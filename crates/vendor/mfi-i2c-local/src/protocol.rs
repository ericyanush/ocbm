//! Shared MFi 2.0B transaction policy. No hardware or sleeping in tests: bus and clock are injected.
use std::{fmt, time::Duration};

const REGISTER_WAIT: Duration = Duration::from_millis(250);
const SIGN_WAIT: Duration = Duration::from_millis(2500);
const RETRY_PAUSE: Duration = Duration::from_millis(5);
// Linux's I2C data-NACK errno; libc on the macOS test host does not define EREMOTEIO.
const REMOTE_IO: i32 = 121;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct TransferError {
    pub op: &'static str,
    pub reg: u8,
    pub len: usize,
    pub actual: i32,
    pub expected: i32,
    pub errno: i32,
}

impl TransferError {
    fn busy(self) -> bool {
        matches!(self.errno, libc::ENXIO | REMOTE_IO)
    }
    fn retryable(self) -> bool {
        self.actual >= 0
            || matches!(
                self.errno,
                libc::ENXIO | REMOTE_IO | libc::EAGAIN | libc::EINTR | libc::EIO
            )
    }
}

impl fmt::Display for TransferError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{} reg=0x{:02x} len={} messages={}/{} errno={}",
            self.op, self.reg, self.len, self.actual, self.expected, self.errno
        )
    }
}

pub(crate) trait Bus {
    fn read(&mut self, reg: u8, out: &mut [u8]) -> Result<(), TransferError>;
    fn write(&mut self, reg: u8, data: &[u8]) -> Result<(), TransferError>;
}

pub(crate) trait Clock {
    fn now(&self) -> Duration;
    fn sleep(&mut self, duration: Duration);
}

#[derive(Debug)]
pub(crate) enum Error {
    Transfer(TransferError),
    InvalidChallenge(usize),
    CertificateInconsistent,
    InvalidSignatureLength(usize),
    Status { status: u8, error_code: Option<u8> },
    ChipError(u8),
    SignTimeout,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Transfer(error) => write!(f, "I2C transfer failed: {error}"),
            Self::InvalidChallenge(n) => write!(f, "invalid challenge length {n}"),
            Self::CertificateInconsistent => write!(
                f,
                "certificate length/data inconsistent after three attempts"
            ),
            Self::InvalidSignatureLength(n) => write!(f, "invalid RSA-1024 signature length {n}"),
            Self::Status { status, error_code } => write!(
                f,
                "signature status=0x{status:02x} error_code={error_code:?}"
            ),
            Self::ChipError(code) => write!(f, "coprocessor error_code=0x{code:02x}"),
            Self::SignTimeout => write!(f, "signature completion exceeded 2500ms"),
        }
    }
}

#[derive(Debug, Default)]
pub(crate) struct Stats {
    pub reads: usize,
    pub writes: usize,
    pub retries: usize,
    pub busy_nacks: usize,
    pub other_failures: usize,
    pub first_fault: Option<TransferError>,
    pub last_fault: Option<TransferError>,
    pub last_status: Option<u8>,
    pub error_register: Option<u8>,
    pub length_samples: Vec<[u8; 2]>,
    pub certificate_attempts: usize,
    pub post_length_anomalies: usize,
    pub post_length_resamples: usize,
    pub device_version: Option<u8>,
    pub compatibility_profile: Option<&'static str>,
    pub capacity_initialized: bool,
}

pub(crate) struct Driver<B, C> {
    pub bus: B,
    pub clock: C,
    pub stats: Stats,
}

impl<B: Bus, C: Clock> Driver<B, C> {
    pub fn new(bus: B, clock: C) -> Self {
        Self {
            bus,
            clock,
            stats: Stats::default(),
        }
    }

    fn fault(&mut self, error: TransferError) {
        if error.busy() {
            self.stats.busy_nacks += 1;
        } else {
            self.stats.other_failures += 1;
        }
        self.stats.first_fault.get_or_insert(error);
        self.stats.last_fault = Some(error);
    }

