//! First-run phone setup page on a temporary Pi access point.
use crate::{network, provision};
use serde_json::json;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::process::Command;
use std::time::Duration;

fn profile_exists() -> bool {
    Command::new("nmcli")
        .args(["connection", "show", "carlink-vehicle-wifi"])
        .output()
        .is_ok_and(|result| result.status.success())
}

fn decode(text: &str) -> Option<String> {
    let mut bytes = Vec::new();
    let mut input = text.as_bytes().iter().copied();
    while let Some(byte) = input.next() {
        if byte == b'%' {
            let a = (input.next()? as char).to_digit(16)?;
            let b = (input.next()? as char).to_digit(16)?;
            bytes.push((a * 16 + b) as u8);
        } else if byte == b'+' {
            bytes.push(b' ');
        } else {
            bytes.push(byte);
        }
    }
    String::from_utf8(bytes).ok()
}

fn form_value<'a>(body: &'a str, name: &str) -> Option<String> {
    body.split('&').find_map(|part| {
        let (key, value) = part.split_once('=')?;
        if key == name {
            decode(value)
        } else {
            None
        }
    })
}

fn reply(stream: &mut TcpStream, status: &str, body: &str) {
    let _ = write!(stream, "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n{body}", body.len());
}

fn handle(mut stream: TcpStream) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let mut data = vec![0; 4096];
    let mut size = 0;
    while size < data.len() {
        match stream.read(&mut data[size..]) {
            Ok(0) | Err(_) => return,
            Ok(n) => size += n,
        }
        if let Some(header_end) = data[..size]
            .windows(4)
            .position(|bytes| bytes == b"\r\n\r\n")
        {
            let header_end = header_end + 4;
            let header = String::from_utf8_lossy(&data[..header_end]);
            let content_len = header
                .lines()
                .find_map(|line| {
                    line.to_ascii_lowercase()
                        .strip_prefix("content-length:")
                        .and_then(|value| value.trim().parse::<usize>().ok())
                })
                .unwrap_or(0);
            if content_len > 2048 || header_end + content_len > data.len() {
                reply(&mut stream, "413 Payload Too Large", "Request too large");
                return;
            }
            if size < header_end + content_len {
                continue;
            }
            if header.starts_with("GET / ") {
                reply(&mut stream, "200 OK", "<!doctype html><meta name=viewport content='width=device-width, initial-scale=1'><title>Carlink Pi setup</title><h1>Vehicle Wi-Fi</h1><form method=post action=/provision><label>Hotspot name <input name=ssid required maxlength=32></label><br><label>Hotspot password <input name=passphrase type=password required minlength=8 maxlength=63></label><br><button>Save and connect</button></form>");
            } else if header.starts_with("POST /provision ") {
                let body = std::str::from_utf8(&data[header_end..header_end + content_len]).ok();
                let creds = body.and_then(|body| {
                    Some((form_value(body, "ssid")?, form_value(body, "passphrase")?))
                });
                if let Some((ssid, passphrase)) = creds {
                    let data = json!({"ssid":ssid,"passphrase":passphrase}).to_string();
                    if provision::configure(data.as_bytes()).is_ok() {
                        reply(
                            &mut stream,
                            "200 OK",
                            "Vehicle Wi-Fi saved. The Pi will join the hotspot when available.",
                        );
                    } else {
                        reply(
                            &mut stream,
                            "400 Bad Request",
                            "Could not save Wi-Fi profile. Check the name and password.",
                        );
                    }
                } else {
                    reply(
                        &mut stream,
                        "400 Bad Request",
                        "Missing hotspot name or password",
                    );
                }
            } else {
                reply(&mut stream, "404 Not Found", "Not found");
            }
            return;
        }
    }
}

pub fn run() -> Result<(), String> {
    // A missing NetworkManager D-Bus reply during boot is not an absent profile.
    // Wait for the manager before deciding to replace Wi-Fi with the setup AP.
    let manager = Command::new("nmcli")
        .args(["-g", "RUNNING", "general"])
        .output()
        .map_err(|e| e.to_string())?;
    if !manager.status.success()
        || String::from_utf8_lossy(&manager.stdout).trim() != "running"
    {
        return Err("NetworkManager is not ready".into());
    }
    if profile_exists() {
        return Ok(());
    }
    let _ = Command::new("nmcli").args(["radio", "wifi", "on"]).output();
    let setup_profile = "carlink-setup-ap";
    // Recreate this temporary profile instead of modifying an older secured
    // profile: NetworkManager can retain hidden WEP/WPA keys after changing
    // key-mgmt to `none`, then still prompt for a secret when activating it.
    if Command::new("nmcli")
        .args(["connection", "show", setup_profile])
        .output()
        .is_ok_and(|result| result.status.success())
    {
        let _ = Command::new("nmcli")
            .args(["connection", "delete", setup_profile])
            .output();
    }
    let created = Command::new("nmcli")
        .args([
            "connection",
            "add",
            "type",
            "wifi",
            "ifname",
            "wlan0",
            "con-name",
            setup_profile,
            "ssid",
            "Carlink-Pi-Setup",
            "802-11-wireless.mode",
            "ap",
            "802-11-wireless.band",
            "bg",
            "802-11-wireless.channel",
            "6",
            "ipv4.method",
            "shared",
            "connection.autoconnect",
            "no",
        ])
        .output()
        .map_err(|e| e.to_string())?;
    if !created.status.success() {
        return Err("NetworkManager could not create open setup AP profile".into());
    }
    let mut started = false;
    for _ in 0..15 {
        if profile_exists() {
            return Ok(());
        }
        let result = Command::new("nmcli")
            .args(["connection", "up", setup_profile, "ifname", "wlan0"])
            .output()
            .map_err(|e| e.to_string())?;
        if result.status.success() {
            started = true;
            break;
        }
        std::thread::sleep(Duration::from_secs(2));
    }
    if !started {
        return Err("NetworkManager could not start setup hotspot".into());
    }
    let address = (0..20)
        .find_map(|_| {
            let address = network::ipv4("wlan0");
            if address.is_none() {
                std::thread::sleep(Duration::from_millis(500));
            }
            address
        })
        .ok_or("setup AP has no IPv4 address")?;
    let listener = TcpListener::bind((address, 8080)).map_err(|e| e.to_string())?;
    listener.set_nonblocking(true).map_err(|e| e.to_string())?;
    eprintln!("[pi-provision] setup page ready at http://{address}:8080");
    while !profile_exists() {
        if network::ipv4("wlan0") != Some(address) {
            return Err("setup AP lost its address".into());
        }
        match listener.accept() {
            Ok((stream, _)) => {
                std::thread::spawn(move || handle(stream));
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(200))
            }
            Err(error) => return Err(error.to_string()),
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn form_decoding_handles_spaces_and_utf8() {
        assert_eq!(
            form_value("ssid=Truck+WiFi&passphrase=pass%25word", "ssid").as_deref(),
            Some("Truck WiFi")
        );
        assert_eq!(
            form_value("ssid=Truck+WiFi&passphrase=pass%25word", "passphrase").as_deref(),
            Some("pass%word")
        );
    }
}
