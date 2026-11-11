use serde_json::{json, Value};
use std::fs::File;
use std::process::Command;

fn run(args: &[&str]) -> Result<String, String> {
    let output = Command::new("bluetoothctl")
        .args(args)
        .output()
        .map_err(|e| e.to_string())?;
    if !output.status.success() {
        return Err(format!(
            "bluetoothctl failed: {}",
            String::from_utf8_lossy(&output.stderr)
        ));
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

pub struct Bluetooth;

impl Bluetooth {
    pub fn start() -> Result<Self, String> {
        let _ = Command::new("rfkill")
            .args(["unblock", "bluetooth"])
            .output();
        if !run(&["show"])?
            .lines()
            .any(|line| line.trim().starts_with("Controller "))
        {
            return Err("BlueZ controller unavailable".into());
        }
        run(&["power", "on"])?;
        run(&["discoverable-timeout", "0"])?;
        run(&["pairable", "on"])?;
        run(&["discoverable", "on"])?;
        Ok(Self)
    }

    pub fn address(&self) -> Result<String, String> {
        let show = run(&["show"])?;
        show.lines()
            .find_map(|line| {
                line.trim()
                    .strip_prefix("Controller ")
                    .and_then(|rest| rest.split_whitespace().next())
                    .map(str::to_owned)
            })
            .ok_or("BlueZ controller unavailable".into())
    }

    pub fn paired(&self) -> Result<Vec<Value>, String> {
        let output = run(&["devices", "Paired"])?;
        let mut devices = Vec::new();
        for line in output.lines() {
            if let Some(rest) = line.strip_prefix("Device ") {
                if let Some((address, name)) = rest.split_once(' ') {
                    if parse_mac(address).is_some() {
                        let info = run(&["info", address]).unwrap_or_default();
                        let connected = info.lines().any(|line| line.trim() == "Connected: yes");
                        devices.push(json!({"address":address.to_uppercase(),"name":name,"connected":connected}));
                    }
                }
            }
        }
        Ok(devices)
    }

    pub fn status(&self) -> Result<Value, String> {
        let show = run(&["show"])?;
        let name = show
            .lines()
            .find_map(|line| line.trim().strip_prefix("Alias: "))
            .unwrap_or("Carlink-Pi");
        Ok(json!({"bt_mac":self.address()?, "name":name, "paired":self.paired()?}))
    }

    pub fn set_name(&self, name: &str) -> Result<(), String> {
        if name.is_empty() || name.len() > 48 || name.chars().any(char::is_control) {
            return Err("Bluetooth name must be 1–48 printable bytes".into());
        }
        run(&["system-alias", name])?;
        Ok(())
    }

    pub fn remove(&self, address: &str) -> Result<(), String> {
        if parse_mac(address).is_none() {
            return Err("invalid phone address".into());
        }
        run(&["remove", address])?;
        Ok(())
    }

    pub fn disconnect(&self, address: &str) -> Result<(), String> {
        if parse_mac(address).is_none() {
            return Err("invalid phone address".into());
        }
        let info = run(&["info", address])?;
        if info.lines().any(|line| line.trim() == "Connected: yes") {
            run(&["disconnect", address])?;
        }
        Ok(())
    }

    pub fn connect_phone(&self, address: &str) -> Result<File, String> {
        let mac = parse_mac(address).ok_or("invalid phone address")?;
        if !self
            .paired()?
            .iter()
            .any(|entry| entry["address"].as_str() == Some(&address.to_uppercase()))
        {
            return Err("phone is not paired".into());
        }
        let output = Command::new("sdptool")
            .args(["browse", address])
            .output()
            .map_err(|e| e.to_string())?;
        if !output.status.success() {
            let info = run(&["info", address]).unwrap_or_default();
            let connected = info.lines().find_map(|line| line.trim().strip_prefix("Connected: "))
                .unwrap_or("unknown");
            let detail = String::from_utf8_lossy(&output.stderr);
            let detail = detail.trim().replace(['\r', '\n'], " ");
            return Err(format!(
                "phone SDP browse failed exit={} connected={} stderr={}",
                output.status, connected, detail.chars().take(240).collect::<String>()
            ));
        }
        let channels = iap2_channels(&String::from_utf8_lossy(&output.stdout));
        if channels.is_empty() {
            return Err("phone has no iAP2 RFCOMM service".into());
        }
        let mut last = String::new();
        for channel in channels {
            match bt_common::rfcomm::connect_to(mac, channel, 15) {
                Ok(file) => return Ok(file),
                Err(error) => last = error.to_string(),
            }
        }
        Err(format!("RFCOMM dial failed: {last}"))
    }
}

fn iap2_channels(sdp: &str) -> Vec<u8> {
    let text = sdp.to_ascii_lowercase();
    let mut channels = Vec::new();
    for record in text.split("service rechandle:") {
        if !record.contains("02030302-1d19-415f-86f2-22a2106a0a77")
            && !record.contains("00000000-deca-fade-deca-deafdecacafe")
        {
            continue;
        }
        if let Some(at) = record.find("\"rfcomm\" (0x0003)") {
            if let Some(channel) = record[at..].lines().find_map(|line| {
                line.trim()
                    .strip_prefix("channel: ")
                    .and_then(|n| n.trim().parse::<u8>().ok())
            }) {
                if (1..=30).contains(&channel) && !channels.contains(&channel) {
                    channels.push(channel);
                }
            }
        }
    }
    channels
}

pub fn parse_mac(text: &str) -> Option<[u8; 6]> {
    let bytes = text
        .split(':')
        .map(|piece| u8::from_str_radix(piece, 16).ok())
        .collect::<Option<Vec<_>>>()?;
    let mut result: [u8; 6] = bytes.try_into().ok()?;
    result.reverse(); // BlueZ sockaddr_rc uses little-endian bdaddr order.
    Some(result)
}

pub fn relay(mut aaos: std::net::TcpStream, mut phone: File) -> Result<(), String> {
    let mut aaos_rx = aaos.try_clone().map_err(|e| e.to_string())?;
    let mut phone_tx = phone.try_clone().map_err(|e| e.to_string())?;
    let copy = std::thread::spawn(move || {
        let _ = std::io::copy(&mut aaos_rx, &mut phone_tx);
        // Closing only the AAOS socket leaves the other relay thread blocked in an
        // RFCOMM read. That holds the phone's RFCOMM channel open after a switch and
        // makes the next dial fail with EBUSY until Bluetooth is disconnected.
        let _ = socket2::SockRef::from(&phone_tx).shutdown(std::net::Shutdown::Both);
        let _ = aaos_rx.shutdown(std::net::Shutdown::Both);
    });
    let _ = std::io::copy(&mut phone, &mut aaos);
    let _ = socket2::SockRef::from(&phone).shutdown(std::net::Shutdown::Both);
    let _ = aaos.shutdown(std::net::Shutdown::Both);
    let _ = copy.join();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::iap2_channels;
    use std::io::Read;
    use std::net::{Ipv4Addr, TcpListener, TcpStream};
    use std::os::fd::{FromRawFd, IntoRawFd};
    use std::sync::mpsc;
    use std::time::Duration;

    #[test]
    fn finds_only_iap2_channel_in_sdp_records() {
        let sdp = "Service RecHandle: 0x10001\nService Name: Audio\n  \"RFCOMM\" (0x0003)\n    Channel: 2\nService RecHandle: 0x10002\nService Class ID List:\n  UUID 128: 02030302-1d19-415f-86f2-22a2106a0a77\n  \"RFCOMM\" (0x0003)\n    Channel: 11\n";
        assert_eq!(iap2_channels(sdp), vec![11]);
    }

    #[test]
    fn closing_aaos_link_releases_phone_socket_without_waiting_for_phone() {
        let aaos_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let aaos_client = TcpStream::connect(aaos_listener.local_addr().unwrap()).unwrap();
        let (aaos_proxy, _) = aaos_listener.accept().unwrap();
        let phone_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let phone_proxy = TcpStream::connect(phone_listener.local_addr().unwrap()).unwrap();
        let (mut phone_peer, _) = phone_listener.accept().unwrap();
        let phone_file = unsafe { std::fs::File::from_raw_fd(phone_proxy.into_raw_fd()) };
        let (done, finished) = mpsc::channel();
        std::thread::spawn(move || {
            let result = super::relay(aaos_proxy, phone_file);
            done.send(result).unwrap();
        });
        drop(aaos_client);
        phone_peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let mut byte = [0];
        assert_eq!(phone_peer.read(&mut byte).unwrap(), 0);
        finished.recv_timeout(Duration::from_secs(2)).unwrap().unwrap();
    }
}