    fn read_once(&mut self, reg: u8, out: &mut [u8]) -> Result<(), TransferError> {
        out.fill(0); // A failed/short read must never leave bytes from a preceding attempt.
        self.stats.reads += 1;
        self.bus
            .read(reg, out)
            .inspect_err(|error| self.fault(*error))
    }

    fn write_once(&mut self, reg: u8, data: &[u8]) -> Result<(), TransferError> {
        self.stats.writes += 1;
        self.bus
            .write(reg, data)
            .inspect_err(|error| self.fault(*error))
    }

    pub fn read(&mut self, reg: u8, out: &mut [u8]) -> Result<(), Error> {
        let deadline = self.clock.now() + REGISTER_WAIT;
        loop {
            match self.read_once(reg, out) {
                Ok(()) => return Ok(()),
                Err(error) if error.retryable() && self.clock.now() < deadline => {
                    self.stats.retries += 1;
                    self.clock.sleep(RETRY_PAUSE);
                }
                Err(error) => return Err(Error::Transfer(error)),
            }
        }
    }

    fn write(&mut self, reg: u8, data: &[u8]) -> Result<(), Error> {
        let deadline = self.clock.now() + REGISTER_WAIT;
        loop {
            match self.write_once(reg, data) {
                Ok(()) => return Ok(()),
                // A trigger may have reached the chip despite a failed ioctl. Only ENXIO
                // (address not acknowledged) proves that it has not started; do not replay an
                // ambiguous data NACK, partial write, EINTR or EIO on this stateful register.
                Err(error)
                    if (if reg == 0x10 {
                        error.actual < 0 && error.errno == libc::ENXIO
                    } else {
                        error.retryable()
                    }) && self.clock.now() < deadline =>
                {
                    self.stats.retries += 1;
                    self.clock.sleep(RETRY_PAUSE);
                }
                Err(error) => return Err(Error::Transfer(error)),
            }
        }
    }

    fn clear_error(&mut self) -> Result<(), Error> {
        let mut value = [0];
        self.read(0x05, &mut value)?; // Read-to-clear per the 2.0B register map.
                                      // Board probes report 0xff on this register on some adapters. Record it, but do not
                                      // reject an otherwise valid operation merely because this register is unimplemented.
        self.stats.error_register = Some(value[0]);
        Ok(())
    }

    fn certificate_length(&mut self) -> Result<usize, Error> {
        let mut value = [0; 2];
        self.read(0x30, &mut value)?;
        self.stats.length_samples.push(value);
        Ok(u16::from_be_bytes(value).into())
    }

    fn check_error(&mut self) -> Result<(), Error> {
        self.clear_error()?;
        match self.stats.error_register {
            Some(0 | 0xff) => Ok(()), // 0xff is an unsupported error register on some boards.
            Some(code) => Err(Error::ChipError(code)),
            None => unreachable!(),
        }
    }

    fn confirm_post_certificate_length(&mut self, expected: usize) -> Result<bool, Error> {
        if self.certificate_length()? == expected {
            return Ok(true);
        }
        self.stats.post_length_anomalies += 1;
        // On this adapter the first length read after a continuous certificate read returns
        // 9c:b1 even after a 100ms delay. Re-selecting 0x30 again returns 03:b1. Require two
        // consecutive correct samples after an anomaly; do not accept persistent divergence.
        let mut matching = 0;
        for _ in 0..3 {
            self.clock.sleep(RETRY_PAUSE);
            self.stats.post_length_resamples += 1;
            if self.certificate_length()? == expected {
                matching += 1;
                if matching == 2 {
                    return Ok(true);
                }
            } else {
                matching = 0;
                self.stats.post_length_anomalies += 1;
            }
        }
        Ok(false)
    }

