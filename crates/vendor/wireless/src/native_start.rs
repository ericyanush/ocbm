//! Per-RFCOMM bootstrap; only the socket owner may send StartSession.
use carplay_iap2_core::{
    config::Iap2Config,
    link::Link,
    session::{CarPlayStartSession, WirelessAttributes},
};
use carplay_start_session::{Endpoint, Event, Gate, Request};
use std::{
    cell::RefCell,
    fs,
    time::{Duration, Instant},
};

struct Bootstrap {
    endpoint: Endpoint,
    event: Event,
    gate: Gate,
    identified_at: Option<Instant>,
    saw_available: bool,
    terminal: bool,
}
thread_local! { static CURRENT: RefCell<Option<Bootstrap>> = const { RefCell::new(None) }; }

fn publish(b: &mut Bootstrap, stage: &str, reason: &str) {
    b.event.stage = stage.into();
    b.event.reason = reason.into();
    b.event.available = b.gate.available && b.gate.identified;
    if let Ok(bytes) = serde_json::to_vec(&b.event) {
        let _ = carplay_start_session::write_atomic(
            &carplay_start_session::event_path(&b.event.peer_mac),
            &bytes,
        );
    }
    crate::log(&format!(
        "StartSession peer={} slot={} request={} receiver={} stage={} available={} reason={}",
        b.event.peer_mac,
        b.event.slot_id,
        b.event.request_id,
        b.event.receiver_epoch,
        stage,
        b.gate.available,
        reason
    ));
}

pub fn begin(peer: &[u8; 6]) -> bool {
    let cfg = Iap2Config::load().iap_config;
    if !cfg.wireless_start_session {
        CURRENT.with(|c| *c.borrow_mut() = None);
        return true;
    }
    let Some(endpoint) = cfg
        .native_receiver_endpoint
        .filter(|e| e.validate().is_ok())
    else {
        crate::log(
            "StartSession enabled without a valid native receiver endpoint; refusing bootstrap",
        );
        return false;
    };
    static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let mut b = Bootstrap {
        endpoint,
        event: Event {
            version: 1,
            peer_mac: crate::control::fmt_addr(peer),
            slot_id: format!(
                "{}-{stamp}-{}",
                std::process::id(),
                NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
            ),
            request_id: String::new(),
            receiver_epoch: String::new(),
            stage: String::new(),
            reason: String::new(),
            available: false,
        },
        gate: Gate::default(),
        identified_at: None,
        saw_available: false,
        terminal: false,
    };
    publish(&mut b, "connected", "");
    CURRENT.with(|c| *c.borrow_mut() = Some(b));
    true
}
pub fn enabled() -> bool {
    CURRENT.with(|c| c.borrow().is_some())
}
pub fn endpoint() -> Option<Endpoint> {
    CURRENT.with(|c| c.borrow().as_ref().map(|b| b.endpoint.clone()))
}
pub fn finish() {
    CURRENT.with(|c| {
        if let Some(mut b) = c.borrow_mut().take() {
            b.gate.available = false;
            let stage = b.event.stage.clone();
            let reason = b.event.reason.clone();
            if matches!(stage.as_str(), "failed" | "unsupported") {
                publish(&mut b, &stage, &reason);
            } else {
                publish(&mut b, "ended", "RFCOMM connection ended");
            }
        }
    });
}

