mod bluetooth;
mod broker;
mod network;
#[cfg(target_os = "linux")]
mod provision;
#[cfg(target_os = "linux")]
mod setup;

use bluetooth::Bluetooth;
use serde_json::{json, Value};
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::Duration;

struct State {
    bluetooth: Mutex<Option<Bluetooth>>,
    broker_slot: Arc<BrokerSlot>,
    phone_iface: String,
    network_config: Option<PathBuf>,
}

#[derive(Default)]
struct BrokerSlot {
    current: Mutex<Option<TcpStream>>,
    released: Condvar,
}

struct BrokerLease(Arc<BrokerSlot>);

impl BrokerSlot {
    fn acquire(self: &Arc<Self>, stream: &TcpStream) -> io::Result<Option<BrokerLease>> {
        let mut current = self.current.lock().unwrap();
        if let Some(previous) = current.as_ref() {
            eprintln!("[pi-link] replacing stale broker connection");
            let _ = previous.shutdown(Shutdown::Both);
            let (next, timeout) = self.released.wait_timeout_while(current, Duration::from_secs(5), |slot| slot.is_some()).unwrap();
            current = next;
            if timeout.timed_out() && current.is_some() { return Ok(None); }
        }
        *current = Some(stream.try_clone()?);
        Ok(Some(BrokerLease(Arc::clone(self))))
    }
}

impl Drop for BrokerLease {
    fn drop(&mut self) {
        *self.0.current.lock().unwrap() = None;
        self.0.released.notify_all();
    }
}

fn boot_ms() -> u64 {
    std::fs::read_to_string("/proc/uptime")
        .ok()
        .and_then(|text| text.split_whitespace().next()?.parse::<f64>().ok())
        .map(|seconds| (seconds * 1000.0) as u64)
        .unwrap_or(0)
}

fn read_line(stream: &mut TcpStream) -> io::Result<String> {
    let mut data = Vec::new();
    loop {
        let mut byte = [0];
        stream.read_exact(&mut byte)?;
        if byte[0] == b'\n' {
            break;
        }
        if data.len() == 2048 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "handshake too long",
            ));
        }
        data.push(byte[0]);
    }
    String::from_utf8(data)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "handshake is not UTF-8"))
}

fn response(stream: &mut TcpStream, value: &str) -> io::Result<()> {
    stream.write_all(value.as_bytes())?;
    stream.write_all(b"\n")
}

fn with_bluetooth<T>(
    state: &State,
    action: impl FnOnce(&Bluetooth) -> Result<T, String>,
) -> Result<T, String> {
    let mut lock = state.bluetooth.lock().unwrap();
    if lock.is_none() {
        *lock = Some(Bluetooth::start()?);
        eprintln!("[pi-boot] bluetooth agent ready uptime_ms={}", boot_ms());
    }
    action(lock.as_ref().unwrap())
}

fn control(state: &State, data: &str) -> Result<Value, String> {
    let request: Value = serde_json::from_str(data).map_err(|e| e.to_string())?;
    match request["action"].as_str().unwrap_or("") {
        "status" => with_bluetooth(state, Bluetooth::status),
        "set_name" => {
            let name = request["name"].as_str().ok_or("missing name")?;
            with_bluetooth(state, |bt| bt.set_name(name))?;
            Ok(json!({"ok":true,"name":name}))
        }
        "remove_pairing" => {
            let address = request["address"].as_str().ok_or("missing address")?;
            with_bluetooth(state, |bt| bt.remove(address))?;
            Ok(json!({"ok":true,"address":address}))
        }
        "disconnect_phone" => {
            let address = request["address"].as_str().ok_or("missing address")?;
            with_bluetooth(state, |bt| bt.disconnect(address))?;
            Ok(json!({"ok":true,"address":address}))
        }
        _ => Err("unknown Pi control action".into()),
    }
}

