//! Socket broker for the optional dongle-Wi-Fi transport.
//!
//! The USB dispatch loop never performs socket I/O. It hands bounded commands to this worker and
//! drains bounded events back into CH_TUNNEL. TCP data is credit controlled; UDP is datagram atomic.
//! The broker accepts only explicit local AP addresses and checks accepted TCP peers against the
//! supplied AP prefix. It never opens arbitrary destinations (unlike the separate CH_IP relay).

use ocbm_proto as p;
use std::collections::{HashMap, VecDeque};
use std::io::{Read, Write};
use std::net::{IpAddr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::mpsc::{self, Receiver, SyncSender, TryRecvError, TrySendError};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use std::os::unix::net::UnixStream;
use std::os::unix::io::{AsRawFd, RawFd};
use std::sync::Mutex;

const COMMAND_CAP: usize = 256;
const EVENT_CAP: usize = 512;
const MAX_OBJECTS: usize = 32;
const MAX_TCP_CREDIT: u32 = 256 * 1024;
const TCP_CHUNK: usize = 16 * 1024;
const UDP_MAX: usize = 60 * 1024;
const COMMAND_BATCH: usize = 64;
const DATAGRAM_BATCH: usize = 32;

pub struct TunnelBroker {
    commands: SyncSender<Vec<u8>>,
    command_wake: UnixStream,
    events: Receiver<Event>,
    wake: UnixStream,
    timing: Mutex<EventTiming>,
    diagnostics: Receiver<String>,
}

struct Event { payload: Vec<u8>, queued: Instant }
struct EventSender { tx: SyncSender<Event>, wake: UnixStream, diagnostics: SyncSender<String> }
struct EventTiming { since: Instant, count: u64, bytes: usize, max_age_us: u128 }

impl TunnelBroker {
    pub fn new() -> Self {
        let (cmd_tx, cmd_rx) = mpsc::sync_channel(COMMAND_CAP);
        let (command_reader, command_wake) = UnixStream::pair().expect("tunnel command wake socketpair");
        command_reader.set_nonblocking(true).expect("command reader nonblocking");
        command_wake.set_nonblocking(true).expect("command writer nonblocking");
        let (event_tx, event_rx) = mpsc::sync_channel(EVENT_CAP);
        let (wake_rx, wake_tx) = UnixStream::pair().expect("tunnel wake socketpair");
        wake_rx.set_nonblocking(true).expect("tunnel wake reader nonblocking");
        wake_tx.set_nonblocking(true).expect("tunnel wake writer nonblocking");
        let (diag_tx, diagnostics) = mpsc::sync_channel(64);
        start_wifi_metrics(diag_tx.clone());
        let event_tx = EventSender { tx: event_tx, wake: wake_tx, diagnostics: diag_tx };
        thread::Builder::new()
            .name("ocbm-socket-tunnel".into())
            .spawn(move || run(cmd_rx, command_reader, event_tx))
            .expect("spawn OCBM socket tunnel worker");
        Self { commands: cmd_tx, command_wake, events: event_rx, wake: wake_rx, diagnostics, timing: Mutex::new(EventTiming { since: Instant::now(), count: 0, bytes: 0, max_age_us: 0 }) }
    }

    /// USB dispatch path: copy at most one bounded OCBM payload and never wait for network I/O.
    pub fn command(&self, payload: &[u8]) -> bool {
        if payload.len() > p::MAX_PAYLOAD { return false; }
        if self.commands.try_send(payload.to_vec()).is_err() { return false; }
        notify(&self.command_wake);
        true
    }

    /// Drain summaries through the daemon's normal CH_LOG encoder, independently of stderr.
    pub fn take_metrics(&self) -> Option<String> {
        if let Ok(line) = self.diagnostics.try_recv() { return Some(line); }
        let mut t = self.timing.lock().unwrap();
        if t.since.elapsed() < Duration::from_secs(5) { return None; }
        let line = format!("[tunnel-perf] implementation=tunnel-poll-v4 events={} bytes={} interval_ms={} max_event_age_us={}", t.count, t.bytes, t.since.elapsed().as_millis(), t.max_age_us);
        *t = EventTiming { since: Instant::now(), count: 0, bytes: 0, max_age_us: 0 };
        Some(line)
    }

    pub fn wake_fd(&self) -> RawFd { self.wake.as_raw_fd() }

    // Clear notifications BEFORE consuming events. Publications racing this drain either appear
    // in the ensuing event batch or leave the descriptor readable for the next poll.
    pub fn clear_wake(&self) {
        let mut socket = &self.wake;
        let mut bytes = [0u8; 512];
        loop {
            match socket.read(&mut bytes) {
                Ok(0) => break,
                Ok(_) => {},
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => break,
            }
        }
    }

    /// Non-blocking event drain. The caller frames returned payloads on CH_TUNNEL.
    pub fn event(&self) -> Option<Vec<u8>> {
        let event = self.events.try_recv().ok()?;
        // Event capacity is readiness too: retry pending accepts/TCP data/credits immediately.
        notify(&self.command_wake);
        let mut timing = self.timing.lock().unwrap();
        timing.count += 1;
        timing.bytes += event.payload.len();
        timing.max_age_us = timing.max_age_us.max(event.queued.elapsed().as_micros());
        Some(event.payload)
    }
}

impl Drop for TunnelBroker {
    fn drop(&mut self) {
        // Wake an idle worker even before Rust drops the command sender.
        let _ = self.command_wake.shutdown(std::net::Shutdown::Both);
    }
}

fn notify(socket: &UnixStream) {
    let mut socket = socket;
    loop {
        match socket.write(&[1]) {
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            _ => break, // A full wake socket already has a readable notification.
        }
    }
}

fn clear_notification(mut socket: &UnixStream) -> bool {
    let mut bytes = [0u8; 512];
    loop {
        match socket.read(&mut bytes) {
            Ok(0) => return false,
            Ok(_) => {},
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => return true,
            Err(_) => return false,
        }
    }
}

#[derive(Clone, Copy)]
struct Prefix { ip: IpAddr, bits: u8 }

impl Prefix {
    fn contains(self, candidate: IpAddr) -> bool {
        match (self.ip, candidate) {
            (IpAddr::V4(a), IpAddr::V4(b)) if self.bits <= 32 => {
                let mask = if self.bits == 0 { 0 } else { u32::MAX << (32 - self.bits) };
                u32::from(a) & mask == u32::from(b) & mask
            }
            (IpAddr::V6(a), IpAddr::V6(b)) if self.bits <= 128 => {
                let mask = if self.bits == 0 { 0 } else { u128::MAX << (128 - self.bits) };
                u128::from(a) & mask == u128::from(b) & mask
            }
            _ => false,
        }
    }
}

enum Object {
    Listener { socket: TcpListener, prefix: Prefix, credit: u32 },
    Tcp { socket: TcpStream, credit: u32, rx_credit: u32, pending_credit: u32, pending: Option<Vec<u8>>, writeq: VecDeque<Vec<u8>>, write_offset: usize, write_eof: bool, read_eof: bool, remote_eof: bool, timing: TcpTiming },
    Udp { socket: UdpSocket, prefix: Prefix, timing: UdpTiming },
}

#[derive(Default)]
struct UdpTiming {
    packets: u64, bytes: u64, drops: u64, rejected: u64,
    last: Option<Instant>, max_gap_us: u128, report: Option<Instant>,
    kernel_last_ns: Option<u64>, kernel_samples: u64, kernel_max_gap_us: u64,
    kernel_max_age_us: u64, overflow_last: u32, overflow_delta: u64,
    truncated: u64, control_truncated: u64,
    audio_rtp: bool, rtp: RtpRx,
}
/// RTP headers remain clear for the encrypted audio stream. Only sequence metadata is kept.
#[derive(Default)]
struct RtpRx {
    source: Option<u32>, last: Option<u16>, packets: u64, gaps: u64, missing: u64,
    late: u64, duplicates: u64, invalid: u64, source_changes: u64, gap_logs: u8,
}
impl RtpRx {
    fn observe(&mut self, packet: &[u8]) -> Option<(u32, u16, u16, u16, u32)> {
        if packet.len() < 12 || packet[0] >> 6 != 2 || packet.len() < 12 + (packet[0] as usize & 15) * 4 {
            self.invalid += 1; return None;
        }
        let seq = u16::from_be_bytes([packet[2], packet[3]]);
        let rtp_timestamp = u32::from_be_bytes([packet[4], packet[5], packet[6], packet[7]]);
        let ssrc = u32::from_be_bytes([packet[8], packet[9], packet[10], packet[11]]);
        self.packets += 1;
        if self.source != Some(ssrc) {
            if self.source.is_some() { self.source_changes += 1; }
            self.source = Some(ssrc); self.last = Some(seq); return None;
        }
        let previous = self.last.unwrap_or(seq);
        let delta = seq.wrapping_sub(previous);
        match delta {
            0 => self.duplicates += 1,
            1 => self.last = Some(seq),
            2..=32767 => {
                self.last = Some(seq); self.gaps += 1; self.missing += (delta - 1) as u64;
                if self.gap_logs < 4 { self.gap_logs += 1; return Some((ssrc, previous, seq, delta - 1, rtp_timestamp)); }
            }
            _ => self.late += 1,
        }
        None
    }
}
impl UdpTiming {
    fn received(&mut self, bytes: usize) {
        let now = Instant::now();
        if let Some(last) = self.last { self.max_gap_us = self.max_gap_us.max(now.duration_since(last).as_micros()); }
        self.last = Some(now); self.packets += 1; self.bytes += bytes as u64;
    }
    fn kernel(&mut self, metadata: UdpMetadata) {
        if let Some(ns) = metadata.timestamp_ns {
            self.kernel_samples += 1;
            if let Some(last) = self.kernel_last_ns { if ns >= last { self.kernel_max_gap_us = self.kernel_max_gap_us.max((ns-last)/1000); } }
            self.kernel_last_ns = Some(ns);
            // SO_TIMESTAMPNS uses CLOCK_REALTIME. Ignore backwards wall-clock adjustments.
            if let Ok(now) = SystemTime::now().duration_since(UNIX_EPOCH) {
                let now = now.as_nanos().min(u64::MAX as u128) as u64;
                if now >= ns { self.kernel_max_age_us = self.kernel_max_age_us.max((now-ns)/1000); }
            }
        }
        if let Some(count) = metadata.overflow {
            self.overflow_delta += count.wrapping_sub(self.overflow_last) as u64;
            self.overflow_last = count;
        }
        self.control_truncated += metadata.control_truncated as u64;
    }
    fn report(&mut self, id: u32, port: u16) -> Option<String> {
        let now = Instant::now();
        let start = self.report.get_or_insert(now);
        if now.duration_since(*start) < Duration::from_secs(5) { return None; }
        let idle_ms = self.last.map(|last| now.duration_since(last).as_millis()).unwrap_or(0);
        let line = format!("[udp-perf] id={id} port={port} packets={} bytes={} event_drops={} rejected={} max_gap_us={} idle_ms={idle_ms} interval_ms={}", self.packets, self.bytes, self.drops, self.rejected, self.max_gap_us, now.duration_since(*start).as_millis());
        let line = format!("{line} kernel_samples={} kernel_max_gap_us={} kernel_max_age_us={} socket_drops={} socket_drops_total={} truncated={} control_truncated={}",
            self.kernel_samples, self.kernel_max_gap_us, self.kernel_max_age_us, self.overflow_delta, self.overflow_last, self.truncated, self.control_truncated);
        let line = if self.audio_rtp { format!("{line} rtp_packets={} rtp_gap_events={} rtp_missing_provisional={} rtp_late={} rtp_duplicates={} rtp_invalid={} rtp_source_changes={}", self.rtp.packets, self.rtp.gaps, self.rtp.missing, self.rtp.late, self.rtp.duplicates, self.rtp.invalid, self.rtp.source_changes) } else { line };
        self.rtp.packets=0; self.rtp.gaps=0; self.rtp.missing=0; self.rtp.late=0; self.rtp.duplicates=0; self.rtp.invalid=0; self.rtp.source_changes=0; self.rtp.gap_logs=0;
        self.kernel_samples=0; self.kernel_max_gap_us=0; self.kernel_max_age_us=0; self.overflow_delta=0; self.truncated=0; self.control_truncated=0;
        self.packets=0; self.bytes=0; self.drops=0; self.rejected=0; self.max_gap_us=0; self.report=Some(now);
        Some(line)
    }
}

// Ancillary receive data never changes the tunnel wire format. Missing kernel support is
// explicitly reported at bind; non-Linux host tests use ordinary recv_from.
#[derive(Default)]
struct UdpMetadata { timestamp_ns: Option<u64>, overflow: Option<u32>, truncated: bool, control_truncated: bool }

#[cfg(target_os = "linux")]
fn enable_udp_metrics(socket: &UdpSocket) -> String {
    let enabled: libc::c_int = 1;
    let set = |option| unsafe { libc::setsockopt(socket.as_raw_fd(), libc::SOL_SOCKET, option,
        &enabled as *const _ as *const libc::c_void, std::mem::size_of_val(&enabled) as libc::socklen_t) == 0 };
    format!("kernel_timestamp={} socket_overflow={}", set(libc::SO_TIMESTAMPNS), set(libc::SO_RXQ_OVFL))
}
#[cfg(not(target_os = "linux"))]
fn enable_udp_metrics(_: &UdpSocket) -> String { "kernel_timestamp=false socket_overflow=false platform=non-linux".into() }

#[cfg(target_os = "linux")]
fn receive_udp(socket: &UdpSocket, buffer: &mut [u8]) -> std::io::Result<(usize, SocketAddr, UdpMetadata)> {
    unsafe {
        let mut address: libc::sockaddr_storage = std::mem::zeroed();
        let mut iov = libc::iovec { iov_base: buffer.as_mut_ptr().cast(), iov_len: buffer.len() };
        // cmsghdr-aligned storage, large enough for timespec and overflow count on both ABIs.
        let mut control = [0usize; 32];
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_name = (&mut address as *mut libc::sockaddr_storage).cast();
        msg.msg_namelen = std::mem::size_of_val(&address) as libc::socklen_t;
        msg.msg_iov = &mut iov; msg.msg_iovlen = 1;
        msg.msg_control = control.as_mut_ptr().cast(); msg.msg_controllen = std::mem::size_of_val(&control) as _;
        let n = libc::recvmsg(socket.as_raw_fd(), &mut msg, 0);
        if n < 0 { return Err(std::io::Error::last_os_error()); }
        let peer = match address.ss_family as libc::c_int {
            libc::AF_INET => {
                let a = &*((&address as *const libc::sockaddr_storage).cast::<libc::sockaddr_in>());
                SocketAddr::new(std::net::Ipv4Addr::from(a.sin_addr.s_addr.to_ne_bytes()).into(), u16::from_be(a.sin_port))
            },
            libc::AF_INET6 => {
                let a = &*((&address as *const libc::sockaddr_storage).cast::<libc::sockaddr_in6>());
                SocketAddr::V6(std::net::SocketAddrV6::new(std::net::Ipv6Addr::from(a.sin6_addr.s6_addr), u16::from_be(a.sin6_port), 0, a.sin6_scope_id))
            },
            _ => return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "unknown UDP peer family")),
        };
        let mut metadata = UdpMetadata { truncated: msg.msg_flags & libc::MSG_TRUNC != 0,
            control_truncated: msg.msg_flags & libc::MSG_CTRUNC != 0, ..Default::default() };
        let mut cmsg = libc::CMSG_FIRSTHDR(&msg);
        while !cmsg.is_null() {
            if (*cmsg).cmsg_level == libc::SOL_SOCKET {
                if (*cmsg).cmsg_type == libc::SO_TIMESTAMPNS && (*cmsg).cmsg_len as usize >= libc::CMSG_LEN(std::mem::size_of::<libc::timespec>() as _) as usize {
                    let t = std::ptr::read_unaligned(libc::CMSG_DATA(cmsg).cast::<libc::timespec>());
                    if t.tv_sec >= 0 && t.tv_nsec >= 0 && t.tv_nsec < 1_000_000_000 {
                        metadata.timestamp_ns = (t.tv_sec as u64).checked_mul(1_000_000_000).and_then(|sec| sec.checked_add(t.tv_nsec as u64));
                    }
                } else if (*cmsg).cmsg_type == libc::SO_RXQ_OVFL && (*cmsg).cmsg_len as usize >= libc::CMSG_LEN(4) as usize {
                    metadata.overflow = Some(std::ptr::read_unaligned(libc::CMSG_DATA(cmsg).cast::<u32>()));
                }
            }
            cmsg = libc::CMSG_NXTHDR(&msg, cmsg);
        }
        Ok((n as usize, peer, metadata))
    }
}
#[cfg(not(target_os = "linux"))]
fn receive_udp(socket: &UdpSocket, buffer: &mut [u8]) -> std::io::Result<(usize, SocketAddr, UdpMetadata)> {
    socket.recv_from(buffer).map(|(n, peer)| (n, peer, UdpMetadata::default()))
}

