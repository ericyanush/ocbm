//! Isolate gadget drivers that block read() despite O_NONBLOCK from USB output/management.
use std::fs::File;
use std::io::{self, Read, Write};
use std::os::unix::io::{AsRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::sync::mpsc::{self, Receiver};
use std::time::{Duration, Instant};

pub struct ReadEvent {
    pub result: io::Result<Vec<u8>>,
    pub read_duration: Duration,
    pub completed: Instant,
}

pub struct AccessoryReader {
    events: Receiver<ReadEvent>,
    wake: UnixStream,
}

impl AccessoryReader {
    pub fn start(mut file: File, chunk_size: usize) -> io::Result<Self> {
        // Bound retained data to 32 chunks, plus the worker's current read. Never drop USB bytes.
        let (tx, events) = mpsc::sync_channel(32);
        let (wake, writer) = UnixStream::pair()?;
        wake.set_nonblocking(true)?;
        writer.set_nonblocking(true)?;
        std::thread::Builder::new().name("ocbm-accessory-read".into()).spawn(move || {
            let mut bytes = vec![0u8; chunk_size];
            loop {
                let mut fd = libc::pollfd { fd: file.as_raw_fd(), events: libc::POLLIN, revents: 0 };
                let ready = unsafe { libc::poll(&mut fd, 1, 250) };
                if ready == 0 { continue; }
                if ready < 0 && io::Error::last_os_error().kind() == io::ErrorKind::Interrupted { continue; }
                let began = Instant::now();
                let result = if ready < 0 { Err(io::Error::last_os_error()) } else if
                    fd.revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 && fd.revents & libc::POLLIN == 0 {
                    Err(io::Error::new(io::ErrorKind::BrokenPipe, "accessory poll hangup/error"))
                } else {
                    file.read(&mut bytes).map(|n| bytes[..n].to_vec())
                };
                if let Err(ref e) = result {
                    if e.kind() == io::ErrorKind::Interrupted { continue; }
                    if e.kind() == io::ErrorKind::WouldBlock {
                        std::thread::sleep(Duration::from_millis(1));
                        continue;
                    }
                }
                let terminal = result.as_ref().map_or(true, Vec::is_empty);
                let event = ReadEvent { result, read_duration: began.elapsed(), completed: Instant::now() };
                // Only this worker can block on the queue. Main-thread USB output remains live.
                if tx.send(event).is_err() { return; }
                let mut socket = &writer;
                loop {
                    match socket.write(&[1]) {
                        Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                        _ => break, // A full notification socket is already readable.
                    }
                }
                if terminal { return; }
            }
        })?;
        Ok(Self { events, wake })
    }

    pub fn wake_fd(&self) -> RawFd { self.wake.as_raw_fd() }
    pub fn clear_wake(&self) {
        let mut socket = &self.wake;
        let mut bytes = [0u8; 256];
        loop {
            match socket.read(&mut bytes) {
                Ok(0) => break,
                Ok(_) => {},
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(_) => break,
            }
        }
    }
    pub fn next(&self) -> Option<ReadEvent> {
        match self.events.try_recv() {
            Ok(event) => Some(event),
            Err(mpsc::TryRecvError::Empty) => None,
            Err(mpsc::TryRecvError::Disconnected) => Some(ReadEvent {
                result: Err(io::Error::new(io::ErrorKind::BrokenPipe, "accessory reader stopped")),
                read_duration: Duration::ZERO, completed: Instant::now(),
            }),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::io::{FromRawFd, IntoRawFd};
    #[test]
    fn wakes_delivers_partial_reads_in_order_and_reports_eof() {
        let (read, mut peer) = UnixStream::pair().unwrap();
        let file = unsafe { File::from_raw_fd(read.into_raw_fd()) };
        let reader = AccessoryReader::start(file, 3).unwrap();
        peer.write_all(b"abcdefgh").unwrap();
        peer.shutdown(std::net::Shutdown::Write).unwrap();
        let deadline = Instant::now() + Duration::from_secs(2);
        let mut received = Vec::new();
        loop {
            assert!(Instant::now() < deadline, "reader failed to deliver EOF");
            let mut fd = libc::pollfd { fd: reader.wake_fd(), events: libc::POLLIN, revents: 0 };
            assert!(unsafe { libc::poll(&mut fd, 1, 1000) } > 0);
            reader.clear_wake();
            while let Some(event) = reader.next() {
                let chunk = event.result.unwrap();
                if chunk.is_empty() { assert_eq!(received, b"abcdefgh"); return; }
                assert!(chunk.len() <= 3);
                received.extend(chunk);
            }
        }
    }
}
