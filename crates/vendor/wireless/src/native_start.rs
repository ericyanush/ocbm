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
    fmt::Write as _,
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

static EVENT_WRITE: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn write_slot_event(path: &std::path::Path, event: &Event) -> std::io::Result<bool> {
    let _guard = EVENT_WRITE.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(current) = fs::read(path).ok().and_then(|b| serde_json::from_slice::<Event>(&b).ok()) {
        if current.slot_id != event.slot_id &&
            (event.stage != "connected" || current.slot_started_at >= event.slot_started_at) {
            return Ok(false);
        }
    }
    let bytes = serde_json::to_vec(event)?;
    carplay_start_session::write_atomic(path, &bytes)?;
    Ok(true)
}

fn monotonic_slot_start() -> u64 {
    let mut time = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut time) } != 0 {
        return 0;
    }
    (time.tv_sec as u64).saturating_mul(1_000_000_000).saturating_add(time.tv_nsec as u64)
}

fn end_event(event: &mut Event) {
    if matches!(event.stage.as_str(), "failed" | "unsupported") {
        event.failure_reason = Some(event.reason.clone());
    }
    event.stage = "ended".into();
    event.reason = "RFCOMM connection ended".into();
    event.available = false;
}

fn publish(b: &mut Bootstrap, stage: &str, reason: &str) {
    b.event.stage = stage.into();
    b.event.reason = reason.into();
    b.event.available = b.gate.available && b.gate.identified;
    match write_slot_event(&carplay_start_session::event_path(&b.event.peer_mac), &b.event) {
        Ok(true) => {},
        Ok(false) => {
            crate::log(&format!("ignoring superseded StartSession event peer={} slot={} stage={stage}", b.event.peer_mac, b.event.slot_id));
            return;
        }
        Err(e) => crate::log(&format!("cannot publish StartSession event: {e}")),
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
            slot_started_at: monotonic_slot_start(),
            request_id: String::new(),
            receiver_epoch: String::new(),
            stage: String::new(),
            reason: String::new(),
            failure_reason: None,
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
            end_event(&mut b.event);
            publish(&mut b, "ended", "RFCOMM connection ended");
        }
    });
}

