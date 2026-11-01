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
use std::time::{Duration, Instant};
use std::os::unix::net::UnixStream;
use std::os::unix::io::{AsRawFd, RawFd};
use std::sync::Mutex;

const COMMAND_CAP: usize = 256;
const EVENT_CAP: usize = 512;
const MAX_OBJECTS: usize = 32;
const MAX_TCP_CREDIT: u32 = 256 * 1024;
const TCP_CHUNK: usize = 16 * 1024;
const UDP_MAX: usize = 60 * 1024;

pub struct TunnelBroker {
    commands: SyncSender<Vec<u8>>,
    events: Receiver<Event>,
    wake: UnixStream,
    timing: Mutex<EventTiming>,
}

struct Event { payload: Vec<u8>, queued: Instant }
struct EventSender { tx: SyncSender<Event>, wake: UnixStream }
struct EventTiming { since: Instant, count: u64, bytes: usize, max_age_us: u128 }

impl TunnelBroker {
    pub fn new() -> Self {
        let (cmd_tx, cmd_rx) = mpsc::sync_channel(COMMAND_CAP);
        let (event_tx, event_rx) = mpsc::sync_channel(EVENT_CAP);
        let (wake_rx, wake_tx) = UnixStream::pair().expect("tunnel wake socketpair");
        wake_rx.set_nonblocking(true).expect("tunnel wake reader nonblocking");
        wake_tx.set_nonblocking(true).expect("tunnel wake writer nonblocking");
        let event_tx = EventSender { tx: event_tx, wake: wake_tx };
        thread::Builder::new()
            .name("ocbm-socket-tunnel".into())
            .spawn(move || run(cmd_rx, event_tx))
            .expect("spawn OCBM socket tunnel worker");
        Self { commands: cmd_tx, events: event_rx, wake: wake_rx, timing: Mutex::new(EventTiming { since: Instant::now(), count: 0, bytes: 0, max_age_us: 0 }) }
    }

    /// USB dispatch path: copy at most one bounded OCBM payload and never wait for network I/O.
    pub fn command(&self, payload: &[u8]) -> bool {
        if payload.len() > p::MAX_PAYLOAD { return false; }
        self.commands.try_send(payload.to_vec()).is_ok()
    }

