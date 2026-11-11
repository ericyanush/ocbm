//! Pi-side socket broker. Frame payloads match OcbmSocketTunnel and the original Python PoC.
use socket2::{Domain, Protocol, Socket, Type};
use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const LISTEN_TCP: u8 = 1;
const BIND_UDP: u8 = 2;
const CLOSE: u8 = 3;
const ACCEPT: u8 = 4;
const TCP_DATA: u8 = 5;
const TCP_CREDIT: u8 = 6;
const TCP_EOF: u8 = 7;
const UDP_DATAGRAM: u8 = 8;
const UDP_SEND: u8 = 9;
const RESULT: u8 = 10;
const START: u8 = 11;
const STOP: u8 = 12;
const MDNS_START: u8 = 13;
const MDNS_PACKET: u8 = 14;
const MDNS_SEND: u8 = 15;
const PING: u8 = 16;
const WINDOW: usize = 256 * 1024;
const CHUNK: usize = 16 * 1024;
const MAX_FRAME: usize = 65535;

fn tuple(addr: SocketAddr) -> io::Result<Vec<u8>> {
    match addr {
        SocketAddr::V4(v4) => {
            let mut out = vec![4];
            out.extend_from_slice(&v4.ip().octets());
            out.extend_from_slice(&v4.port().to_be_bytes());
            Ok(out)
        }
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "IPv6 peer not supported",
        )),
    }
}

fn read_tuple(body: &[u8]) -> io::Result<(SocketAddr, &[u8])> {
    if body.len() < 7 || body[0] != 4 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid IPv4 tuple",
        ));
    }
    Ok((
        SocketAddr::from((
            Ipv4Addr::new(body[1], body[2], body[3], body[4]),
            u16::from_be_bytes([body[5], body[6]]),
        )),
        &body[7..],
    ))
}

struct Sender {
    stream: Mutex<TcpStream>,
    epoch: AtomicU32,
    live: AtomicBool,
}

impl Sender {
    fn send(&self, op: u8, id: u32, body: &[u8]) -> io::Result<()> {
        if !self.live.load(Ordering::SeqCst) {
            return Ok(());
        }
        let length = 9 + body.len();
        if length > MAX_FRAME {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "broker frame too large",
            ));
        }
        let mut data = Vec::with_capacity(4 + length);
        data.extend_from_slice(&(length as u32).to_be_bytes());
        data.push(op);
        data.extend_from_slice(&self.epoch.load(Ordering::SeqCst).to_le_bytes());
        data.extend_from_slice(&id.to_le_bytes());
        data.extend_from_slice(body);
        self.stream.lock().unwrap().write_all(&data)
    }

    fn result(&self, id: u32, status: u8, port: u16) {
        let mut body = vec![status];
        body.extend_from_slice(&port.to_le_bytes());
        let _ = self.send(RESULT, id, &body);
    }
}

struct Stream {
    socket: TcpStream,
    live: AtomicBool,
    read_done: AtomicBool,
    write_done: AtomicBool,
    credit: Mutex<usize>,
    wake: Condvar,
    downlink: mpsc::SyncSender<Option<Vec<u8>>>,
}

impl Stream {
    fn close(&self) {
        self.live.store(false, Ordering::SeqCst);
        self.wake.notify_all();
        let _ = self.socket.shutdown(Shutdown::Both);
    }

    fn grant(&self, amount: usize) {
        let mut credit = self.credit.lock().unwrap();
        *credit = credit.saturating_add(amount).min(WINDOW);
        self.wake.notify_all();
    }
}

struct Registration {
    live: Arc<AtomicBool>,
    tcp: bool,
    socket: Socket,
}

impl Registration {
    fn close(&self) {
        self.live.store(false, Ordering::SeqCst);
    }
}

pub struct Broker {
    reader: TcpStream,
    sender: Arc<Sender>,
    ip: Ipv4Addr,
    streams: Arc<Mutex<HashMap<u32, Arc<Stream>>>>,
    regs: HashMap<u32, Registration>,
    mdns: Option<UdpSocket>,
    mdns_live: Option<Arc<AtomicBool>>,
    next_stream: Arc<AtomicU32>,
}

