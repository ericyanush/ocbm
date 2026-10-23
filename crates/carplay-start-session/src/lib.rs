//! Host-selected native receiver bootstrap. USB dispatch replaces a bounded request file;
//! the RFCOMM owner performs Bluetooth writes. State is ephemeral and atomically replaced.
use serde::{Deserialize, Serialize};
use std::{fs, net::IpAddr, path::Path};

pub const DIR: &str = "/tmp/ocbm_start_session";
pub const REQUEST: &str = "/tmp/ocbm_start_session/request.json";
pub const MAX_BYTES: usize = 8192;

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Endpoint {
    pub port: u16,
    pub device_id: String,
    pub pairing_id: String,
    pub public_key: String,
    pub source_version: String,
    pub interface: String,
    pub addresses: Vec<IpAddr>,
    pub ssid: String,
    pub passphrase: String,
    pub channel: u8,
    pub security: u8,
}
impl std::fmt::Debug for Endpoint {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Endpoint")
            .field("device_id", &self.device_id)
            .field("port", &self.port)
            .field("addresses", &self.addresses)
            .field("passphrase", &"<redacted>")
            .finish_non_exhaustive()
    }
}

pub fn valid_mac(value: &str) -> bool {
    value.len() == 17
        && value.split(':').count() == 6
        && value
            .split(':')
            .all(|p| p.len() == 2 && p.bytes().all(|b| b.is_ascii_hexdigit()))
}

impl Endpoint {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.port == 0 || !valid_mac(&self.device_id) {
            return Err("invalid receiver port/device ID");
        }
        if self.public_key.len() != 64 || !self.public_key.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err("invalid receiver public key");
        }
        if [
            &self.pairing_id,
            &self.source_version,
            &self.interface,
            &self.ssid,
            &self.passphrase,
        ]
        .iter()
        .any(|s| s.is_empty() || s.contains('\0') || s.contains('\n') || s.contains('\r'))
        {
            return Err("invalid endpoint string");
        }
        if self.ssid.len() > 32
            || !(8..=63).contains(&self.passphrase.len())
            || !(1..=196).contains(&self.channel)
            || !(2..=4).contains(&self.security)
        {
            return Err("invalid hotspot configuration");
        }
        if self.addresses.is_empty()
            || self.addresses.len() > 16
            || self.addresses.iter().any(|a| {
                a.is_unspecified()
                    || a.is_multicast()
                    || a.is_loopback()
                    || matches!(a, IpAddr::V6(v) if !v.is_unicast_link_local())
            })
        {
            return Err("invalid hotspot addresses");
        }
        Ok(())
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Request {
    pub version: u8,
    pub action: String,
    pub request_id: String,
    pub peer_mac: String,
    pub slot_id: String,
    pub receiver_epoch: String,
    pub endpoint: Option<Endpoint>,
}

impl Request {
    pub fn parse(bytes: &[u8]) -> Result<Self, &'static str> {
        if bytes.len() > MAX_BYTES {
            return Err("request too large");
        }
        let value: Self = serde_json::from_slice(bytes).map_err(|_| "invalid request JSON")?;
        if value.version != 1
            || !matches!(value.action.as_str(), "start" | "cancel")
            || !valid_mac(&value.peer_mac)
            || [&value.request_id, &value.slot_id, &value.receiver_epoch]
                .iter()
                .any(|s| {
                    s.is_empty()
                        || s.len() > 128
                        || !s
                            .bytes()
                            .all(|b| b.is_ascii_alphanumeric() || b"-_.".contains(&b))
                })
        {
            return Err("invalid request identity");
        }
        if value.action == "start" {
            value
                .endpoint
                .as_ref()
                .ok_or("missing endpoint")?
                .validate()?;
        }
        Ok(value)
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Event {
    pub version: u8,
    pub peer_mac: String,
    pub slot_id: String,
    pub request_id: String,
    pub receiver_epoch: String,
    pub stage: String,
    pub reason: String,
    pub available: bool,
}

pub fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    use std::sync::atomic::{AtomicUsize, Ordering};
    static SEQ: AtomicUsize = AtomicUsize::new(0);
    fs::create_dir_all(path.parent().unwrap())?;
    let temp = path.with_extension(format!(
        "tmp.{}.{}",
        std::process::id(),
        SEQ.fetch_add(1, Ordering::Relaxed)
    ));
    // Requests contain hotspot credentials. Restrict access before writing any bytes.
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&temp)?;
    if let Err(e) = file.write_all(bytes) {
        let _ = fs::remove_file(&temp);
        return Err(e);
    }
    if let Err(e) = fs::rename(&temp, path) {
        let _ = fs::remove_file(temp);
        return Err(e);
    }
    Ok(())
}

pub fn event_path(mac: &str) -> std::path::PathBuf {
    Path::new(DIR).join(format!("{}.json", mac.to_ascii_uppercase()))
}

#[derive(Default)]
pub struct Gate {
    pub available: bool,
    pub identified: bool,
    pub sent_request: Option<String>,
}
impl Gate {
    pub fn eligible(&self, r: &Request, peer: &str, slot: &str) -> bool {
        self.available
            && self.identified
            && r.action == "start"
            && r.peer_mac.eq_ignore_ascii_case(peer)
            && r.slot_id == slot
            && self.sent_request.as_deref() != Some(r.request_id.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn request() -> Request {
        Request {
            version: 1,
            action: "start".into(),
            request_id: "one".into(),
            peer_mac: "02:00:00:AA:BB:01".into(),
            slot_id: "slot".into(),
            receiver_epoch: "rx".into(),
            endpoint: Some(Endpoint {
                port: 7011,
                device_id: "B2:D6:2A:9F:C9:30".into(),
                pairing_id: "pi".into(),
                public_key: "ab".repeat(32),
                source_version: "320.17".into(),
                interface: "br0".into(),
                addresses: vec!["192.168.5.1".parse().unwrap(), "fe80::1".parse().unwrap()],
                ssid: "Truck".into(),
                passphrase: "password".into(),
                channel: 149,
                security: 2,
            }),
        }
    }
    #[test]
    fn request_roundtrip_and_invalid_input() {
        let mut r = request();
        assert!(Request::parse(&serde_json::to_vec(&r).unwrap()).is_ok());
        r.endpoint.as_mut().unwrap().addresses = vec!["::".parse().unwrap()];
        assert!(Request::parse(&serde_json::to_vec(&r).unwrap()).is_err());
        assert!(Request::parse(&vec![b' '; MAX_BYTES + 1]).is_err());
    }
    #[test]
    fn only_current_selected_identified_available_slot_can_send_once() {
        let r = request();
        let mut g = Gate::default();
        assert!(!g.eligible(&r, &r.peer_mac, "slot"));
        g.available = true;
        assert!(!g.eligible(&r, &r.peer_mac, "slot"));
        g.identified = true;
        assert!(g.eligible(&r, &r.peer_mac, "slot"));
        assert!(!g.eligible(&r, &r.peer_mac, "old"));
        assert!(!g.eligible(&r, "02:00:00:AA:BB:02", "slot"));
        g.sent_request = Some(r.request_id.clone());
        assert!(!g.eligible(&r, &r.peer_mac, "slot"));
        g.sent_request = None;
        let mut cancel = r.clone();
        cancel.action = "cancel".into();
        assert!(!g.eligible(&cancel, &r.peer_mac, "slot"));
    }
}