    /// Drain summaries through the daemon's normal CH_LOG encoder, independently of stderr.
    pub fn take_metrics(&self) -> Option<String> {
        let mut t = self.timing.lock().unwrap();
        if t.since.elapsed() < Duration::from_secs(5) { return None; }
        let line = format!("[tunnel-perf] implementation=tunnel-wake-v2 events={} bytes={} interval_ms={} max_event_age_us={}", t.count, t.bytes, t.since.elapsed().as_millis(), t.max_age_us);
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
        let mut timing = self.timing.lock().unwrap();
        timing.count += 1;
        timing.bytes += event.payload.len();
        timing.max_age_us = timing.max_age_us.max(event.queued.elapsed().as_micros());
        Some(event.payload)
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
    Tcp { socket: TcpStream, credit: u32, rx_credit: u32, pending_credit: u32, pending: Option<Vec<u8>>, writeq: VecDeque<Vec<u8>>, write_offset: usize, write_eof: bool, read_eof: bool, remote_eof: bool },
    Udp { socket: UdpSocket, prefix: Prefix },
}

struct Mdns { socket: UdpSocket, prefix: Prefix }

fn run(commands: Receiver<Vec<u8>>, events: EventSender) {
    let mut epoch = 0u32;
    let mut objects: HashMap<u32, Object> = HashMap::new();
    let mut pending_accepts: Vec<(u32, u32, TcpStream, SocketAddr, u32)> = Vec::new();
    let mut mdns: Option<Mdns> = None;
    let mut next_stream = 0x8000_0000u32;
    let mut rx = vec![0u8; TCP_CHUNK.max(UDP_MAX)];
    loop {
        loop {
            match commands.try_recv() {
                Ok(frame) => {
                    if matches!(frame.first(), Some(&p::TUNNEL_START) | Some(&p::TUNNEL_STOP)) { pending_accepts.clear(); }
                    command(&frame, &mut epoch, &mut objects, &mut next_stream, &mut mdns, &events)
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => return,
            }
        }

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
                objects.insert(stream_id, Object::Tcp { socket: stream, credit: 0, rx_credit: credit.min(MAX_TCP_CREDIT), pending_credit: 0, pending: None, writeq: VecDeque::new(), write_offset: 0, write_eof: false, read_eof: false, remote_eof: false });
            } else { pending_index += 1; }
        }

        let can_accept = objects.len() + pending_accepts.len() < MAX_OBJECTS;
        if let Some(mdns_state) = &mdns {
            loop {
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
            let mut remove = false;
            match objects.get_mut(&id) {
                Some(Object::Listener { socket, prefix, credit }) => {
                    let accepted = if can_accept { match socket.accept() {
                        Ok((stream, peer)) => Some((stream, peer, *prefix, *credit)),
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => None,
                        Err(_) => { remove = true; None }
                    }} else { None };
                    if let Some((stream, peer, prefix, credit)) = accepted {
                        if !prefix.contains(peer.ip()) || objects.len() >= MAX_OBJECTS {
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
                                objects.insert(stream_id, Object::Tcp { socket: stream, credit: 0, rx_credit: credit.min(MAX_TCP_CREDIT), pending_credit: 0, pending: None, writeq: VecDeque::new(), write_offset: 0, write_eof: false, read_eof: false, remote_eof: false });
                            } else {
                                pending_accepts.push((stream_id, id, stream, peer, credit));
                            }
                        }
                    }
                }
                Some(Object::Tcp { socket, credit, rx_credit, pending_credit, pending, writeq, write_offset, write_eof, read_eof, remote_eof }) => {
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
                    if pending.is_none() && *credit > 0 && !*read_eof {
                        let nmax = (*credit as usize).min(TCP_CHUNK);
                        match socket.read(&mut rx[..nmax]) {
                            Ok(0) => {
                                let eof = header(p::TUNNEL_TCP_EOF, epoch, id);
                                if let Err(eof) = try_event(&events, eof) { *pending = Some(eof); }
                                *read_eof = true;
                            }
                            Ok(n) => {
                                let mut event = header(p::TUNNEL_TCP_DATA, epoch, id);
                                event.extend_from_slice(&rx[..n]);
                                match try_event(&events, event) {
                                    Ok(()) => *credit = credit.saturating_sub(n as u32),
                                    Err(event) => *pending = Some(event),
                                }
                            }
                            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
                            Err(_) => remove = true,
                        }
                    }
                    if let Some(front) = writeq.front() {
                        match socket.write(&front[*write_offset..]) {
                            Ok(0) => remove = true,
                            Ok(n) => {
                                *write_offset += n;
                                *pending_credit = pending_credit.saturating_add(n as u32);
                                if *write_offset == front.len() { writeq.pop_front(); *write_offset = 0; }
                            }
                            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
                            Err(_) => remove = true,
                        }
                    } else if *write_eof {
                        let _ = socket.shutdown(std::net::Shutdown::Write);
                        *write_eof = false;
                    }
                    if *read_eof && *remote_eof && writeq.is_empty() && !*write_eof { remove = true; }
                }
                Some(Object::Udp { socket, prefix }) => loop {
                    match socket.recv_from(&mut rx[..UDP_MAX]) {
                        Ok((n, peer)) if prefix.contains(peer.ip()) => {
                            let mut event = header(p::TUNNEL_UDP_DATAGRAM, epoch, id);
                            encode_peer(&mut event, peer);
                            event.extend_from_slice(&rx[..n]);
                            if try_event(&events, event).is_err() { /* atomic datagram drop */ }
                        }
                        Ok(_) => {}
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                        Err(_) => { remove = true; break; }
                    }
                },
                None => {}
            }
            if remove {
                objects.remove(&id);
                let _ = try_event(&events, header(p::TUNNEL_CLOSE, epoch, id));
            }
        }
        thread::sleep(Duration::from_millis(2));
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
        p::TUNNEL_CLOSE => { objects.remove(&id); }
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
                UdpSocket::bind(SocketAddr::new(addr, port)).map(|s| { let _ = s.set_nonblocking(true); Object::Udp { socket: s, prefix } })
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
                    objects.remove(&id);
                }
            }
        }
        // Host closed its write half. Drain queued bytes then half-close the AP socket; keep
        // forwarding the adapter peer's read half until it independently closes.
        p::TUNNEL_TCP_EOF => if let Some(Object::Tcp { write_eof, remote_eof, .. }) = objects.get_mut(&id) { *write_eof = true; *remote_eof = true; },
        p::TUNNEL_UDP_SEND => {
            if let Some(Object::Udp { socket, prefix }) = objects.get(&id) {
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
    fn published_events_wake_poll_and_survive_notification_drain() {
        let (tx, rx) = mpsc::sync_channel(EVENT_CAP);
        let (reader, writer) = UnixStream::pair().unwrap();
        reader.set_nonblocking(true).unwrap();
        writer.set_nonblocking(true).unwrap();
        let sender = EventSender { tx, wake: writer };
        let broker = TunnelBroker {
            commands: mpsc::sync_channel(1).0,
            events: rx, wake: reader,
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