#[derive(Default)]
struct TcpTiming { since: Option<Instant>, last: Option<Instant>, packets: u64, bytes: u64,
    max_gap_us: u128, peak_unread: libc::c_int, credit_since: Option<Instant>, max_credit_wait_us: u128 }
impl TcpTiming {
    fn received(&mut self, bytes: usize) {
        let now = Instant::now();
        if let Some(last) = self.last { self.max_gap_us = self.max_gap_us.max(now.duration_since(last).as_micros()); }
        self.last = Some(now); self.packets += 1; self.bytes += bytes as u64;
    }
    fn report(&mut self, socket: &TcpStream, id: u32, credit: u32, pending: bool, events: &EventSender) {
        let now = Instant::now();
        if credit == 0 {
            self.credit_since.get_or_insert(now);
        } else if let Some(start) = self.credit_since.take() { self.max_credit_wait_us = self.max_credit_wait_us.max(start.elapsed().as_micros()); }
        let start = self.since.get_or_insert(now);
        if start.elapsed() < Duration::from_secs(5) { return; }
        let waiting = self.credit_since.map(|s| s.elapsed().as_micros()).unwrap_or(0);
        let _ = events.diagnostics.try_send(format!("[tcp-perf] id={id} port={} packets={} bytes={} max_gap_us={} idle_ms={} peak_unread_bytes={} credit={} max_credit_wait_us={} pending_event={pending} interval_ms={}",
            socket.local_addr().map(|a| a.port()).unwrap_or(0), self.packets, self.bytes, self.max_gap_us,
            self.last.map(|s| s.elapsed().as_millis()).unwrap_or(0), self.peak_unread, credit, self.max_credit_wait_us.max(waiting), start.elapsed().as_millis()));
        self.since=Some(now); self.packets=0; self.bytes=0; self.max_gap_us=0; self.peak_unread=0; self.max_credit_wait_us=0;
        if self.credit_since.is_some() { self.credit_since=Some(now); }
    }
}