pub fn incoming(id: u16, body: &[u8]) -> bool {
    CURRENT.with(|c| {
        let mut current = c.borrow_mut(); let Some(b) = current.as_mut() else { return false; };
        if id == 0x4300 {
            match carplay_iap2_core::session::parse_carplay_availability(body) {
                Ok(a) => {
                    b.gate.available = a.wireless.as_ref().and_then(|w| w.available) == Some(true);
                    b.saw_available |= b.gate.available;
                    crate::log(&format!("RX 0x4300 peer={} wireless={:?} transport={:?}", b.event.peer_mac,
                        a.wireless.as_ref().and_then(|w| w.available), a.wireless.as_ref().and_then(|w| w.transport_identifier.as_ref())));
                    publish(b, if b.gate.available { "available" } else { "unavailable" }, "");
                }
                Err(error) => {
                    // Availability contains bearer state/identifiers, never Wi-Fi credentials.
                    crate::log(&format!("RX 0x4300 parse failed peer={} error={error:?} body_bytes={} body_prefix={:02x?}",
                        b.event.peer_mac, body.len(), &body[..body.len().min(128)]));
                    b.gate.available = false;
                    publish(b, "failed", &format!("malformed CarPlayAvailability ({error:?})"));
                    b.terminal = true;
                }
            }
        } else if id == 0x1D03 {
            let mut offset = 0;
            while offset + 4 <= body.len() {
                let len = u16::from_be_bytes([body[offset], body[offset+1]]) as usize;
                let param = u16::from_be_bytes([body[offset+2], body[offset+3]]);
                if len < 4 || offset + len > body.len() { break; }
                if matches!(param, 6 | 7) && body[offset+4..offset+len].chunks_exact(2).any(|v| v == [0x43, 0] || v == [0x43, 1]) {
                    publish(b, "unsupported", "phone rejected StartSession declarations; disable experiment for legacy startup"); b.terminal = true;
                }
                offset += len;
            }
        }
        b.terminal
    })
}

pub fn poll(
    sock: &mut fs::File,
    link: &mut Link,
    abort: &dyn Fn() -> bool,
    identified: bool,
) -> bool {
    CURRENT.with(|c| {
        let mut current = c.borrow_mut(); let Some(b) = current.as_mut() else { return false; };
        if b.terminal { return true; }
        if identified && !b.gate.identified {
            b.gate.identified = true; b.identified_at = Some(Instant::now());
            publish(b, if b.gate.available { "available" } else { "identified" }, "");
        }
        if !b.saw_available && b.identified_at.is_some_and(|t| t.elapsed() > Duration::from_secs(60)) {
            publish(b, "failed", "no wireless availability within 60 seconds"); return true;
        }
        if fs::read_to_string("/tmp/host_present").ok().as_deref().map(str::trim) != Some("1") { return false; }
        let Some(r) = fs::read(carplay_start_session::REQUEST).ok().and_then(|v| Request::parse(&v).ok()) else { return false; };
        if r.peer_mac.eq_ignore_ascii_case(&b.event.peer_mac) && r.slot_id == b.event.slot_id && r.action == "cancel" {
            if b.event.request_id != r.request_id || b.event.stage != "cancelled" {
                b.event.request_id = r.request_id; b.event.receiver_epoch = r.receiver_epoch; publish(b, "cancelled", "host cancelled attempt");
            }
            return false;
        }
        if !b.gate.eligible(&r, &b.event.peer_mac, &b.event.slot_id) { return false; }
        b.event.request_id = r.request_id.clone(); b.event.receiver_epoch = r.receiver_epoch.clone();
        let e = r.endpoint.as_ref().unwrap();
        let msg = CarPlayStartSession { wireless: Some(WirelessAttributes { wifi_ssid: &e.ssid, passphrase: &e.passphrase,
            channel: e.channel, ip_addresses: &e.addresses, security_type: e.security }),
            ..CarPlayStartSession::new(e.port.into(), &e.device_id, &e.public_key, &e.source_version) };
        let body = match msg.build() { Ok(v) => v, Err(_) => { publish(b, "failed", "invalid StartSession payload"); return true; } };
        let still_current = || fs::read_to_string("/tmp/host_present").ok().as_deref().map(str::trim) == Some("1") && fs::read(carplay_start_session::REQUEST).ok().and_then(|v| Request::parse(&v).ok())
            .is_some_and(|latest| latest.action == "start" && latest.request_id == r.request_id && latest.slot_id == r.slot_id && latest.receiver_epoch == r.receiver_epoch);
        if !still_current() { return false; }
        let bytes = link.build_msg(1, 0x4301, &body);
        if !crate::bt_driver::write_interruptible(sock, &bytes, &|| abort() || !still_current()) {
            publish(b, "failed", "StartSession write failed or cancelled"); return true;
        }
        if !still_current() { publish(b, "cancelled", "host changed during write"); return false; }
        b.gate.sent_request = Some(r.request_id.clone()); b.endpoint = e.clone();
        crate::log(&format!("TX 0x4301 complete peer={} receiver={} iface={} addresses={:?} port={} public_key_prefix={}",
            b.event.peer_mac, e.device_id, e.interface, e.addresses, e.port, &e.public_key[..8]));
        publish(b, "sent", ""); false
    })
}
