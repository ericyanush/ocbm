//! One local MFi 2.0C implementation for every box daemon. Whole operations share the
//! cross-process lock; busy NACKs are retried without reopening/resetting the controller.
//! Remote MFI1 behavior remains available through try_cert/try_sign for hosts without a chip.
mod protocol;

use protocol::{Bus, Clock, Driver, TransferError};
use std::{
    fs::{File, OpenOptions},
    os::fd::AsRawFd,
    os::unix::fs::OpenOptionsExt,
    time::{Duration, Instant},
};

pub const MFI_LOCK_PATH: &[u8] = b"/tmp/carplay_mfi.lock\0";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MfiError {
    LockBusy,
    LockUnavailable,
    Chip,
}
impl std::fmt::Display for MfiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::LockBusy => "shared MFi lock busy for 10s",
            Self::LockUnavailable => "shared MFi lock unavailable",
            Self::Chip => "MFi chip operation failed (see mfi-i2c diagnostics)",
        })
    }
}
impl std::error::Error for MfiError {}

struct MfiLock(File);
impl MfiLock {
    fn acquire() -> Result<Self, MfiError> {
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .open("/tmp/carplay_mfi.lock")
            .map_err(|error| {
                eprintln!("[mfi-i2c] lock open failed: {error}");
                MfiError::LockUnavailable
            })?;
        let start = Instant::now();
        loop {
            if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0 {
                return Ok(Self(file));
            }
            let error = std::io::Error::last_os_error();
            if !matches!(error.raw_os_error(), Some(libc::EWOULDBLOCK | libc::EINTR)) {
                eprintln!("[mfi-i2c] lock failed: {error}");
                return Err(MfiError::LockUnavailable);
            }
            if start.elapsed() >= Duration::from_secs(10) {
                return Err(MfiError::LockBusy);
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    }
}
impl Drop for MfiLock {
    fn drop(&mut self) {
        unsafe {
            libc::flock(self.0.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

#[repr(C)]
struct I2cMsg {
    addr: u16,
    flags: u16,
    len: u16,
    buf: *mut u8,
}
#[repr(C)]
struct I2cRdwr {
    msgs: *mut I2cMsg,
    nmsgs: u32,
}

fn transfer_result(
    op: &'static str,
    reg: u8,
    len: usize,
    actual: i32,
    expected: i32,
    errno: i32,
) -> Result<(), TransferError> {
    if actual == expected {
        Ok(())
    } else {
        Err(TransferError {
            op,
            reg,
            len,
            actual,
            expected,
            errno: if actual < 0 { errno } else { 0 },
        })
    }
}

struct LinuxBus<'a>(&'a File);
impl Bus for LinuxBus<'_> {
    fn read(&mut self, reg: u8, out: &mut [u8]) -> Result<(), TransferError> {
        let mut r = reg;
        let mut messages = [
            I2cMsg {
                addr: 0x11,
                flags: 0,
                len: 1,
                buf: &mut r,
            },
            I2cMsg {
                addr: 0x11,
                flags: 1,
                len: out.len() as u16,
                buf: out.as_mut_ptr(),
            },
        ];
        let mut request = I2cRdwr {
            msgs: messages.as_mut_ptr(),
            nmsgs: 2,
        };
        let actual = unsafe { libc::ioctl(self.0.as_raw_fd(), 0x0707 as _, &mut request) };
        let errno = if actual < 0 {
            std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
        } else {
            0
        };
        transfer_result("read", reg, out.len(), actual, 2, errno)
    }
    fn write(&mut self, reg: u8, bytes: &[u8]) -> Result<(), TransferError> {
        let mut bytes = [vec![reg], bytes.to_vec()].concat();
        let mut message = I2cMsg {
            addr: 0x11,
            flags: 0,
            len: bytes.len() as u16,
            buf: bytes.as_mut_ptr(),
        };
        let mut request = I2cRdwr {
            msgs: &mut message,
            nmsgs: 1,
        };
        let actual = unsafe { libc::ioctl(self.0.as_raw_fd(), 0x0707 as _, &mut request) };
        let errno = if actual < 0 {
            std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
        } else {
            0
        };
        transfer_result("write", reg, bytes.len() - 1, actual, 1, errno)
    }
}

struct RealClock(Instant);
impl Clock for RealClock {
    fn now(&self) -> Duration {
        self.0.elapsed()
    }
    fn sleep(&mut self, duration: Duration) {
        std::thread::sleep(duration);
    }
}

pub struct Device {
    file: File,
    source: &'static str,
}
impl Device {
    pub fn open(source: &'static str) -> std::io::Result<Self> {
        // Rust opens File descriptors CLOEXEC. No process-global descriptor that can be
        // invalidated by another caller; failures are retried by the next operation/open.
        Ok(Self {
            file: OpenOptions::new()
                .read(true)
                .write(true)
                .open("/dev/i2c-1")?,
            source,
        })
    }
    fn operation<T>(
        &self,
        op: &str,
        run: impl FnOnce(&mut Driver<LinuxBus<'_>, RealClock>) -> Result<T, protocol::Error>,
    ) -> Result<T, MfiError> {
        let start = Instant::now();
        let _lock = MfiLock::acquire().inspect_err(|error| {
            eprintln!(
                "[mfi-i2c] source={} pid={} op={op} lock_wait_ms={} result={error}",
                self.source,
                std::process::id(),
                start.elapsed().as_millis()
            );
        })?;
        let lock_wait = start.elapsed().as_millis();
        let mut driver = Driver::new(LinuxBus(&self.file), RealClock(Instant::now()));
        let result = run(&mut driver);
        let s = &driver.stats;
        eprintln!("[mfi-i2c] source={} pid={} op={op} result={} elapsed_ms={} lock_wait_ms={lock_wait} reads={} writes={} retries={} busy_nacks={} other_failures={} first_fault={} last_fault={} status={:02x?} error_register={:02x?} certificate_attempts={} length_samples={:02x?}",
            self.source, std::process::id(), result.as_ref().map(|_| "ok".into()).unwrap_or_else(|e| e.to_string()),
            start.elapsed().as_millis(), s.reads, s.writes, s.retries, s.busy_nacks, s.other_failures,
            s.first_fault.map(|e| e.to_string()).unwrap_or_else(|| "none".into()),
            s.last_fault.map(|e| e.to_string()).unwrap_or_else(|| "none".into()),
            s.last_status, s.error_register, s.certificate_attempts, s.length_samples);
        result.map_err(|_| MfiError::Chip)
    }
    pub fn certificate(&self) -> Result<Vec<u8>, MfiError> {
        self.operation("certificate", |driver| driver.certificate())
    }
    pub fn signature(&self, challenge: &[u8]) -> Result<Vec<u8>, MfiError> {
        self.operation("signature", |driver| driver.sign(challenge))
    }
    pub fn device_version(&self) -> Result<u8, MfiError> {
        self.operation("probe", |driver| {
            let mut value = [0];
            driver.read(0, &mut value)?;
            Ok(value[0])
        })
    }
}

fn local_device() -> Result<Device, MfiError> {
    match Device::open("local-api") {
        Ok(device) => Ok(device),
        Err(error) => {
            eprintln!("[mfi-i2c] open /dev/i2c-1 failed: {error}");
            // Preserve the existing distinction for hosts such as Android that cannot even
            // open /tmp/carplay_mfi.lock: this is not a transient chip error to retry.
            let _lock = MfiLock::acquire()?;
            Err(MfiError::Chip)
        }
    }
}
fn remote_err(what: &str, e: mfi_wire::client::Error) -> MfiError {
    eprintln!("[mfi-i2c-local] remote {what} failed: {e}");
    match e {
        mfi_wire::client::Error::Status(mfi_wire::Status::LockBusy) => MfiError::LockBusy,
        _ => MfiError::Chip,
    }
}
pub fn try_cert() -> Result<Vec<u8>, MfiError> {
    if let Some(addr) = mfi_wire::client::addr() {
        return mfi_wire::client::cert(addr).map_err(|e| remote_err("cert", e));
    }
    local_device()?.certificate()
}
pub fn try_sign(challenge: &[u8]) -> Result<Vec<u8>, MfiError> {
    if let Some(addr) = mfi_wire::client::addr() {
        return mfi_wire::client::sign(addr, challenge).map_err(|e| remote_err("sign", e));
    }
    local_device()?.signature(challenge)
}
pub fn cert() -> Option<Vec<u8>> {
    try_cert().ok()
}
pub fn sign(challenge: &[u8]) -> Option<Vec<u8>> {
    try_sign(challenge).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ioctl_requires_all_messages_and_preserves_failure_errno() {
        assert!(transfer_result("read", 0x30, 2, 2, 2, 0).is_ok());
        assert!(transfer_result("write", 0x11, 2, 1, 1, 0).is_ok());
        for (actual, expected) in [(1, 2), (0, 1), (-1, 2)] {
            let error =
                transfer_result("read", 0x30, 2, actual, expected, libc::ENXIO).unwrap_err();
            assert_eq!(error.actual, actual);
            assert_eq!(error.errno, if actual < 0 { libc::ENXIO } else { 0 });
        }
    }

    /// The whole point of the typed `mfi_wire::client::Error`: only a lock timeout may map to
    /// `LockBusy`, and `LockBusy` must never be reported as `Chip` (which `mfi_retry` retries).
    #[test]
    fn remote_lock_busy_is_not_a_chip_error() {
        use mfi_wire::client::Error;
        assert_eq!(
            remote_err("sign", Error::Status(mfi_wire::Status::LockBusy)),
            MfiError::LockBusy
        );
        assert_eq!(
            remote_err("sign", Error::Status(mfi_wire::Status::Chip)),
            MfiError::Chip
        );
        assert_eq!(
            remote_err("cert", Error::Status(mfi_wire::Status::BadRequest)),
            MfiError::Chip
        );
        assert_eq!(
            remote_err("cert", Error::UnknownStatus(0x7f)),
            MfiError::Chip
        );
        assert_eq!(
            remote_err(
                "sign",
                Error::Io(std::io::Error::from(std::io::ErrorKind::TimedOut))
            ),
            MfiError::Chip
        );
    }
}