struct Mdns { socket: UdpSocket, prefix: Prefix }

fn run(commands: Receiver<Vec<u8>>, command_wake: UnixStream, events: EventSender) {
    let mut epoch = 0u32;
    let mut objects: HashMap<u32, Object> = HashMap::new();
    let mut pending_accepts: Vec<(u32, u32, TcpStream, SocketAddr, u32)> = Vec::new();
    let mut mdns: Option<Mdns> = None;
    let mut next_stream = 0x8000_0000u32;
    let mut rx = vec![0u8; TCP_CHUNK.max(UDP_MAX)];
    let mut report_at = Instant::now();
    let (mut turns, mut active_us, mut max_active_us, mut max_commands_us, mut max_prepare_us, mut max_service_us, mut max_poll_us, mut max_timeout_late_us) = (0u64, 0u128, 0u128, 0u128, 0u128, 0u128, 0u128, 0u128);
    loop {
        let turn_start = Instant::now();
        if !clear_notification(&command_wake) { return; }
        let mut command_count = 0;
        for _ in 0..COMMAND_BATCH {
            match commands.try_recv() {
                Ok(frame) => {
                    command_count += 1;
                    if matches!(frame.first(), Some(&p::TUNNEL_START) | Some(&p::TUNNEL_STOP)) { pending_accepts.clear(); }
                    command(&frame, &mut epoch, &mut objects, &mut next_stream, &mut mdns, &events)
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => return,
            }
        }

        max_commands_us = max_commands_us.max(turn_start.elapsed().as_micros());
        let prepare_start = Instant::now();
        let mut pending_index = 0;
        while pending_index < pending_accepts.len() {
            let (stream_id, listener_id, _stream, peer, _credit) = &pending_accepts[pending_index];
            let mut payload = header(p::TUNNEL_ACCEPT, epoch, *stream_id);
            payload.extend_from_slice(&listener_id.to_le_bytes());
            payload.extend_from_slice(&(*_credit).min(MAX_TCP_CREDIT).to_le_bytes());
            encode_peer(&mut payload, *peer);
            if try_event(&events, payload).is_ok() {
                let (stream_id, _listener_id, stream, _peer, credit) = pending_accepts.swap_remove(pending_index);
                let _ = stream.set_nonblocking(true);
                objects.insert(stream_id, Object::Tcp { socket: stream, credit: 0, rx_credit: credit.min(MAX_TCP_CREDIT), pending_credit: 0, pending: None, writeq: VecDeque::new(), write_offset: 0, write_eof: false, read_eof: false, remote_eof: false, timing: TcpTiming::default() });
            } else { pending_index += 1; }
        }

        let can_accept = objects.len() + pending_accepts.len() < MAX_OBJECTS;
        // Only watch useful readiness. In particular, do not watch TCP reads while credit is
        // exhausted or a reliable event is pending: a readable socket would otherwise spin.
        let mut pollfds = vec![libc::pollfd { fd: command_wake.as_raw_fd(), events: libc::POLLIN, revents: 0 }];
        let mut slots = HashMap::new();
        for (&id, object) in objects.iter_mut() {
            let (fd, interest) = match object {
                Object::Listener { socket, .. } => (socket.as_raw_fd(), if can_accept { libc::POLLIN } else { 0 }),
                Object::Udp { socket, .. } => (socket.as_raw_fd(), libc::POLLIN),
                Object::Tcp { socket, credit, rx_credit, pending_credit, pending, writeq, write_eof, read_eof, timing, .. } => {
                    timing.report(socket, id, *credit, pending.is_some(), &events);
                    if *pending_credit > 0 {
                        let amount = (*pending_credit).min(MAX_TCP_CREDIT);
                        let mut e = header(p::TUNNEL_TCP_CREDIT, epoch, id);
                        e.extend_from_slice(&amount.to_le_bytes());
                        if try_event(&events, e).is_ok() {
                            *pending_credit -= amount;
                            *rx_credit = rx_credit.saturating_add(amount).min(MAX_TCP_CREDIT);
                        }
                    }
                    if let Some(event) = pending.take() {
                        let bytes = event.len().saturating_sub(9) as u32;
                        if let Err(event) = try_event(&events, event) { *pending = Some(event); }
                        else { *credit = credit.saturating_sub(bytes); }
                    }
                    if writeq.is_empty() && *write_eof {
                        let _ = socket.shutdown(std::net::Shutdown::Write);
                        *write_eof = false;
                    }
                    let mut interest = 0;
                    if *credit > 0 && pending.is_none() && !*read_eof { interest |= libc::POLLIN; }
                    if !writeq.is_empty() { interest |= libc::POLLOUT; }
                    (socket.as_raw_fd(), interest)
                }
            };
            slots.insert(id, pollfds.len());
            pollfds.push(libc::pollfd { fd: if interest == 0 { -1 } else { fd }, events: interest, revents: 0 });
        }
        let mdns_slot = mdns.as_ref().map(|state| {
            let index = pollfds.len();
            pollfds.push(libc::pollfd { fd: state.socket.as_raw_fd(), events: libc::POLLIN, revents: 0 });
            index
        });
        // The timer exists only to emit idle diagnostics. Commands and freed event capacity
        // wake the descriptor; a full command batch gets an immediate next pass.
        max_prepare_us = max_prepare_us.max(prepare_start.elapsed().as_micros());
        let before_poll_us = turn_start.elapsed().as_micros();
        let poll_start = Instant::now();
        let timeout_ms = if command_count == COMMAND_BATCH { 0 } else { 1000 };
        let ready = unsafe { libc::poll(pollfds.as_mut_ptr(), pollfds.len() as libc::nfds_t,
            timeout_ms) };
        let poll_us = poll_start.elapsed().as_micros();
        max_poll_us = max_poll_us.max(poll_us);
        if ready == 0 { max_timeout_late_us = max_timeout_late_us.max(poll_us.saturating_sub(timeout_ms as u128 * 1000)); }
        let service_start = Instant::now();
        if ready < 0 {
            if std::io::Error::last_os_error().kind() == std::io::ErrorKind::Interrupted { continue; }
            let _ = events.diagnostics.try_send(format!("[tunnel-perf] poll_error={}", std::io::Error::last_os_error()));
            return;
        }

        if let Some(mdns_state) = &mdns {
            for _ in 0..if mdns_slot.is_some_and(|i| pollfds[i].revents != 0) { DATAGRAM_BATCH } else { 0 } {
                match mdns_state.socket.recv_from(&mut rx[..9000]) {
                    Ok((n, peer)) if mdns_state.prefix.contains(peer.ip()) => {
                        let mut event = header(p::TUNNEL_MDNS_PACKET, epoch, 0);
                        encode_peer(&mut event, peer);
                        event.extend_from_slice(&rx[..n]);
                        let _ = try_event(&events, event);
                    }
                    Ok(_) => {}
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                    Err(_) => { mdns = None; break; }
                }
            }
        }
        let ids: Vec<u32> = objects.keys().copied().collect();
        for id in ids {
            let readiness = slots.get(&id).map(|&i| pollfds[i].revents).unwrap_or(0);
            let readable = readiness & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0;
            let writable = readiness & (libc::POLLOUT | libc::POLLHUP | libc::POLLERR) != 0;
            let mut remove = false;
            match objects.get_mut(&id) {
                Some(Object::Listener { socket, prefix, credit }) => {
                    let accepted = if can_accept && readable { match socket.accept() {
                        Ok((stream, peer)) => Some((stream, peer, *prefix, *credit)),
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => None,
                        Err(_) => { remove = true; None }
                    }} else { None };
                    if let Some((stream, peer, prefix, credit)) = accepted {
                        if !prefix.contains(peer.ip()) || objects.len() + pending_accepts.len() >= MAX_OBJECTS {
                            drop(stream);
                        } else {
                            // Small control/HID writes must not wait for Nagle batching.
                            if stream.set_nodelay(true).is_err() { continue; }
                            let _ = stream.set_nonblocking(true);
                            let stream_id = allocate_id(&mut next_stream, &objects);
                            let mut payload = header(p::TUNNEL_ACCEPT, epoch, stream_id);
                            payload.extend_from_slice(&id.to_le_bytes());
                            payload.extend_from_slice(&credit.min(MAX_TCP_CREDIT).to_le_bytes());
                            encode_peer(&mut payload, peer);
                            if try_event(&events, payload).is_ok() {
                                // The listener's initial window is granted to each accepted stream.
                                objects.insert(stream_id, Object::Tcp { socket: stream, credit: 0, rx_credit: credit.min(MAX_TCP_CREDIT), pending_credit: 0, pending: None, writeq: VecDeque::new(), write_offset: 0, write_eof: false, read_eof: false, remote_eof: false, timing: TcpTiming::default() });
                            } else {
                                pending_accepts.push((stream_id, id, stream, peer, credit));
                            }
                        }
                    }
                }
                Some(Object::Tcp { socket, credit, pending_credit, pending, writeq, write_offset, write_eof, read_eof, remote_eof, timing, .. }) => {
                    if readable && pending.is_none() && *credit > 0 && !*read_eof {
                        let nmax = (*credit as usize).min(TCP_CHUNK);
                        let mut unread: libc::c_int = 0;
                        if unsafe { libc::ioctl(socket.as_raw_fd(), libc::FIONREAD, &mut unread) } == 0 { timing.peak_unread = timing.peak_unread.max(unread); }
                        match socket.read(&mut rx[..nmax]) {
                            Ok(0) => {
                                let _ = events.diagnostics.try_send(format!("[tunnel-close] epoch={epoch} id={id} source=phone_socket event=read_eof peer={:?} local={:?} revents={readiness:#x}", socket.peer_addr().ok(), socket.local_addr().ok()));
                                let eof = header(p::TUNNEL_TCP_EOF, epoch, id);
                                if let Err(eof) = try_event(&events, eof) { *pending = Some(eof); }
                                *read_eof = true;
                            }
                            Ok(n) => {
                                timing.received(n);
                                let mut event = header(p::TUNNEL_TCP_DATA, epoch, id);
                                event.extend_from_slice(&rx[..n]);
                                match try_event(&events, event) {
                                    Ok(()) => *credit = credit.saturating_sub(n as u32),
                                    Err(event) => *pending = Some(event),
                                }
                            }
                            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
                            Err(e) => { let _ = events.diagnostics.try_send(format!("[tunnel-close] epoch={epoch} id={id} source=phone_socket event=read_error kind={:?} errno={:?} error={e} revents={readiness:#x}", e.kind(), e.raw_os_error())); remove = true; },
                        }
                    }
                    if writable { if let Some(front) = writeq.front() {
                        match socket.write(&front[*write_offset..]) {
                            Ok(0) => { let _ = events.diagnostics.try_send(format!("[tunnel-close] epoch={epoch} id={id} source=phone_socket event=write_zero revents={readiness:#x}")); remove = true; },
                            Ok(n) => {
                                *write_offset += n;
                                *pending_credit = pending_credit.saturating_add(n as u32);
                                if *write_offset == front.len() { writeq.pop_front(); *write_offset = 0; }
                            }
                            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
                            Err(e) => { let _ = events.diagnostics.try_send(format!("[tunnel-close] epoch={epoch} id={id} source=phone_socket event=write_error kind={:?} errno={:?} error={e} revents={readiness:#x}", e.kind(), e.raw_os_error())); remove = true; },
                        }
                    }}
                    if writeq.is_empty() && *write_eof {
                        let _ = socket.shutdown(std::net::Shutdown::Write);
                        *write_eof = false;
                    }
                    if *read_eof && *remote_eof && writeq.is_empty() && !*write_eof { let _ = events.diagnostics.try_send(format!("[tunnel-close] epoch={epoch} id={id} source=both event=half_closes_complete")); remove = true; }
                }
                Some(Object::Udp { socket, prefix, timing }) => {
                    if let Some(line) = timing.report(id, socket.local_addr().map(|a| a.port()).unwrap_or(0)) { let _ = events.diagnostics.try_send(line); }
                    for _ in 0..if readable { DATAGRAM_BATCH } else { 0 } {
                    match receive_udp(socket, &mut rx[..UDP_MAX]) {
                        Ok((n, peer, metadata)) if prefix.contains(peer.ip()) => {
                            let truncated = metadata.truncated;
                            let kernel_ns = metadata.timestamp_ns;
                            timing.kernel(metadata);
                            if truncated || n > UDP_MAX { timing.truncated += 1; continue; }
                            timing.received(n);
                            if timing.audio_rtp {
                                if let Some((ssrc, previous, current, missing, rtp_timestamp)) = timing.rtp.observe(&rx[..n]) {
                                    let timestamp = kernel_ns.map(|n| n.to_string()).unwrap_or_else(|| "unavailable".into());
                                    let _ = events.diagnostics.try_send(format!("[rtp-rx-gap] epoch={epoch} id={id} port={} ssrc={ssrc:08x} previous={previous} current={current} missing_provisional={missing} rtp_timestamp={rtp_timestamp} kernel_unix_ns={timestamp}", socket.local_addr().map(|a| a.port()).unwrap_or(0)));
                                }
                            }
                            let mut event = header(p::TUNNEL_UDP_DATAGRAM, epoch, id);
                            encode_peer(&mut event, peer);
                            event.extend_from_slice(&rx[..n]);
                            if try_event(&events, event).is_err() { timing.drops += 1; }
                        }
                        Ok(_) => { timing.rejected += 1; }
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                        Err(e) => {
                            let _ = events.diagnostics.try_send(format!("[udp-perf] id={id} receive_error={e}"));
                            remove = true; break;
                        }
                    }
                }},
                None => {}
            }
            if remove {
                objects.remove(&id);
                let _ = try_event(&events, header(p::TUNNEL_CLOSE, epoch, id));
            }
        }
        let service_us = service_start.elapsed().as_micros();
        max_service_us = max_service_us.max(service_us);
        let active = before_poll_us + service_us;
        active_us += active; max_active_us = max_active_us.max(active); turns += 1;
        if report_at.elapsed() >= Duration::from_secs(5) {
            let _ = events.diagnostics.try_send(format!("[broker-perf] turns={turns} active_us={active_us} max_active_us={max_active_us} max_commands_us={max_commands_us} max_prepare_us={max_prepare_us} max_service_us={max_service_us} max_poll_us={max_poll_us} max_timeout_late_us={max_timeout_late_us} interval_ms={}", report_at.elapsed().as_millis()));
            report_at=Instant::now(); turns=0; active_us=0; max_active_us=0; max_commands_us=0; max_prepare_us=0; max_service_us=0; max_poll_us=0; max_timeout_late_us=0;
        }


    }
}

fn command(frame: &[u8], epoch: &mut u32, objects: &mut HashMap<u32, Object>, next: &mut u32, mdns: &mut Option<Mdns>, events: &EventSender) {
    if frame.len() < 9 { return; }
    let op = frame[0];
    let incoming_epoch = u32::from_le_bytes(frame[1..5].try_into().unwrap());
    let id = u32::from_le_bytes(frame[5..9].try_into().unwrap());
    if op == p::TUNNEL_START {
        objects.clear(); *mdns = None; *epoch = incoming_epoch;
        return;
    }
    // epoch=0 is the daemon's fail-closed reset signal (e.g. outbound USB queue overflow).
    if op == p::TUNNEL_STOP && incoming_epoch == 0 {
        objects.clear(); *mdns = None; *epoch = 0;
        return;
    }
    if incoming_epoch != *epoch { return; }
    match op {
        p::TUNNEL_STOP => { objects.clear(); *mdns = None; *epoch = 0; }
        p::TUNNEL_CLOSE => { if objects.contains_key(&id) { let _ = events.diagnostics.try_send(format!("[tunnel-close] epoch={} id={id} source=host event=close reason={:?}", *epoch, frame.get(9))); } objects.remove(&id); }
        p::TUNNEL_LISTEN_TCP | p::TUNNEL_BIND_UDP => {
            let parsed = parse_bind(&frame[9..]);
            let (addr, prefix, port, credit) = match parsed {
                Some(x) => x,
                None => { result(events, *epoch, id, 1, 0); return; }
            };
            if objects.len() >= MAX_OBJECTS { result(events, *epoch, id, 2, 0); return; }
            let bound = if op == p::TUNNEL_LISTEN_TCP {
                TcpListener::bind(SocketAddr::new(addr, port)).map(|s| { let _ = s.set_nonblocking(true); Object::Listener { socket: s, prefix, credit } })
            } else {
                UdpSocket::bind(SocketAddr::new(addr, port)).map(|s| { let _ = s.set_nonblocking(true); { let support = enable_udp_metrics(&s); let mut timing = UdpTiming::default(); let base = if frame.get(9) == Some(&4) { 12 } else { 24 }; timing.audio_rtp = frame.get(9 + base) == Some(&1); let _ = events.diagnostics.try_send(format!("[udp-perf] id={id} {support} audio_rtp={}", timing.audio_rtp)); Object::Udp { socket: s, prefix, timing } } })
            };
            match bound {
                Ok(object) => {
                    let actual_port = match &object { Object::Listener { socket, .. } => socket.local_addr().map(|x| x.port()).unwrap_or(0), Object::Udp { socket, .. } => socket.local_addr().map(|x| x.port()).unwrap_or(0), _ => 0 };
                    objects.insert(id, object); result(events, *epoch, id, 0, actual_port);
                }
                Err(_) => result(events, *epoch, id, 3, 0),
            }
        }
        p::TUNNEL_TCP_CREDIT if frame.len() == 13 => {
            if let Some(Object::Tcp { credit, .. }) = objects.get_mut(&id) {
                *credit = credit.saturating_add(u32::from_le_bytes(frame[9..13].try_into().unwrap())).min(MAX_TCP_CREDIT);
            }
        }
        p::TUNNEL_TCP_DATA => {
            if let Some(Object::Tcp { writeq, rx_credit, .. }) = objects.get_mut(&id) {
                let len = frame.len().saturating_sub(9);
                if len > 0 && len <= *rx_credit as usize && writeq.iter().map(Vec::len).sum::<usize>() + len <= MAX_TCP_CREDIT as usize {
                    *rx_credit -= len as u32;
                    writeq.push_back(frame[9..].to_vec());
                } else {
                    let _ = events.diagnostics.try_send(format!("[tunnel-close] epoch={} id={id} source=host event=invalid_data bytes={len} remaining_credit={rx_credit}", *epoch));
                    objects.remove(&id);
                }
            }
        }
        // Host closed its write half. Drain queued bytes then half-close the AP socket; keep
        // forwarding the adapter peer's read half until it independently closes.
        p::TUNNEL_TCP_EOF => if let Some(Object::Tcp { write_eof, remote_eof, .. }) = objects.get_mut(&id) { let _ = events.diagnostics.try_send(format!("[tunnel-close] epoch={} id={id} source=host event=write_eof", *epoch)); *write_eof = true; *remote_eof = true; },
        p::TUNNEL_UDP_SEND => {
            if let Some(Object::Udp { socket, prefix, .. }) = objects.get(&id) {
                if let Some((peer, body)) = parse_peer(&frame[9..]) {
                    if body.len() <= UDP_MAX && prefix.contains(peer.ip()) { let _ = socket.send_to(body, peer); }
                }
            }
        }
        p::TUNNEL_MDNS_START => {
            if let Some(ip) = parse_ip(frame.get(9..).unwrap_or_default()) {
                let prefix = Prefix { ip, bits: if ip.is_ipv4() { 24 } else { 64 } };
                let bind_result = (|| -> std::io::Result<UdpSocket> {
                    let socket = UdpSocket::bind(SocketAddr::new(ip, 5353))?;
                    socket.set_nonblocking(true)?;
                    if let IpAddr::V4(ap) = ip {
                        let group = std::net::Ipv4Addr::new(224, 0, 0, 251);
                        socket.join_multicast_v4(&group, &ap)?;
                    } else { return Err(std::io::Error::new(std::io::ErrorKind::Unsupported, "IPv6 AP mDNS not implemented")); }
                    Ok(socket)
                })();
                match bind_result {
                    Ok(socket) => { *mdns = Some(Mdns { socket, prefix }); result(events, *epoch, 0, 0, 5353); }
                    Err(_) => { *mdns = None; result(events, *epoch, 0, 3, 0); }
                }
            } else { result(events, *epoch, 0, 1, 0); }
        }
        p::TUNNEL_MDNS_SEND if frame.len() > 9 && frame.len() <= 9009 => {
            if let Some(mdns_state) = mdns {
                let group = if mdns_state.prefix.ip.is_ipv4() { "224.0.0.251:5353" } else { "[ff02::fb]:5353" };
                if let Ok(dest) = group.parse::<SocketAddr>() { let _ = mdns_state.socket.send_to(&frame[9..], dest); }
            }
        }
        _ => {}
    }
    let _ = next; // stream IDs are allocated only from accept().
}

fn parse_bind(data: &[u8]) -> Option<(IpAddr, Prefix, u16, u32)> {
    if data.len() < 1 { return None; }
    let family = data[0];
    let n = match family { 4 => 4, 6 => 16, _ => return None };
    if data.len() < 1 + n + 1 + 2 + 4 { return None; }
    let ip = if family == 4 { IpAddr::V4(std::net::Ipv4Addr::new(data[1],data[2],data[3],data[4])) }
        else { IpAddr::V6(std::net::Ipv6Addr::from(<[u8;16]>::try_from(&data[1..17]).ok()?)) };
    let off = 1 + n;
    let bits = data[off];
    let port = u16::from_le_bytes([data[off+1], data[off+2]]);
    let credit = u32::from_le_bytes(data[off+3..off+7].try_into().ok()?);
    let prefix = Prefix { ip, bits };
    if bits > if family == 4 { 32 } else { 128 } || port == 0 { return None; }
    Some((ip, prefix, port, credit.min(MAX_TCP_CREDIT)))
}

fn parse_ip(data: &[u8]) -> Option<IpAddr> {
    match *data.first()? {
        4 if data.len() == 5 => Some(IpAddr::V4(std::net::Ipv4Addr::new(data[1], data[2], data[3], data[4]))),
        6 if data.len() == 17 => Some(IpAddr::V6(std::net::Ipv6Addr::from(<[u8; 16]>::try_from(&data[1..17]).ok()?))),
        _ => None,
    }
}

fn parse_peer(data: &[u8]) -> Option<(SocketAddr, &[u8])> {
    let family = *data.first()?;
    let n = match family { 4 => 4, 6 => 16, _ => return None };
    if data.len() < n + 3 { return None; }
    let ip = if family == 4 { IpAddr::V4(std::net::Ipv4Addr::new(data[1],data[2],data[3],data[4])) }
        else { IpAddr::V6(std::net::Ipv6Addr::from(<[u8;16]>::try_from(&data[1..17]).ok()?)) };
    let off = 1 + n;
    let port = u16::from_be_bytes([data[off], data[off+1]]);
    Some((SocketAddr::new(ip, port), &data[off+2..]))
}

fn encode_peer(out: &mut Vec<u8>, peer: SocketAddr) {
    match peer.ip() {
        IpAddr::V4(ip) => { out.push(4); out.extend_from_slice(&ip.octets()); }
        IpAddr::V6(ip) => { out.push(6); out.extend_from_slice(&ip.octets()); }
    }
    out.extend_from_slice(&peer.port().to_be_bytes());
}

fn header(op: u8, epoch: u32, id: u32) -> Vec<u8> {
    let mut out = Vec::with_capacity(9);
    out.push(op); out.extend_from_slice(&epoch.to_le_bytes()); out.extend_from_slice(&id.to_le_bytes()); out
}

fn result(events: &EventSender, epoch: u32, id: u32, status: u8, port: u16) {
    let mut out = header(p::TUNNEL_RESULT, epoch, id);
    out.push(status); out.extend_from_slice(&port.to_le_bytes());
    let _ = try_event(events, out);
}

fn try_event(events: &EventSender, payload: Vec<u8>) -> Result<(), Vec<u8>> {
    match events.tx.try_send(Event { payload, queued: Instant::now() }) {
        Ok(()) => {
            let mut wake = &events.wake;
            loop {
                match wake.write(&[1]) {
                    Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                    _ => break, // WouldBlock means an existing notification already wakes poll.
                }
            }
            Ok(())
        },
        Err(TrySendError::Full(event)) | Err(TrySendError::Disconnected(event)) => Err(event.payload),
    }
}

fn allocate_id(next: &mut u32, objects: &HashMap<u32, Object>) -> u32 {
    loop { let id = *next; *next = next.wrapping_add(1).max(0x8000_0000); if !objects.contains_key(&id) { return id; } }
}

#[cfg(test)]
mod wake_tests {
    use super::*;

    #[test]
    fn audio_rtp_sequence_tracking_handles_wrap_reordering_and_source_changes() {
        fn packet(seq: u16, ssrc: u32) -> [u8; 12] {
            let mut p = [0u8; 12]; p[0] = 0x80;
            p[2..4].copy_from_slice(&seq.to_be_bytes());
            p[8..12].copy_from_slice(&ssrc.to_be_bytes()); p
        }
        let mut r = RtpRx::default();
        assert_eq!(r.observe(&packet(65534, 9)), None);
        assert_eq!(r.observe(&packet(65535, 9)), None);
        assert_eq!(r.observe(&packet(0, 9)), None);
        assert_eq!(r.observe(&packet(3, 9)), Some((9, 0, 3, 2, 0)));
        assert_eq!(r.observe(&packet(2, 9)), None); // late does not move high-water mark
        assert_eq!(r.observe(&packet(3, 9)), None); // duplicate
        assert_eq!(r.observe(&packet(4, 9)), None);
        assert_eq!(r.observe(&packet(500, 10)), None); // new source resets sequence
        assert_eq!(r.observe(&[0; 4]), None);
        assert_eq!((r.gaps, r.missing, r.late, r.duplicates, r.source_changes, r.invalid), (1, 2, 1, 1, 1, 1));
    }

    fn broker(capacity: usize) -> (TunnelBroker, thread::JoinHandle<()>) {
        let (tx, rx) = mpsc::sync_channel(COMMAND_CAP);
        let (events, incoming) = mpsc::sync_channel(capacity);
        let (reader, command_wake) = UnixStream::pair().unwrap();
        let (wake, writer) = UnixStream::pair().unwrap();
        for socket in [&reader, &command_wake, &wake, &writer] { socket.set_nonblocking(true).unwrap(); }
        let (diagnostics, diagnostic_rx) = mpsc::sync_channel(64);
        let worker = thread::spawn(move || run(rx, reader, EventSender { tx: events, wake: writer, diagnostics }));
        (TunnelBroker { commands: tx, command_wake, events: incoming, wake,
            diagnostics: diagnostic_rx, timing: Mutex::new(EventTiming { since: Instant::now(), count: 0, bytes: 0, max_age_us: 0 }) }, worker)
    }

    fn receive(broker: &TunnelBroker) -> Vec<u8> {
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            broker.clear_wake();
            if let Some(event) = broker.event() { return event; }
            assert!(Instant::now() < deadline, "broker event timed out");
            let mut fd = libc::pollfd { fd: broker.wake_fd(), events: libc::POLLIN, revents: 0 };
            unsafe { libc::poll(&mut fd, 1, 50); }
        }
    }

    fn listen(broker: &TunnelBroker) -> (u16, u32) {
        assert!(broker.command(&header(p::TUNNEL_START, 1, 0)));
        let reservation = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = reservation.local_addr().unwrap().port();
        drop(reservation);
        let mut frame = header(p::TUNNEL_LISTEN_TCP, 1, 1);
        frame.extend_from_slice(&[4, 127, 0, 0, 1, 8]);
        frame.extend_from_slice(&port.to_le_bytes());
        frame.extend_from_slice(&MAX_TCP_CREDIT.to_le_bytes());
        assert!(broker.command(&frame));
        let result = receive(broker);
        assert_eq!(result[0], p::TUNNEL_RESULT);
        assert_eq!(result[9], 0);
        (port, 1)
    }

    #[test]
    fn idle_commands_credit_and_half_close_wake_the_broker() {
        let (broker, worker) = broker(EVENT_CAP);
        let (port, _) = listen(&broker);
        let mut phone = TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port)).unwrap();
        phone.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let accept = receive(&broker);
        assert_eq!(accept[0], p::TUNNEL_ACCEPT);
        let id = u32::from_le_bytes(accept[5..9].try_into().unwrap());
        phone.write_all(b"reply").unwrap();
        // No read credit: readability must not spin or lose the pending bytes.
        let mut frame = header(p::TUNNEL_TCP_CREDIT, 1, id);
        frame.extend_from_slice(&5u32.to_le_bytes());
        assert!(broker.command(&frame));
        let data = receive(&broker);
        assert_eq!(data[0], p::TUNNEL_TCP_DATA);
        assert_eq!(&data[9..], b"reply");
        let mut frame = header(p::TUNNEL_TCP_DATA, 1, id);
        frame.extend_from_slice(b"request");
        assert!(broker.command(&frame));
        let mut body = [0; 7]; phone.read_exact(&mut body).unwrap();
        assert_eq!(&body, b"request");
        let credit = receive(&broker);
        assert_eq!(credit[0], p::TUNNEL_TCP_CREDIT);
        assert!(broker.command(&header(p::TUNNEL_TCP_EOF, 1, id)));
        assert_eq!(phone.read(&mut body).unwrap(), 0);
        drop(broker);
        worker.join().unwrap();
    }

    #[test]
    fn freed_event_capacity_retries_pending_accept_without_network_activity() {
        let (broker, worker) = broker(1);
        let (port, _) = listen(&broker);
        let _first = TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port)).unwrap();
        // Poll for the first accept without consuming it: the event channel is now full.
        let mut fd = libc::pollfd { fd: broker.wake_fd(), events: libc::POLLIN, revents: 0 };
        assert_eq!(unsafe { libc::poll(&mut fd, 1, 1000) }, 1);
        let _second = TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port)).unwrap();
        assert_eq!(receive(&broker)[0], p::TUNNEL_ACCEPT);
        assert_eq!(receive(&broker)[0], p::TUNNEL_ACCEPT);
        drop(broker);
        worker.join().unwrap();
    }

    #[test]
    fn labelled_audio_udp_logs_sequence_gap_and_still_forwards_datagrams() {
        let (broker, worker) = broker(EVENT_CAP);
        assert!(broker.command(&header(p::TUNNEL_START, 7, 0)));
        let reservation = UdpSocket::bind("127.0.0.1:0").unwrap();
        let port = reservation.local_addr().unwrap().port(); drop(reservation);
        let mut bind = header(p::TUNNEL_BIND_UDP, 7, 5);
        bind.extend_from_slice(&[4, 127, 0, 0, 1, 8]);
        bind.extend_from_slice(&port.to_le_bytes()); bind.extend_from_slice(&0u32.to_le_bytes()); bind.push(1);
        assert!(broker.command(&bind)); assert_eq!(receive(&broker)[9], 0);
        let phone = UdpSocket::bind("127.0.0.1:0").unwrap();
        for seq in [100u16, 103] {
            let mut packet = [0u8; 12]; packet[0] = 0x80;
            packet[2..4].copy_from_slice(&seq.to_be_bytes()); packet[11] = 42;
            phone.send_to(&packet, (std::net::Ipv4Addr::LOCALHOST, port)).unwrap();
        }
        for _ in 0..2 { assert_eq!(receive(&broker)[0], p::TUNNEL_UDP_DATAGRAM); }
        let deadline = Instant::now() + Duration::from_secs(1);
        let mut saw_gap = false;
        while Instant::now() < deadline {
            if let Some(line) = broker.take_metrics() {
                if line.contains("[rtp-rx-gap]") {
                    assert!(line.contains("previous=100 current=103 missing_provisional=2"));
                    assert!(line.contains("epoch=7 id=5")); saw_gap = true; break;
                }
            }
            thread::sleep(Duration::from_millis(1));
        }
        assert!(saw_gap, "audio gap diagnostic not emitted");
        drop(broker); worker.join().unwrap();
    }

    #[test]
    fn udp_burst_larger_than_budget_preserves_datagrams_and_services_commands() {
        let (broker, worker) = broker(EVENT_CAP);
        let (tcp_port, _) = listen(&broker);
        let reservation = UdpSocket::bind("127.0.0.1:0").unwrap();
        let port = reservation.local_addr().unwrap().port();
        drop(reservation);
        let mut bind = header(p::TUNNEL_BIND_UDP, 1, 2);
        bind.extend_from_slice(&[4, 127, 0, 0, 1, 8]);
        bind.extend_from_slice(&port.to_le_bytes());
        bind.extend_from_slice(&0u32.to_le_bytes());
        assert!(broker.command(&bind));
        assert_eq!(receive(&broker)[9], 0);
        let phone = UdpSocket::bind("127.0.0.1:0").unwrap();
        for i in 0..64u8 { phone.send_to(&[i], (std::net::Ipv4Addr::LOCALHOST, port)).unwrap(); }
        let _tcp = TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, tcp_port)).unwrap();
        let mut datagrams = Vec::new();
        let mut accepted = false;
        while datagrams.len() < 64 || !accepted {
            let event = receive(&broker);
            match event[0] {
                p::TUNNEL_ACCEPT => accepted = true,
                p::TUNNEL_UDP_DATAGRAM => datagrams.push(parse_peer(&event[9..]).unwrap().1[0]),
                op => panic!("unexpected event {op}"),
            }
        }
        assert_eq!(datagrams, (0..64u8).collect::<Vec<_>>());
        drop(broker);
        worker.join().unwrap();
    }

    #[test]
    fn command_batches_larger_than_budget_do_not_lose_their_wakeup() {
        let (broker, worker) = broker(EVENT_CAP);
        assert!(broker.command(&header(p::TUNNEL_START, 1, 0)));
        // Malformed binds return results without opening sockets.
        for id in 1..=130 { assert!(broker.command(&header(p::TUNNEL_BIND_UDP, 1, id))); }
        for _ in 0..130 { assert_eq!(receive(&broker)[0], p::TUNNEL_RESULT); }
        drop(broker);
        worker.join().unwrap();
    }

    #[test]
    fn kernel_udp_metrics_keep_cross_interval_gaps_and_count_overflow_wrap() {
        let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos() as u64;
        let mut timing = UdpTiming::default();
        timing.overflow_last = u32::MAX - 1;
        timing.kernel(UdpMetadata { timestamp_ns: Some(now - 3_000_000_000), overflow: Some(2), control_truncated: true, ..Default::default() });
        timing.kernel(UdpMetadata { timestamp_ns: Some(now - 1_000_000_000), ..Default::default() });
        assert_eq!(timing.overflow_delta, 4);
        assert_eq!(timing.kernel_max_gap_us, 2_000_000);
        assert!(timing.kernel_max_age_us >= 3_000_000);
        timing.report = Some(Instant::now() - Duration::from_secs(6));
        let line = timing.report(1, 5000).unwrap();
        assert!(line.contains("socket_drops=4"));
        assert!(line.contains("control_truncated=1"));
        assert_eq!(timing.overflow_delta, 0);
        timing.kernel(UdpMetadata { timestamp_ns: Some(now), overflow: Some(3), ..Default::default() });
        assert_eq!(timing.kernel_max_gap_us, 1_000_000);
        assert_eq!(timing.overflow_delta, 1);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn recvmsg_preserves_udp_boundaries_and_extracts_kernel_timestamp() {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        socket.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
        assert!(enable_udp_metrics(&socket).contains("kernel_timestamp=true"));
        let sender = UdpSocket::bind("127.0.0.1:0").unwrap();
        sender.send_to(b"packet", socket.local_addr().unwrap()).unwrap();
        let mut bytes = [0; 16];
        let (n, peer, metadata) = receive_udp(&socket, &mut bytes).unwrap();
        assert_eq!(&bytes[..n], b"packet");
        assert_eq!(peer, sender.local_addr().unwrap());
        assert!(metadata.timestamp_ns.is_some());
        assert!(!metadata.truncated && !metadata.control_truncated);
        sender.send_to(b"oversized", socket.local_addr().unwrap()).unwrap();
        assert!(receive_udp(&socket, &mut bytes[..2]).unwrap().2.truncated);
    }

    #[test]
    fn published_events_wake_poll_and_survive_notification_drain() {
        let (tx, rx) = mpsc::sync_channel(EVENT_CAP);
        let (reader, writer) = UnixStream::pair().unwrap();
        reader.set_nonblocking(true).unwrap();
        writer.set_nonblocking(true).unwrap();
        let sender = EventSender { tx, wake: writer, diagnostics: mpsc::sync_channel(1).0 };
        let broker = TunnelBroker {
            commands: mpsc::sync_channel(1).0,
            command_wake: UnixStream::pair().unwrap().0,
            events: rx, wake: reader, diagnostics: mpsc::sync_channel(1).1,
            timing: Mutex::new(EventTiming { since: Instant::now(), count: 0, bytes: 0, max_age_us: 0 }),
        };
        let mut fd = libc::pollfd { fd: broker.wake_fd(), events: libc::POLLIN, revents: 0 };
        assert_eq!(unsafe { libc::poll(&mut fd, 1, 0) }, 0);
        // More than the USB loop's 64-event budget must survive clearing notifications.
        for i in 0..130u8 { try_event(&sender, vec![i]).unwrap(); }
        assert_eq!(unsafe { libc::poll(&mut fd, 1, 100) }, 1);
        broker.clear_wake();
        for i in 0..130u8 { assert_eq!(broker.event(), Some(vec![i])); }
        assert!(broker.event().is_none());
        assert_eq!(unsafe { libc::poll(&mut fd, 1, 0) }, 0);
        try_event(&sender, vec![255]).unwrap();
        assert_eq!(unsafe { libc::poll(&mut fd, 1, 100) }, 1);
    }
}