impl Broker {
    pub fn new(stream: TcpStream, ip: Ipv4Addr) -> io::Result<Self> {
        stream.set_nodelay(true)?;
        let sender = Arc::new(Sender {
            stream: Mutex::new(stream.try_clone()?),
            epoch: AtomicU32::new(0),
            live: AtomicBool::new(true),
        });
        Ok(Self {
            reader: stream,
            sender,
            ip,
            streams: Arc::new(Mutex::new(HashMap::new())),
            regs: HashMap::new(),
            mdns: None,
            mdns_live: None,
            next_stream: Arc::new(AtomicU32::new(0x80000000)),
        })
    }

    pub fn run(mut self) -> io::Result<()> {
        let started = Instant::now();
        let mut frames = 0u64;
        loop {
            let mut size = [0u8; 4];
            self.reader.read_exact(&mut size)?;
            let size = u32::from_be_bytes(size) as usize;
            if !(9..=MAX_FRAME).contains(&size) {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "invalid broker frame length",
                ));
            }
            let mut frame = vec![0; size];
            self.reader.read_exact(&mut frame)?;
            frames += 1;
            let op = frame[0];
            let epoch = u32::from_le_bytes(frame[1..5].try_into().unwrap());
            let id = u32::from_le_bytes(frame[5..9].try_into().unwrap());
            let body = &frame[9..];
            if op == START {
                self.close_sockets();
                self.sender.epoch.store(epoch, Ordering::SeqCst);
                eprintln!("[pi-broker] started epoch={epoch:08x}");
                continue;
            }
            if epoch != self.sender.epoch.load(Ordering::SeqCst) {
                continue;
            }
            if op == STOP {
                break;
            }
            self.handle(op, id, body);
            if frames % 500 == 0 {
                eprintln!(
                    "[pi-broker] uptime_ms={} frames={} streams={} listeners={}",
                    started.elapsed().as_millis(),
                    frames,
                    self.streams.lock().unwrap().len(),
                    self.regs.len()
                );
            }
        }
        Ok(())
    }

    fn handle(&mut self, op: u8, id: u32, body: &[u8]) {
        match op {
            PING => {
                if let Err(error) = self.sender.send(PING, 0, &[]) {
                    eprintln!("[pi-broker] heartbeat response failed: {error}");
                    let _ = self.reader.shutdown(Shutdown::Both);
                }
            }
            LISTEN_TCP | BIND_UDP => self.register(op, id, body),
            MDNS_START => self.start_mdns(id, body),
            MDNS_SEND => {
                if body.len() <= 9000 {
                    if let Some(sock) = &self.mdns {
                        let _ = sock.send_to(body, (Ipv4Addr::new(224, 0, 0, 251), 5353));
                    }
                }
            }
            TCP_CREDIT if body.len() == 4 => {
                if let Some(s) = self.streams.lock().unwrap().get(&id) {
                    s.grant(u32::from_le_bytes(body.try_into().unwrap()) as usize);
                }
            }
            TCP_DATA if body.len() <= CHUNK => {
                let stream = self.streams.lock().unwrap().get(&id).cloned();
                if let Some(stream) = stream {
                    if stream.downlink.try_send(Some(body.to_vec())).is_err() {
                        eprintln!("[pi-broker] TCP downlink queue full stream={id}");
                        let _ = self.sender.send(CLOSE, id, &[3]);
                        stream.close();
                    }
                }
            }
            TCP_EOF => {
                if let Some(s) = self.streams.lock().unwrap().get(&id) {
                    let _ = s.downlink.try_send(None);
                }
            }
            UDP_SEND => {
                if let Ok((peer, packet)) = read_tuple(body) {
                    if let Some(reg) = self.regs.get(&id).filter(|r| !r.tcp) {
                        let sock: UdpSocket = reg.socket.try_clone().unwrap().into();
                        let _ = sock.send_to(packet, peer);
                    }
                }
            }
            CLOSE => {
                if let Some(stream) = self.streams.lock().unwrap().remove(&id) {
                    stream.close();
                }
                if let Some(reg) = self.regs.remove(&id) {
                    reg.close();
                }
            }
            _ => eprintln!("[pi-broker] invalid opcode={op} id={id}"),
        }
    }

    fn register(&mut self, op: u8, id: u32, body: &[u8]) {
        if body.len() < 12 || body[0] != 4 || body[1..5] != self.ip.octets() {
            self.sender.result(id, 1, 0);
            return;
        }
        let port = u16::from_le_bytes([body[6], body[7]]);
        if port == 0 || self.regs.contains_key(&id) {
            self.sender.result(id, 1, 0);
            return;
        }
        let addr = SocketAddr::from((self.ip, port));
        let ty = if op == LISTEN_TCP {
            Type::STREAM
        } else {
            Type::DGRAM
        };
        let protocol = if op == LISTEN_TCP {
            Protocol::TCP
        } else {
            Protocol::UDP
        };
        let result = (|| -> io::Result<Registration> {
            let socket = Socket::new(Domain::IPV4, ty, Some(protocol))?;
            socket.set_reuse_address(true)?;
            if op == BIND_UDP {
                // Leave room for compressed-video bursts while the USB/AAOS backhaul stalls.
                if let Err(error) = socket.set_recv_buffer_size(4 * 1024 * 1024) {
                    eprintln!("[pi-broker] UDP receive buffer request rejected: {error}");
                }
            }
            socket.bind(&addr.into())?;
            if op == LISTEN_TCP {
                socket.listen(16)?;
            }
            let live = Arc::new(AtomicBool::new(true));
            if op == LISTEN_TCP {
                let listener: TcpListener = socket.try_clone()?.into();
                listener.set_nonblocking(true)?;
                let sender = self.sender.clone();
                let streams = self.streams.clone();
                let next = self.next_stream.clone();
                let running = live.clone();
                thread::spawn(move || {
                    while running.load(Ordering::SeqCst) && sender.live.load(Ordering::SeqCst) {
                        match listener.accept() {
                            Ok((phone, peer)) => {
                                let sid = next.fetch_add(1, Ordering::SeqCst).wrapping_add(1);
                                if let Err(error) = start_stream(
                                    sid,
                                    id,
                                    phone,
                                    peer,
                                    sender.clone(),
                                    streams.clone(),
                                ) {
                                    eprintln!("[pi-broker] stream start failed: {error}");
                                }
                            }
                            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                                thread::sleep(Duration::from_millis(5))
                            }
                            Err(_) => break,
                        }
                    }
                });
            } else {
                let udp: UdpSocket = socket.try_clone()?.into();
                udp.set_read_timeout(Some(Duration::from_millis(200)))?;
                let sender = self.sender.clone();
                let running = live.clone();
                thread::spawn(move || {
                    let mut packet = [0u8; 60000];
                    let mut count = 0u64;
                    let mut last = Instant::now();
                    let mut max_gap = 0u128;
                    while running.load(Ordering::SeqCst) && sender.live.load(Ordering::SeqCst) {
                        match udp.recv_from(&mut packet) {
                            Ok((n, peer)) => {
                                let gap = last.elapsed().as_micros();
                                last = Instant::now();
                                max_gap = max_gap.max(gap);
                                count += 1;
                                if let Ok(mut body) = tuple(peer) {
                                    body.extend_from_slice(&packet[..n]);
                                    if sender.send(UDP_DATAGRAM, id, &body).is_err() {
                                        break;
                                    }
                                }
                                if count % 500 == 0 {
                                    eprintln!("[pi-broker] UDP id={id} packets={count} max_gap_us={max_gap}");
                                    max_gap = 0;
                                }
                            }
                            Err(e)
                                if e.kind() == io::ErrorKind::WouldBlock
                                    || e.kind() == io::ErrorKind::TimedOut => {}
                            Err(_) => break,
                        }
                    }
                });
            }
            Ok(Registration {
                live,
                tcp: op == LISTEN_TCP,
                socket,
            })
        })();
        match result {
            Ok(reg) => {
                self.regs.insert(id, reg);
                self.sender.result(id, 0, port);
                eprintln!(
                    "[pi-broker] bound {} {addr} id={id}",
                    if op == LISTEN_TCP { "TCP" } else { "UDP" }
                );
            }
            Err(error) => {
                self.sender.result(id, 1, 0);
                eprintln!("[pi-broker] bind failed {addr}: {error}");
            }
        }
    }

    fn start_mdns(&mut self, id: u32, body: &[u8]) {
        if body.len() != 5 || body[0] != 4 || body[1..] != self.ip.octets() {
            self.sender.result(id, 1, 0);
            return;
        }
        let result = (|| -> io::Result<UdpSocket> {
            let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
            socket.set_reuse_address(true)?;
            socket.set_multicast_if_v4(&self.ip)?;
            socket.bind(&SocketAddr::from((Ipv4Addr::UNSPECIFIED, 5353)).into())?;
            let udp: UdpSocket = socket.into();
            udp.join_multicast_v4(&Ipv4Addr::new(224, 0, 0, 251), &self.ip)?;
            udp.set_read_timeout(Some(Duration::from_millis(200)))?;
            Ok(udp)
        })();
        match result {
            Ok(sock) => {
                let reader = sock.try_clone().unwrap();
                let sender = self.sender.clone();
                let live = Arc::new(AtomicBool::new(true));
                let running = live.clone();
                let own = self.ip;
                thread::spawn(move || {
                    let mut data = [0u8; 9000];
                    while running.load(Ordering::SeqCst) && sender.live.load(Ordering::SeqCst) {
                        match reader.recv_from(&mut data) {
                            Ok((n, peer)) if peer.ip() != own => {
                                if let Ok(mut body) = tuple(peer) {
                                    body.extend_from_slice(&data[..n]);
                                    let _ = sender.send(MDNS_PACKET, 0, &body);
                                }
                            }
                            Ok(_) => {}
                            Err(e)
                                if e.kind() == io::ErrorKind::WouldBlock
                                    || e.kind() == io::ErrorKind::TimedOut => {}
                            Err(_) => break,
                        }
                    }
                });
                self.mdns_live = Some(live);
                self.mdns = Some(sock);
                self.sender.result(id, 0, 5353);
                eprintln!("[pi-broker] mDNS joined on {}", self.ip);
            }
            Err(error) => {
                self.sender.result(id, 1, 0);
                eprintln!("[pi-broker] mDNS failed: {error}");
            }
        }
    }

    fn close_sockets(&mut self) {
        for reg in self.regs.values() {
            reg.close();
        }
        self.regs.clear();
        for stream in self.streams.lock().unwrap().values() {
            stream.close();
        }
        self.streams.lock().unwrap().clear();
        if let Some(live) = self.mdns_live.take() {
            live.store(false, Ordering::SeqCst);
        }
        self.mdns.take();
    }
}