fn handle(mut stream: TcpStream, peer: SocketAddr, state: Arc<State>) -> io::Result<()> {
    stream.set_nodelay(true)?;
    stream.set_read_timeout(Some(Duration::from_secs(40)))?;
    let line = read_line(&mut stream)?;
    if line == "PI_CARPLAY/2" {
        let Some(_lease) = state.broker_slot.acquire(&stream)? else {
            response(&mut stream, "BUSY")?;
            return Ok(());
        };
        let result = (|| {
            let details = network::hotspot(&state.phone_iface, state.network_config.as_deref())
                .map_err(io::Error::other)?;
            let address: Ipv4Addr = details["ap_ip"]
                .as_str()
                .unwrap()
                .parse()
                .map_err(io::Error::other)?;
            response(&mut stream, &format!("READY {details}"))?;
            stream.set_read_timeout(Some(Duration::from_secs(20)))?;
            stream.set_write_timeout(Some(Duration::from_secs(10)))?;
            eprintln!(
                "[pi-link] broker connected peer={peer} ip={address} channel={} uptime_ms={}",
                details["channel"],
                boot_ms()
            );
            let watchdog = stream.try_clone()?;
            let iface = state.phone_iface.clone();
            let initial_link = network::wifi_link(&iface).ok_or_else(|| io::Error::other("Wi-Fi link lost during broker setup"))?;
            let broker = broker::Broker::new(stream, address)?;
            let watchdog_live = Arc::new(AtomicBool::new(true));
            let watchdog_running = Arc::clone(&watchdog_live);
            thread::spawn(move || {
                while watchdog_running.load(Ordering::SeqCst) && network::wifi_link(&iface) == Some(initial_link.clone()) {
                    thread::sleep(Duration::from_secs(1));
                }
                if watchdog_running.load(Ordering::SeqCst) {
                    eprintln!("[pi-link] Wi-Fi association changed; closing stale broker");
                    let _ = watchdog.shutdown(Shutdown::Both);
                }
            });
            let result = broker.run();
            watchdog_live.store(false, Ordering::SeqCst);
            result
        })();
        eprintln!("[pi-link] broker ended peer={peer}: {result:?}");
        return result;
    }
    if line == "PI_STATUS/2" || line == "PI_CTRL/2" {
        let request = if line == "PI_STATUS/2" {
            "{\"action\":\"status\"}".to_owned()
        } else {
            read_line(&mut stream)?
        };
        match control(&state, &request) {
            Ok(value) => response(&mut stream, &format!("READY {value}"))?,
            Err(error) => response(&mut stream, &format!("ERROR {error}"))?,
        }
        return Ok(());
    }
    if let Some(address) = line.strip_prefix("PI_RFCOMM/2 ") {
        match with_bluetooth(&state, |bt| bt.connect_phone(address)) {
            Ok(phone) => {
                let accessory =
                    with_bluetooth(&state, Bluetooth::address).map_err(io::Error::other)?;
                response(&mut stream, &format!("READY {accessory}"))?;
                stream.set_read_timeout(None)?;
                eprintln!("[pi-bt] RFCOMM connected phone={address}");
                let _ = bluetooth::relay(stream, phone);
                eprintln!("[pi-bt] RFCOMM closed phone={address}");
            }
            Err(error) => {
                eprintln!("[pi-bt] RFCOMM dial failed phone={address}: {error}");
                response(&mut stream, &format!("ERROR RFCOMM dial failed: {}", error.replace('\r', " ").replace('\n', " ")))?;
            }
        }
        return Ok(());
    }
    eprintln!("[pi-link] rejected handshake peer={peer}");
    Ok(())
}