    pub fn certificate(&mut self) -> Result<Vec<u8>, Error> {
        self.clear_error()?;
        for attempt in 0..3 {
            self.stats.certificate_attempts += 1;
            if attempt != 0 {
                self.clock.sleep(RETRY_PAUSE);
            }
            let result = (|| {
                let n = self.certificate_length()?;
                let confirm = self.certificate_length()?;
                if !(1..=1280).contains(&n) || confirm != n {
                    return Ok(None);
                }
                let mut cert = vec![0; n];
                // Continuous reads across certificate pages are permitted by the 2.0B spec.
                self.read(0x31, &mut cert)?;
                if !self.confirm_post_certificate_length(n)? || !valid_der_envelope(&cert) {
                    return Ok(None);
                }
                Ok(Some(cert))
            })();
            match result {
                Ok(Some(cert)) => {
                    self.check_error()?;
                    return Ok(cert);
                }
                Ok(None) => {}
                Err(error) => return Err(error),
            }
        }
        Err(Error::CertificateInconsistent)
    }

    pub fn sign(&mut self, challenge: &[u8]) -> Result<Vec<u8>, Error> {
        if challenge.is_empty() || challenge.len() > 128 {
            return Err(Error::InvalidChallenge(challenge.len()));
        }
        self.clear_error()?;
        let mut version = [0];
        self.read(0x00, &mut version)?;
        self.stats.device_version = Some(version[0]);
        // Version 0x03 identifies the 2.0B coprocessor. The spec says to set signature capacity
        // to 128 bytes before signing. This CPC200 reports 0xff from the error register and was
        // verified to return invalid RSA signatures if we write that capacity. Its existing
        // initialized-capacity path verifies without the write; keep this hardware-specific
        // exception limited to the observed version/error-register combination.
        let legacy = version[0] == 0x03 && self.stats.error_register == Some(0xff);
        self.stats.compatibility_profile = Some(if legacy { "cpc200-v03" } else { "2.0c" });
        self.write(0x20, &(challenge.len() as u16).to_be_bytes())?;
        self.write(0x21, challenge)?;
        if !legacy {
            self.write(0x11, &[0x00, 0x80])?;
            self.stats.capacity_initialized = true;
        }
        self.write(0x10, &[0x01])?;
        let deadline = self.clock.now() + SIGN_WAIT;
        self.clock.sleep(Duration::from_millis(100));
        loop {
            if self.clock.now() >= deadline {
                return Err(Error::SignTimeout);
            }
            let mut value = [0];
            match self.read_once(0x10, &mut value) {
                Err(error) if error.retryable() => {
                    self.stats.retries += 1;
                }
                Err(error) => return Err(Error::Transfer(error)),
                Ok(()) => {
                    let status = value[0];
                    self.stats.last_status = Some(status);
                    let results = (status >> 4) & 7;
                    if status & 0x80 != 0 || results > 1 || (results == 1 && status != 0x10) {
                        let mut error_code = [0];
                        let code = self.read(0x05, &mut error_code).ok().map(|_| error_code[0]);
                        return Err(Error::Status {
                            status,
                            error_code: code,
                        });
                    }
                    if status == 0x10 {
                        break;
                    }
                }
            }
            self.clock.sleep(RETRY_PAUSE);
        }
        let mut length = [0; 2];
        self.read(0x11, &mut length)?;
        let n = usize::from(u16::from_be_bytes(length));
        if n != 128 {
            return Err(Error::InvalidSignatureLength(n));
        }
        let mut signature = vec![0; n];
        self.read(0x12, &mut signature)?;
        self.check_error()?;
        Ok(signature)
    }
}

