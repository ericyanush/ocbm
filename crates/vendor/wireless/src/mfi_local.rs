//! Local MFi uses the shared 2.0C driver; the Pi's existing remote MFI1 backend is unchanged.

/// Env var selecting a REMOTE MFi service instead of this box's local i2c coprocessor.
///
/// Set it to `host:port` (e.g. `192.168.50.2:7789`) and `cert`/`sign` speak the `MFI1` protocol to
/// `mfid` instead of touching `/dev/i2c-1`. This exists for the **Raspberry Pi port**, where the Pi
/// runs the Bluetooth/iAP2 stack but has no coprocessor of its own and reaches the CCPA's over
/// USB-NCM.
///
/// UNSET selects the shared local driver. The remote daemon takes the chip lock on its host.
const REMOTE_ADDR_ENV: &str = "CARPLAY_MFI_ADDR";

/// Resolved ONCE per process, matching the project's env-lever convention — editing the variable
/// mid-run does nothing until the daemon restarts.
fn remote_addr() -> Option<&'static str> {
    static ADDR: std::sync::OnceLock<Option<String>> = std::sync::OnceLock::new();
    ADDR.get_or_init(|| match std::env::var(REMOTE_ADDR_ENV) {
        Ok(v) if !v.trim().is_empty() => {
            let v = v.trim().to_string();
            eprintln!("[mfi] REMOTE backend {v} — local /dev/i2c-1 will NOT be used");
            Some(v)
        }
        _ => None,
    })
    .as_deref()
}

/// MFI1 client for the remote coprocessor. Deliberately blocking and synchronous: `sign` is called
/// inside the iAP2 handshake against the phone's timeout, so no async boundary may appear here.
mod remote {
    use mfi_wire::{read_frame, write_frame, Op, Status, DIGEST_LEN};
    use std::io::Write;
    use std::net::{TcpStream, ToSocketAddrs};
    use std::time::Duration;

    const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
    /// Generous on purpose: a contended chip lock is bounded at 10 s inside the daemon and the sign
    /// path itself measures ~1.5 s over NCM. A tighter client timeout would report false failures.
    const IO_TIMEOUT: Duration = Duration::from_secs(30);

    fn exchange(addr: &str, op: Op, payload: &[u8]) -> Option<Vec<u8>> {
        let sa = match addr.to_socket_addrs().ok().and_then(|mut a| a.next()) {
            Some(sa) => sa,
            None => {
                eprintln!("[mfi] remote: cannot resolve {addr}");
                return None;
            }
        };
        let mut s = match TcpStream::connect_timeout(&sa, CONNECT_TIMEOUT) {
            Ok(s) => s,
            Err(e) => {
                eprintln!("[mfi] remote: connect {addr} failed: {e}");
                return None;
            }
        };
        let _ = s.set_read_timeout(Some(IO_TIMEOUT));
        let _ = s.set_write_timeout(Some(IO_TIMEOUT));
        let _ = s.set_nodelay(true);

        if let Err(e) = write_frame(&mut s, op as u8, payload).and_then(|()| s.flush()) {
            eprintln!("[mfi] remote: send failed: {e}");
            return None;
        }
        match read_frame(&mut s) {
            Ok((code, body)) => match Status::from_u8(code) {
                Some(Status::Ok) => Some(body),
                Some(st) => {
                    eprintln!("[mfi] remote: daemon returned {}", st.as_str());
                    None
                }
                None => {
                    eprintln!("[mfi] remote: unknown status 0x{code:02x}");
                    None
                }
            },
            Err(e) => {
                eprintln!("[mfi] remote: receive failed: {e}");
                None
            }
        }
    }

    pub fn cert(addr: &str) -> Option<Vec<u8>> {
        exchange(addr, Op::Cert, &[])
    }

    pub fn sign(addr: &str, chal: &[u8]) -> Option<Vec<u8>> {
        // Reject locally rather than let the daemon answer BadRequest — the chip would otherwise be
        // handed a short buffer and fail with an opaque NAK.
        if chal.len() != DIGEST_LEN {
            eprintln!(
                "[mfi] remote: digest is {} bytes, expected {DIGEST_LEN}",
                chal.len()
            );
            return None;
        }
        exchange(addr, Op::Sign, chal)
    }
}

fn local_device() -> Option<mfi_i2c_local::Device> {
    mfi_i2c_local::Device::open("btd")
        .map_err(|error| {
            eprintln!("[mfi-local] open /dev/i2c-1 failed: {error}");
        })
        .ok()
}

pub fn cert() -> Option<Vec<u8>> {
    if let Some(addr) = remote_addr() {
        return remote::cert(addr);
    }
    local_device()?.certificate().ok()
}

pub fn sign(challenge: &[u8]) -> Option<Vec<u8>> {
    if let Some(addr) = remote_addr() {
        return remote::sign(addr, challenge);
    }
    local_device()?.signature(challenge).ok()
}