pub fn incoming(id: u16, body: &[u8], link_seq: u8, session: u8, frame_bytes: usize, message_bytes: usize) -> bool {
    CURRENT.with(|c| {
        let mut current = c.borrow_mut(); let Some(b) = current.as_mut() else { return false; };
        if id == 0x4300 {
            match carplay_iap2_core::session::parse_carplay_availability(body) {
                Ok(a) => {
                    b.gate.available = a.wireless.as_ref().and_then(|w| w.available) == Some(true);
                    b.saw_available |= b.gate.available;
                    crate::log(&format!("RX 0x4300 peer={} slot={} link_seq={} session={} frame_bytes={} message_bytes={} body_bytes={} wireless={:?} transport={:?}",
                        b.event.peer_mac, b.event.slot_id, link_seq, session, frame_bytes, message_bytes, body.len(),
                        a.wireless.as_ref().and_then(|w| w.available), a.wireless.as_ref().and_then(|w| w.transport_identifier.as_ref())));
                    publish(b, if b.gate.available { "available" } else { "unavailable" }, "");
                }
                Err(error) => {
                    // Keep one bounded log line while retaining the complete body for typical
                    // messages; this body contains bearer metadata, not Wi-Fi credentials.
                    const MAX_BODY_DIAGNOSTIC_BYTES: usize = 320;
                    let captured = &body[..body.len().min(MAX_BODY_DIAGNOSTIC_BYTES)];
                    let mut body_hex = String::with_capacity(captured.len() * 2);
                    for byte in captured { let _ = write!(body_hex, "{byte:02x}"); }
                    crate::log(&format!("RX 0x4300 parse failed peer={} slot={} link_seq={} session={} frame_bytes={} message_bytes={} body_bytes={} body_hex={} body_hex_truncated={} error={error:?}",
                        b.event.peer_mac, b.event.slot_id, link_seq, session, frame_bytes, message_bytes,
                        body.len(), body_hex, captured.len() != body.len()));
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

/// A frame never put on the wire must not consume a link sequence number. Once any byte is
/// written, cancellation requires a new RFCOMM connection to avoid a truncated iAP2 frame.
fn write_start_frame<W: std::io::Write>(sock: &mut W, link: &mut Link, body: &[u8], abort: &dyn Fn() -> bool) -> Result<(), crate::bt_driver::WriteFailure> {
    let seq = link.my_seq;
    let bytes = link.build_msg(1, 0x4301, body);
    let result = crate::bt_driver::write_interruptible_report(sock, &bytes, abort);
    if matches!(&result, Err(failure) if failure.written == 0 && failure.error.is_none()) {
        link.my_seq = seq;
    }
    result
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
        let write_started = Instant::now();
        let write_expired = || write_started.elapsed() >= Duration::from_secs(5);
        if let Err(failure) = write_start_frame(sock, link, &body, &|| abort() || !still_current() || write_expired()) {
            let cancelled_before_write = failure.error.is_none() && failure.written == 0 && !abort() && !write_expired();
            let reason = if write_expired() { format!("StartSession write deadline exceeded: {failure}") }
                else { format!("StartSession write {failure}") };
            crate::log(&format!("StartSession write stopped peer={} slot={} request={} receiver={} keep_link={cancelled_before_write}: {reason}",
                b.event.peer_mac, b.event.slot_id, r.request_id, r.receiver_epoch));
            publish(b, if cancelled_before_write { "cancelled" } else { "failed" }, &reason);
            return !cancelled_before_write;
        }
        if !still_current() { publish(b, "cancelled", "host changed during write"); return false; }
        b.gate.sent_request = Some(r.request_id.clone()); b.endpoint = e.clone();
        crate::log(&format!("TX 0x4301 complete peer={} receiver={} iface={} addresses={:?} port={} public_key_prefix={}",
            b.event.peer_mac, e.device_id, e.interface, e.addresses, e.port, &e.public_key[..8]));
        publish(b, "sent", ""); false
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cancellation_before_any_bytes_preserves_the_link_sequence() {
        let mut link = Link::new();
        link.my_seq = 42;
        let mut wire = Vec::new();
        let failure = write_start_frame(&mut wire, &mut link, b"payload", &|| true).unwrap_err();
        assert_eq!(failure.written, 0);
        assert!(failure.error.is_none());
        assert!(wire.is_empty());
        assert_eq!(link.my_seq, 42);
        write_start_frame(&mut wire, &mut link, b"replacement", &|| false).unwrap();
        assert_eq!(wire[5], 42);
        assert_eq!(link.my_seq, 43);
    }

    #[test]
    fn cancellation_after_partial_write_requires_reconnect_and_keeps_sequence_consumed() {
        use std::cell::Cell;
        struct Partial<'a>(&'a Cell<bool>);
        impl std::io::Write for Partial<'_> {
            fn write(&mut self, _: &[u8]) -> std::io::Result<usize> { self.0.set(true); Ok(3) }
            fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
        }
        let cancelled = Cell::new(false);
        let mut link = Link::new();
        let failure = write_start_frame(&mut Partial(&cancelled), &mut link, b"payload", &|| cancelled.get()).unwrap_err();
        assert_eq!(failure.written, 3);
        assert!(failure.error.is_none());
        assert_eq!(link.my_seq, 1);
    }

    #[test]
    fn socket_failure_preserves_errno_and_interrupted_writes_are_retried() {
        struct Broken(usize);
        impl std::io::Write for Broken {
            fn write(&mut self, _: &[u8]) -> std::io::Result<usize> {
                self.0 += 1;
                match self.0 {
                    1 => Err(std::io::ErrorKind::Interrupted.into()),
                    2 => Ok(2),
                    _ => Err(std::io::Error::from_raw_os_error(libc::EPIPE)),
                }
            }
            fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
        }
        let failure = write_start_frame(&mut Broken(0), &mut Link::new(), b"payload", &|| false).unwrap_err();
        assert_eq!(failure.written, 2);
        assert_eq!(failure.error.as_ref().unwrap().raw_os_error(), Some(libc::EPIPE));
        assert!(failure.to_string().contains("I/O error after 2 bytes"));
    }

    fn event(slot: &str, start: u64, stage: &str) -> Event {
        serde_json::from_value(serde_json::json!({
            "version": 1, "peerMac": "02:00:00:AA:BB:01", "slotId": slot,
            "slotStartedAt": start, "requestId": "request", "receiverEpoch": "receiver",
            "stage": stage, "reason": "write failed", "available": true
        })).unwrap()
    }

    #[test]
    fn closing_a_failed_slot_always_publishes_ended_and_preserves_failure() {
        for stage in ["failed", "unsupported", "available"] {
            let mut e = event("slot-1", 1, stage);
            end_event(&mut e);
            assert_eq!(e.stage, "ended");
            assert!(!e.available);
            assert_eq!(e.failure_reason.as_deref(), if stage == "available" { None } else { Some("write failed") });
        }
    }

    #[test]
    fn old_slot_teardown_cannot_overwrite_a_replacement_even_if_connected_is_replayed() {
        let path = std::env::temp_dir().join(format!("ocbm-slot-event-test-{}.json", std::process::id()));
        let old = event("slot-1", 10, "connected");
        assert!(write_slot_event(&path, &old).unwrap());
        let replacement = event("slot-2", 20, "connected");
        assert!(write_slot_event(&path, &replacement).unwrap());
        let mut ended = old.clone();
        end_event(&mut ended);
        assert!(!write_slot_event(&path, &ended).unwrap());
        assert!(!write_slot_event(&path, &old).unwrap());
        assert_eq!(serde_json::from_slice::<Event>(&fs::read(&path).unwrap()).unwrap().slot_id, "slot-2");
        fs::remove_file(path).unwrap();
    }
}