fn start_wifi_metrics(diagnostics: SyncSender<String>) {
    let _ = thread::Builder::new().name("ocbm-wifi-metrics".into()).spawn(move || loop {
        for interface in ["wlan0", "sta0"] {
            if !std::path::Path::new(&format!("/sys/class/net/{interface}")).exists() { continue; }
            let counters = ["rx_packets", "rx_dropped", "rx_errors", "tx_packets", "tx_dropped", "tx_errors"]
                .iter().map(|key| format!("{key}={}", std::fs::read_to_string(format!("/sys/class/net/{interface}/statistics/{key}")).unwrap_or_else(|_| "unavailable".into()).trim())).collect::<Vec<_>>().join(" ");
            if matches!(diagnostics.try_send(format!("[wifi-perf] iface={interface} {counters}")), Err(TrySendError::Disconnected(_))) { return; }
            let child = std::process::Command::new("iw").args(["dev", interface, "station", "dump"])
                .stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::null()).spawn();
            match child {
                Ok(mut child) => {
                    let mut output = child.stdout.take().unwrap();
                    unsafe { let fd = output.as_raw_fd(); let flags = libc::fcntl(fd, libc::F_GETFL); libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK); }
                    let began = Instant::now(); let mut result = Vec::new(); let mut buf = [0u8; 512]; let mut finished = false;
                    while began.elapsed() < Duration::from_secs(2) && result.len() < 8192 {
                        match output.read(&mut buf) {
                            Ok(0) => { finished = true; break; },
                            Ok(n) => result.extend_from_slice(&buf[..n]),
                            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => thread::sleep(Duration::from_millis(10)),
                            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {},
                            Err(_) => break,
                        }
                    }
                    if !finished { let _ = child.kill(); }
                    let text = String::from_utf8_lossy(&result);
                    let fields = text.lines().filter(|l| ["Station ", "signal:", "signal avg:", "tx retries:", "tx failed:", "rx drop misc:", "tx bitrate:", "rx bitrate:", "inactive time:"].iter().any(|key| l.trim().starts_with(key))).map(str::trim).collect::<Vec<_>>().join("; ");
                    let fields = if fields.is_empty() { "unavailable" } else { &fields };
                    for (part, chunk) in fields.as_bytes().chunks(600).enumerate() {
                        let _ = diagnostics.try_send(format!("[wifi-perf] iface={interface} part={part} station={} query_ms={} complete={finished}", String::from_utf8_lossy(chunk), began.elapsed().as_millis()));
                    }
                },
                Err(_) => { let _ = diagnostics.try_send(format!("[wifi-perf] iface={interface} station=unavailable iw_not_found=true")); },
            }
        }
        thread::sleep(Duration::from_secs(10));
    });
}
