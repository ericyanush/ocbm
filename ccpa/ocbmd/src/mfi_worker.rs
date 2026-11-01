//! Serial MFi work queue. Chip operations never execute on the USB dispatch thread.
use std::io::{self, Read, Write};
use std::os::unix::io::{AsRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::sync::{Arc, atomic::{AtomicU64, Ordering}, mpsc::{self, Receiver, SyncSender}};
use std::time::Instant;
struct Job { epoch: u64, payload: Vec<u8>, queued: Instant }
pub struct Reply { pub op: u8, pub epoch: u64, pub payload: Vec<u8>, pub queue_us: u128, pub chip_us: u128 }
pub struct MfiWorker {
    requests: SyncSender<Job>, replies: Receiver<Reply>, wake: UnixStream, epoch: Arc<AtomicU64>,
    thread: std::thread::JoinHandle<()>,
}
impl MfiWorker {
    pub fn start(mut execute: impl FnMut(&[u8]) -> Vec<u8> + Send + 'static) -> io::Result<Self> {
        let (requests, jobs) = mpsc::sync_channel::<Job>(8);
        let (out, replies) = mpsc::sync_channel(8);
        let (wake, writer) = UnixStream::pair()?;
        wake.set_nonblocking(true)?; writer.set_nonblocking(true)?;
        let epoch = Arc::new(AtomicU64::new(0));
        let current = epoch.clone();
        let thread = std::thread::Builder::new().name("ocbm-mfi".into()).spawn(move || {
            while let Ok(job) = jobs.recv() {
                if current.load(Ordering::Acquire) != job.epoch { continue; }
                let queue_us = job.queued.elapsed().as_micros();
                let began = Instant::now();
                let payload = execute(&job.payload);
                let chip_us = began.elapsed().as_micros();
                if current.load(Ordering::Acquire) != job.epoch { continue; }
                if out.send(Reply { op: job.payload.first().copied().unwrap_or(0), epoch: job.epoch, payload, queue_us, chip_us }).is_err() { return; }
                let mut socket = &writer;
                loop { match socket.write(&[1]) {
                    Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                    _ => break,
                }}
            }
        })?;
        Ok(Self { requests, replies, wake, epoch, thread })
    }
    pub fn invalidate(&self) { self.epoch.fetch_add(1, Ordering::AcqRel); }
    pub fn submit(&self, payload: &[u8]) -> bool {
        self.requests.try_send(Job { epoch: self.epoch.load(Ordering::Acquire), payload: payload.to_vec(), queued: Instant::now() }).is_ok()
    }
    pub fn wake_fd(&self) -> RawFd { self.wake.as_raw_fd() }
    pub fn stopped(&self) -> bool { self.thread.is_finished() }
    pub fn clear_wake(&self) {
        let mut socket = &self.wake; let mut bytes = [0u8; 64];
        loop { match socket.read(&mut bytes) {
            Ok(0) => break, Ok(_) => {},
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(_) => break,
        }}
    }
    pub fn replies(&self) -> Vec<Reply> {
        // At most eight queued plus the worker's next send: bound each drain pass.
        (0..16).filter_map(|_| self.replies.try_recv().ok())
            .filter(|r| r.epoch == self.epoch.load(Ordering::Acquire)).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;
    #[test]
    fn serializes_jobs_and_discards_old_session_signatures() {
        let (began, started) = mpsc::channel();
        let (release, wait) = mpsc::channel();
        let worker = MfiWorker::start(move |payload| {
            began.send(payload.to_vec()).unwrap();
            wait.recv().unwrap();
            payload.to_vec()
        }).unwrap();
        assert!(worker.submit(&[1]));
        assert_eq!(started.recv_timeout(Duration::from_secs(2)).unwrap(), vec![1]);
        assert!(worker.submit(&[2]));
        assert!(started.try_recv().is_err(), "second operation must wait for the first");
        worker.invalidate();
        assert!(worker.submit(&[3]));
        release.send(()).unwrap();
        // Queued old-session job 2 is never executed; in-flight job 1 cannot publish its reply.
        assert_eq!(started.recv_timeout(Duration::from_secs(2)).unwrap(), vec![3]);
        release.send(()).unwrap();
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            assert!(Instant::now() < deadline);
            worker.clear_wake();
            let replies = worker.replies();
            if !replies.is_empty() {
                assert_eq!(replies.len(), 1);
                assert_eq!(replies[0].payload, vec![3]);
                break;
            }
            std::thread::sleep(Duration::from_millis(1));
        }
    }
}