impl Drop for Broker {
    fn drop(&mut self) {
        self.sender.live.store(false, Ordering::SeqCst);
        self.close_sockets();
    }
}

fn start_stream(
    id: u32,
    listener: u32,
    phone: TcpStream,
    peer: SocketAddr,
    sender: Arc<Sender>,
    streams: Arc<Mutex<HashMap<u32, Arc<Stream>>>>,
) -> io::Result<()> {
    phone.set_nodelay(true)?;
    let (tx, rx) = mpsc::sync_channel::<Option<Vec<u8>>>(32);
    let stream = Arc::new(Stream {
        socket: phone.try_clone()?,
        live: AtomicBool::new(true),
        read_done: AtomicBool::new(false),
        write_done: AtomicBool::new(false),
        credit: Mutex::new(0),
        wake: Condvar::new(),
        downlink: tx,
    });
    streams.lock().unwrap().insert(id, stream.clone());
    let mut body = Vec::new();
    body.extend_from_slice(&listener.to_le_bytes());
    body.extend_from_slice(&(WINDOW as u32).to_le_bytes());
    body.extend(tuple(peer)?);
    sender.send(ACCEPT, id, &body)?;
    eprintln!("[pi-broker] TCP accept peer={peer} stream={id}");
    let mut reader = phone.try_clone()?;
    let send_rx = sender.clone();
    let read_state = stream.clone();
    let read_map = streams.clone();
    thread::spawn(move || {
        let mut data = [0u8; CHUNK];
        let mut chunks = 0u64;
        let mut max_gap = Duration::ZERO;
        let mut last = Instant::now();
        while read_state.live.load(Ordering::SeqCst) {
            let mut credit = read_state.credit.lock().unwrap();
            while *credit == 0 && read_state.live.load(Ordering::SeqCst) {
                credit = read_state.wake.wait(credit).unwrap();
            }
            if !read_state.live.load(Ordering::SeqCst) {
                break;
            }
            let reserved = (*credit).min(CHUNK);
            *credit -= reserved;
            drop(credit);
            match reader.read(&mut data[..reserved]) {
                Ok(0) => {
                    let _ = send_rx.send(TCP_EOF, id, &[]);
                    break;
                }
                Ok(n) => {
                    let gap = last.elapsed();
                    last = Instant::now();
                    max_gap = max_gap.max(gap);
                    chunks += 1;
                    if chunks % 500 == 0 {
                        eprintln!(
                            "[pi-broker] TCP uplink stream={id} chunks={chunks} max_gap_ms={}",
                            max_gap.as_millis()
                        );
                        max_gap = Duration::ZERO;
                    }
                    if n < reserved {
                        read_state.grant(reserved - n);
                    }
                    if send_rx.send(TCP_DATA, id, &data[..n]).is_err() {
                        break;
                    }
                }
                Err(_) => {
                    let _ = send_rx.send(CLOSE, id, &[1]);
                    read_state.close();
                    break;
                }
            }
        }
        read_state.read_done.store(true, Ordering::SeqCst);
        if !read_state.live.load(Ordering::SeqCst) || read_state.write_done.load(Ordering::SeqCst) {
            read_state.close();
            read_map.lock().unwrap().remove(&id);
        }
    });
    let write_state = stream;
    let write_map = streams;
    thread::spawn(move || {
        let mut writer = phone;
        let mut chunks = 0u64;
        let mut max_write = Duration::ZERO;
        while let Ok(item) = rx.recv() {
            match item {
                Some(data) => {
                    let started = Instant::now();
                    if writer.write_all(&data).is_err() {
                        let _ = sender.send(CLOSE, id, &[2]);
                        write_state.close();
                        break;
                    }
                    max_write = max_write.max(started.elapsed());
                    chunks += 1;
                    if chunks % 500 == 0 {
                        eprintln!(
                            "[pi-broker] TCP downlink stream={id} chunks={chunks} max_write_ms={}",
                            max_write.as_millis()
                        );
                        max_write = Duration::ZERO;
                    }
                    if sender
                        .send(TCP_CREDIT, id, &(data.len() as u32).to_le_bytes())
                        .is_err()
                    {
                        break;
                    }
                }
                None => {
                    let _ = writer.shutdown(Shutdown::Write);
                    break;
                }
            }
        }
        write_state.write_done.store(true, Ordering::SeqCst);
        if !write_state.live.load(Ordering::SeqCst) || write_state.read_done.load(Ordering::SeqCst)
        {
            write_state.close();
            write_map.lock().unwrap().remove(&id);
        }
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ipv4_tuple_roundtrip() {
        let peer: SocketAddr = "192.0.2.10:54321".parse().unwrap();
        let bytes = tuple(peer).unwrap();
        assert_eq!(read_tuple(&bytes).unwrap().0, peer);
    }

    #[test]
    fn tcp_listener_forwards_phone_bytes_using_android_wire_frames() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut android = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        android
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        let (pi, _) = listener.accept().unwrap();
        let worker = thread::spawn(move || Broker::new(pi, Ipv4Addr::LOCALHOST).unwrap().run());
        fn write_frame(stream: &mut TcpStream, op: u8, epoch: u32, id: u32, body: &[u8]) {
            let mut frame = vec![op];
            frame.extend_from_slice(&epoch.to_le_bytes());
            frame.extend_from_slice(&id.to_le_bytes());
            frame.extend_from_slice(body);
            stream
                .write_all(&(frame.len() as u32).to_be_bytes())
                .unwrap();
            stream.write_all(&frame).unwrap();
        }
        fn read_frame(stream: &mut TcpStream) -> Vec<u8> {
            let mut len = [0; 4];
            stream.read_exact(&mut len).unwrap();
            let mut frame = vec![0; u32::from_be_bytes(len) as usize];
            stream.read_exact(&mut frame).unwrap();
            frame
        }
        let probe = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = probe.local_addr().unwrap().port();
        drop(probe);
        let epoch = 0x1234abcd;
        write_frame(&mut android, START, epoch, 0, &[]);
        let mut bind = vec![4, 127, 0, 0, 1, 0];
        bind.extend_from_slice(&port.to_le_bytes());
        bind.extend_from_slice(&[0, 0, 0, 0]);
        write_frame(&mut android, LISTEN_TCP, epoch, 9, &bind);
        let result = read_frame(&mut android);
        assert_eq!(result[0], RESULT);
        assert_eq!(result[9], 0);
        let mut phone = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        let accepted = read_frame(&mut android);
        assert_eq!(accepted[0], ACCEPT);
        let stream_id = u32::from_le_bytes(accepted[5..9].try_into().unwrap());
        write_frame(
            &mut android,
            TCP_CREDIT,
            epoch,
            stream_id,
            &16u32.to_le_bytes(),
        );
        phone.write_all(b"hello").unwrap();
        let data = read_frame(&mut android);
        assert_eq!(data[0], TCP_DATA);
        assert_eq!(&data[9..], b"hello");
        write_frame(&mut android, STOP, epoch, 0, &[]);
        worker.join().unwrap().unwrap();
    }
}