fn main() -> io::Result<()> {
    let mut port = 5101u16;
    let mut phone_iface = "wlan0".to_owned();
    let mut network_config = None;
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--port" => {
                port = args
                    .next()
                    .ok_or_else(|| io::Error::other("missing port"))?
                    .parse()
                    .map_err(io::Error::other)?
            }
            "--phone-interface" => {
                phone_iface = args
                    .next()
                    .ok_or_else(|| io::Error::other("missing interface"))?
            }
            "--network-config-file" => {
                network_config = Some(PathBuf::from(
                    args.next()
                        .ok_or_else(|| io::Error::other("missing path"))?,
                ))
            }
            _ => return Err(io::Error::other(format!("unknown option: {arg}"))),
        }
    }
    let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, port))?;
    eprintln!(
        "[pi-boot] broker listener ready port={port} uptime_ms={}",
        boot_ms()
    );
    let state = Arc::new(State {
        bluetooth: Mutex::new(None),
        broker_slot: Arc::new(BrokerSlot::default()),
        phone_iface: phone_iface.clone(),
        network_config,
    });
    let bt_state = state.clone();
    thread::spawn(move || loop {
        match with_bluetooth(&bt_state, Bluetooth::address) {
            Ok(_) => {
                eprintln!(
                    "[pi-boot] Bluetooth controller ready uptime_ms={}",
                    boot_ms()
                );
                break;
            }
            Err(error) => eprintln!(
                "[pi-boot] Bluetooth not ready uptime_ms={} error={error}",
                boot_ms()
            ),
        }
        thread::sleep(Duration::from_secs(2));
    });
    thread::spawn(move || publish_mdns(phone_iface, port));
    #[cfg(target_os = "linux")]
    thread::spawn(|| loop {
        if let Err(error) = provision::run() {
            eprintln!("[pi-provision] BLE unavailable; retrying: {error}");
        }
        thread::sleep(Duration::from_secs(1));
    });
    #[cfg(target_os = "linux")]
    thread::spawn(|| loop {
        match setup::run() {
            Ok(()) => break,
            Err(error) => eprintln!("[pi-provision] setup page unavailable; retrying: {error}"),
        }
        thread::sleep(Duration::from_secs(5));
    });
    for peer in listener.incoming() {
        let stream = peer?;
        let addr = stream.peer_addr()?;
        let state = state.clone();
        thread::spawn(move || {
            if let Err(error) = handle(stream, addr, state) {
                eprintln!("[pi-link] peer={addr} ended: {error}");
            }
        });
    }
    Ok(())
}

fn publish_mdns(phone_iface: String, port: u16) {
    let id = std::fs::read_to_string("/etc/machine-id").unwrap_or_else(|_| "unknown".into());
    let id = id.trim().chars().take(12).collect::<String>();
    let name = format!("Carlink-Pi-{id}");
    let mut current = None;
    let mut publisher: Option<Child> = None;
    loop {
        let link = network::wifi_link(&phone_iface);
        if link != current
            || publisher
                .as_mut()
                .is_some_and(|child| child.try_wait().ok().flatten().is_some())
        {
            if let Some(mut child) = publisher.take() {
                let _ = child.kill();
                let _ = child.wait();
            }
            current = link;
            if let Some(link) = current.as_ref() {
                let child = Command::new("avahi-publish-service")
                    .args([
                        &name,
                        "_carlink-pi._tcp",
                        &port.to_string(),
                        "proto=2",
                        &format!("id={id}"),
                    ])
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .spawn();
                match child {
                    Ok(process) => {
                        publisher = Some(process);
                        eprintln!("[pi-boot] mDNS announced ip={} profile={} path={} uptime_ms={}", link.address, link.profile, link.connection_path, boot_ms());
                    }
                    Err(error) => eprintln!("[pi-boot] mDNS publisher failed: {error}"),
                }
            }
        }
        thread::sleep(Duration::from_secs(1));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::sync::mpsc;

    #[test]
    fn new_broker_replaces_and_waits_for_old_lease() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let first = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (mut old_peer, _) = listener.accept().unwrap();
        let slot = Arc::new(BrokerSlot::default());
        let old_lease = slot.acquire(&first).unwrap().unwrap();
        let second = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (_new_peer, _) = listener.accept().unwrap();
        let (tx, rx) = mpsc::channel();
        let slot_for_thread = Arc::clone(&slot);
        let replacement = thread::spawn(move || {
            let new_lease = slot_for_thread.acquire(&second).unwrap().unwrap();
            tx.send(()).unwrap();
            drop(new_lease);
        });
        old_peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let mut byte = [0];
        assert_eq!(old_peer.read(&mut byte).unwrap(), 0);
        assert!(rx.recv_timeout(Duration::from_millis(100)).is_err());
        drop(old_lease);
        rx.recv_timeout(Duration::from_secs(2)).unwrap();
        replacement.join().unwrap();
        assert!(slot.current.lock().unwrap().is_none());
    }
}
