//! Hold one process-wide Bluetooth owner, including while the controller is being initialized.
use std::{fs::{File, OpenOptions}, io::{self, Write}, os::fd::AsRawFd, path::Path};

pub fn acquire(path: &Path) -> io::Result<File> {
    // Rust opens files CLOEXEC. Keep the inode in place on exit: unlinking a locked file allows
    // another process to lock a different inode while an earlier owner is still shutting down.
    let mut file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(path)?;
    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return Err(io::Error::last_os_error());
    }
    file.set_len(0)?;
    writeln!(file, "{}", std::process::id())?;
    Ok(file)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn duplicate_owner_is_refused_and_exit_releases_the_same_inode() {
        let path = std::env::temp_dir().join(format!("ocbm-btd-lock-test-{}", std::process::id()));
        let owner = acquire(&path).unwrap();
        assert_eq!(acquire(&path).unwrap_err().kind(), io::ErrorKind::WouldBlock);
        assert_eq!(std::fs::read_to_string(&path).unwrap().trim(), std::process::id().to_string());
        drop(owner);
        let replacement = acquire(&path).unwrap();
        drop(replacement);
        std::fs::remove_file(path).unwrap();
    }
}