/// Structural framing only; the phone still verifies the certificate chain and signature.
fn valid_der_envelope(bytes: &[u8]) -> bool {
    if bytes.len() < 2 || bytes[0] != 0x30 {
        return false;
    }
    let (header, payload) = if bytes[1] < 128 {
        (2, bytes[1] as usize)
    } else {
        let count = (bytes[1] & 127) as usize;
        if count == 0 || count > 2 || bytes.len() < 2 + count || bytes[2] == 0 {
            return false;
        }
        let len = bytes[2..2 + count]
            .iter()
            .fold(0usize, |n, b| n * 256 + *b as usize);
        if len < 128 || (count == 2 && len < 256) {
            return false;
        }
        (2 + count, len)
    };
    payload > 0 && header + payload == bytes.len()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    #[derive(Default)]
    struct FakeClock(Duration);
    impl Clock for FakeClock {
        fn now(&self) -> Duration {
            self.0
        }
        fn sleep(&mut self, d: Duration) {
            self.0 += d;
        }
    }
    enum Step {
        Read(u8, Result<Vec<u8>, TransferError>),
        Write(u8, Vec<u8>, Result<(), TransferError>),
    }
    struct FakeBus {
        steps: VecDeque<Step>,
        forever_busy: bool,
    }
    impl FakeBus {
        fn new(steps: Vec<Step>) -> Self {
            Self {
                steps: steps.into(),
                forever_busy: false,
            }
        }
    }
    fn read(reg: u8, bytes: &[u8]) -> Step {
        Step::Read(reg, Ok(bytes.to_vec()))
    }
    fn write(reg: u8, bytes: &[u8]) -> Step {
        Step::Write(reg, bytes.to_vec(), Ok(()))
    }
    fn fault(op: &'static str, reg: u8, errno: i32) -> TransferError {
        TransferError {
            op,
            reg,
            len: 1,
            actual: -1,
            expected: if op == "read" { 2 } else { 1 },
            errno,
        }
    }
    impl Bus for FakeBus {
        fn read(&mut self, reg: u8, out: &mut [u8]) -> Result<(), TransferError> {
            assert!(
                out.iter().all(|v| *v == 0),
                "retry retained an earlier buffer"
            );
            if self.steps.is_empty() && self.forever_busy {
                assert_eq!(reg, 0x10);
                return Err(fault("read", reg, libc::ENXIO));
            }
            let Step::Read(expected, result) = self.steps.pop_front().expect("unexpected read")
            else {
                panic!("expected write, got read {reg:02x}")
            };
            assert_eq!(reg, expected);
            match result {
                Ok(bytes) => {
                    assert_eq!(out.len(), bytes.len());
                    out.copy_from_slice(&bytes);
                    Ok(())
                }
                Err(error) => {
                    out.fill(0xcc);
                    Err(error)
                }
            }
        }
        fn write(&mut self, reg: u8, bytes: &[u8]) -> Result<(), TransferError> {
            let Step::Write(expected, data, result) =
                self.steps.pop_front().expect("unexpected write")
            else {
                panic!("expected read, got write {reg:02x}")
            };
            assert_eq!(reg, expected);
            assert_eq!(bytes, data);
            result
        }
    }
    fn setup() -> Vec<Step> {
        vec![
            read(5, &[0]),
            read(0x00, &[0x05]),
            write(0x20, &[0, 20]),
            write(0x21, &[0x5a; 20]),
            write(0x11, &[0, 128]),
            write(0x10, &[1]),
        ]
    }
    fn driver(steps: Vec<Step>) -> Driver<FakeBus, FakeClock> {
        Driver::new(FakeBus::new(steps), FakeClock::default())
    }

    #[test]
    fn signing_initializes_capacity_and_busy_nacks_eventually_succeed_without_reopen() {
        let mut steps = setup();
        for _ in 0..3 {
            steps.push(Step::Read(0x10, Err(fault("read", 0x10, libc::ENXIO))));
        }
        steps.extend([
            read(0x10, &[0]),
            read(0x10, &[0x10]),
            read(0x11, &[0, 128]),
            read(0x12, &[0xab; 128]),
            read(5, &[0]),
        ]);
        let mut d = driver(steps);
        assert_eq!(d.sign(&[0x5a; 20]).unwrap(), vec![0xab; 128]);
        assert!(d.bus.steps.is_empty());
        assert_eq!(d.stats.busy_nacks, 3);
        assert_eq!(d.clock.now(), Duration::from_millis(120));
    }

    #[test]
    fn capacity_write_is_skipped_only_for_the_verified_cpc200_profile() {
        for (version, error_register) in [(3, 0xff), (5, 0xff), (3, 0), (5, 0)] {
            let legacy = version == 3 && error_register == 0xff;
            let mut steps = setup();
            steps[0] = read(5, &[error_register]);
            steps[1] = read(0, &[version]);
            if legacy {
                steps.remove(4);
            } // Signature-capacity write, not the trigger.
            steps.extend([
                read(0x10, &[0x10]),
                read(0x11, &[0, 128]),
                read(0x12, &[0xab; 128]),
                read(5, &[error_register]),
            ]);
            let mut d = driver(steps);
            assert_eq!(d.sign(&[0x5a; 20]).unwrap(), vec![0xab; 128]);
            assert_eq!(d.stats.capacity_initialized, !legacy);
            assert_eq!(
                d.stats.compatibility_profile,
                Some(if legacy { "cpc200-v03" } else { "2.0c" })
            );
            assert_eq!(d.stats.writes, if legacy { 3 } else { 4 });
            assert!(d.bus.steps.is_empty());
        }
    }

    #[test]
    fn bit_four_alone_does_not_mean_signature_success() {
        for status in [0x30, 0x90, 0xff, 0x11] {
            let mut steps = setup();
            steps.extend([read(0x10, &[status]), read(5, &[0x0b])]);
            let mut d = driver(steps);
            assert!(
                matches!(d.sign(&[0x5a; 20]), Err(Error::Status { status: s, error_code: Some(0x0b) }) if s == status)
            );
            assert!(d.bus.steps.is_empty());
        }
    }

    #[test]
    fn status_polling_has_a_wall_clock_deadline_even_when_every_read_nacks() {
        let mut d = driver(setup());
        d.bus.forever_busy = true;
        assert!(matches!(d.sign(&[0x5a; 20]), Err(Error::SignTimeout)));
        assert_eq!(d.clock.now(), SIGN_WAIT);
        assert!(d.stats.busy_nacks > 100);
    }

    #[test]
    fn ambiguous_trigger_failure_is_not_replayed() {
        for mut error in [
            fault("write", 0x10, REMOTE_IO),
            fault("write", 0x10, libc::EIO),
            fault("write", 0x10, 0),
        ] {
            if error.errno == 0 {
                error.actual = 0;
            }
            let mut steps = setup();
            steps.pop();
            steps.push(Step::Write(0x10, vec![1], Err(error)));
            let mut d = driver(steps);
            assert!(matches!(d.sign(&[0x5a; 20]), Err(Error::Transfer(e)) if e == error));
            assert!(d.bus.steps.is_empty());
            assert_eq!(d.stats.retries, 0);
        }
    }

    #[test]
    fn address_nack_on_trigger_can_be_retried_but_hard_status_fault_cannot() {
        let mut steps = setup();
        steps.pop();
        steps.extend([
            Step::Write(0x10, vec![1], Err(fault("write", 0x10, libc::ENXIO))),
            write(0x10, &[1]),
            Step::Read(0x10, Err(fault("read", 0x10, libc::ETIMEDOUT))),
        ]);
        let mut d = driver(steps);
        assert!(
            matches!(d.sign(&[0x5a; 20]), Err(Error::Transfer(e)) if e.errno == libc::ETIMEDOUT)
        );
        assert_eq!(d.stats.retries, 1);
        assert_eq!(d.stats.other_failures, 1);
        assert!(d.bus.steps.is_empty());
    }

    #[test]
    fn corrupted_length_is_reselected_and_confirmed_before_allocating_certificate() {
        let mut d = driver(vec![
            read(5, &[0xff]),
            read(0x30, &[0x9c, 0xb1]),
            read(0x30, &[3, 0xb1]),
            read(0x30, &[0, 4]),
            read(0x30, &[0, 4]),
            read(0x31, &[0x30, 2, 5, 0]),
            read(0x30, &[0, 4]),
            read(5, &[0xff]),
        ]);
        assert_eq!(d.certificate().unwrap(), vec![0x30, 2, 5, 0]);
        assert_eq!(d.stats.certificate_attempts, 2);
        assert!(d.bus.steps.is_empty());
    }

    #[test]
    fn certificate_data_and_post_read_length_must_both_be_consistent() {
        for (body, post) in [([0x30, 8, 5, 0], [0, 4]), ([0x30, 2, 5, 0], [0x9c, 0xb1])] {
            let mut steps = vec![read(5, &[0])];
            for _ in 0..3 {
                steps.extend([
                    read(0x30, &[0, 4]),
                    read(0x30, &[0, 4]),
                    read(0x31, &body),
                    read(0x30, &post),
                ]);
                if post != [0, 4] {
                    for _ in 0..3 {
                        steps.push(read(0x30, &post));
                    }
                }
            }
            let mut d = driver(steps);
            assert!(matches!(
                d.certificate(),
                Err(Error::CertificateInconsistent)
            ));
            assert!(d.bus.steps.is_empty());
            assert_eq!(d.stats.certificate_attempts, 3);
        }
    }

    #[test]
    fn post_certificate_register_quirk_recovers_only_after_two_matching_resamples() {
        let certificate = include_bytes!("../../mfi/tests/fixtures/mfi_cert.der");
        let length = (certificate.len() as u16).to_be_bytes();
        let bad = [0x9c, length[1]];
        let mut d = driver(vec![
            read(5, &[0xff]),
            read(0x30, &length),
            read(0x30, &length),
            read(0x31, certificate),
            read(0x30, &bad),
            read(0x30, &length),
            read(0x30, &length),
            read(5, &[0xff]),
        ]);
        assert_eq!(d.certificate().unwrap(), certificate);
        assert_eq!(d.stats.certificate_attempts, 1);
        assert_eq!(d.stats.post_length_anomalies, 1);
        assert_eq!(d.stats.post_length_resamples, 2);
        assert_eq!(d.stats.busy_nacks, 0);
        assert!(d.bus.steps.is_empty());
    }

    #[test]
    fn one_good_post_read_sample_does_not_hide_an_unstable_length() {
        let mut d = driver(vec![
            read(0x30, &[0x9c, 0xb1]),
            read(0x30, &[3, 0xb1]),
            read(0x30, &[0x9c, 0xb1]),
            read(0x30, &[3, 0xb1]),
        ]);
        assert!(!d.confirm_post_certificate_length(945).unwrap());
        assert_eq!(d.stats.post_length_resamples, 3);
        assert!(d.bus.steps.is_empty());
    }

    #[test]
    fn der_framing_rejects_truncation_nonminimal_and_indefinite_lengths() {
        for invalid in [
            &[0x30, 0x80, 0, 0][..],
            &[0x30, 0x81, 1, 0],
            &[0x30, 0x82, 0, 1, 0],
            &[0x30, 4, 0, 0],
            &[0x31, 2, 5, 0],
        ] {
            assert!(!valid_der_envelope(invalid));
        }
        assert!(valid_der_envelope(include_bytes!(
            "../../mfi/tests/fixtures/mfi_cert.der"
        )));
    }

    #[test]
    fn unexpected_signature_length_and_post_read_chip_errors_are_rejected() {
        let mut steps = setup();
        steps.extend([read(0x10, &[0x10]), read(0x11, &[1, 0])]);
        assert!(matches!(
            driver(steps).sign(&[0x5a; 20]),
            Err(Error::InvalidSignatureLength(256))
        ));
        let mut steps = setup();
        steps.extend([
            read(0x10, &[0x10]),
            read(0x11, &[0, 128]),
            read(0x12, &[0; 128]),
            read(5, &[6]),
        ]);
        assert!(matches!(
            driver(steps).sign(&[0x5a; 20]),
            Err(Error::ChipError(6))
        ));
    }
}
