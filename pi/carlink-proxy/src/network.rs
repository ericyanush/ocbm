use serde_json::{json, Value};
use std::net::Ipv4Addr;
use std::path::Path;
use std::process::Command;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WifiLink {
    pub address: Ipv4Addr,
    pub profile: String,
    pub connection_path: String,
}

/// NetworkManager gives each activation a new connection path, even when DHCP reuses an IP.
pub fn wifi_link(iface: &str) -> Option<WifiLink> {
    let carrier = std::fs::read_to_string(format!("/sys/class/net/{iface}/carrier")).ok()?;
    if carrier.trim() != "1" { return None; }
    let details = command("nmcli", &["-g", "GENERAL.STATE,GENERAL.CONNECTION,GENERAL.CON-PATH", "device", "show", iface]).ok()?;
    let mut lines = details.lines();
    let state = lines.next()?;
    if !state.starts_with("100 ") { return None; }
    let profile = lines.next()?.to_owned();
    let connection_path = lines.next()?.to_owned();
    if profile.is_empty() || profile == "--" || connection_path.is_empty() || connection_path == "--" || is_setup_profile(&profile) { return None; }
    Some(WifiLink { address: ipv4(iface)?, profile, connection_path })
}

fn command(program: &str, args: &[&str]) -> Result<String, String> {
    let output = Command::new(program)
        .args(args)
        .output()
        .map_err(|e| format!("{program}: {e}"))?;
    if !output.status.success() {
        return Err(format!(
            "{program} failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    Ok(String::from_utf8_lossy(&output.stdout).trim().to_owned())
}

pub fn ipv4(iface: &str) -> Option<Ipv4Addr> {
    let output = command("ip", &["-4", "-o", "addr", "show", "dev", iface]).ok()?;
    let word = output.split_whitespace().collect::<Vec<_>>();
    let at = word.iter().position(|w| *w == "inet")?;
    word.get(at + 1)?.split('/').next()?.parse().ok()
}

pub fn active_profile(iface: &str) -> Result<String, String> {
    let profile = command(
        "nmcli",
        &["-g", "GENERAL.CONNECTION", "device", "show", iface],
    )?;
    if profile.is_empty() || profile == "--" {
        return Err("phone Wi-Fi profile unavailable".into());
    }
    Ok(profile)
}

pub fn is_setup_profile(profile: &str) -> bool {
    profile == "carlink-setup-ap" || profile == "Hotspot"
}

pub fn hotspot(iface: &str, network_config: Option<&Path>) -> Result<Value, String> {
    let address = ipv4(iface).ok_or("phone Wi-Fi network unavailable")?;
    let profile = active_profile(iface)?;
    if is_setup_profile(&profile) {
        return Err("vehicle Wi-Fi unavailable; Pi is in setup mode".into());
    }
    if let Some(path) = network_config {
        let metadata = std::fs::metadata(path).map_err(|e| e.to_string())?;
        #[cfg(unix)]
        if std::os::unix::fs::PermissionsExt::mode(&metadata.permissions()) & 0o077 != 0 {
            return Err("bench network config must be owner-only".into());
        }
        let bench: Value = serde_json::from_slice(&std::fs::read(path).map_err(|e| e.to_string())?)
            .map_err(|e| e.to_string())?;
        if bench["profile"]
            .as_str()
            .is_none_or(|expected| expected == profile)
        {
            return validate(
                address,
                bench["ssid"].as_str().unwrap_or(""),
                bench["passphrase"].as_str().unwrap_or(""),
                bench["channel"].as_u64().unwrap_or(0),
            );
        }
    }
    let ssid = command(
        "nmcli",
        &["-g", "802-11-wireless.ssid", "connection", "show", &profile],
    )?;
    let mut password = command(
        "nmcli",
        &[
            "-s",
            "-g",
            "802-11-wireless-security.psk",
            "connection",
            "show",
            &profile,
        ],
    )?;
    if password.is_empty() || password == "--" {
        let secret_file = std::env::var("CARLINK_PI_WIFI_PASSPHRASE_FILE")
            .unwrap_or_else(|_| "/etc/carlink-pi-proxy/vehicle-passphrase".into());
        password = std::fs::read_to_string(secret_file)
            .map_err(|_| "Wi-Fi password unavailable".to_owned())?
            .trim()
            .to_owned();
    }
    let scan = command(
        "nmcli",
        &[
            "-t",
            "-f",
            "IN-USE,CHAN",
            "device",
            "wifi",
            "list",
            "ifname",
            iface,
            "--rescan",
            "no",
        ],
    )?;
    let channel = scan
        .lines()
        .find(|line| line.starts_with('*') || line.starts_with("yes:"))
        .and_then(|line| line.rsplit(':').next())
        .and_then(|value| value.parse::<u64>().ok())
        .ok_or("connected hotspot channel unavailable")?;
    validate(address, &ssid, &password, channel)
}

fn validate(address: Ipv4Addr, ssid: &str, password: &str, channel: u64) -> Result<Value, String> {
    if ssid.is_empty() || !(8..=63).contains(&password.len()) || !(1..=196).contains(&channel) {
        return Err("phone Wi-Fi SSID, passphrase or channel invalid".into());
    }
    Ok(json!({"ap_ip":address.to_string(), "ssid":ssid, "passphrase":password, "channel":channel}))
}
