//! Writing save files so that a crash, a full disk or a mismatched file never
//! costs the player a save.

use std::ffi::OsString;
use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread::JoinHandle;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// Writes `data` to a temporary file beside `path`, flushes it to disk, and
/// renames it over `path`, so that a crash leaves either the old file or the
/// new one.
pub(crate) fn write_atomically(path: &Path, data: &[u8]) -> io::Result<()> {
    let dir = parent_dir(path);
    fs::create_dir_all(dir)?;
    let name = path
        .file_name()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "path has no file name"))?;
    let mut tmp_name = OsString::from(".");
    tmp_name.push(name);
    tmp_name.push(format!(".{}.tmp", std::process::id()));
    let tmp = dir.join(tmp_name);
    let result = (|| {
        let mut file = fs::File::create(&tmp)?;
        file.write_all(data)?;
        file.sync_all()?;
        fs::rename(&tmp, path)?;
        // Persist the rename itself.
        fs::File::open(dir)?.sync_all()
    })();
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

/// Writes `data`, a save file's contents, to a new file beside `path` named
/// `<name>.<unix time>.bak`, and returns its path.
fn back_up(path: &Path, data: &[u8]) -> io::Result<PathBuf> {
    let name = path
        .file_name()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "path has no file name"))?;
    let time = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    let dir = parent_dir(path);
    let backup = (0..)
        .map(|n| {
            let mut backup = name.to_owned();
            match n {
                0 => backup.push(format!(".{time}.bak")),
                n => backup.push(format!(".{time}-{n}.bak")),
            }
            dir.join(backup)
        })
        .find(|backup| !backup.exists())
        .expect("an unused name");
    write_atomically(&backup, data)?;
    Ok(backup)
}

fn parent_dir(path: &Path) -> &Path {
    path.parent()
        .filter(|d| !d.as_os_str().is_empty())
        .unwrap_or(Path::new("."))
}

/// How often a running session compares save memory with its file.
const CHECK_INTERVAL: Duration = Duration::from_secs(2);

/// Keeps the core's save memory in its file: the session's thread compares
/// save memory with what it last wrote, and a writer thread writes the
/// changes.
pub(crate) struct SaveRam {
    path: PathBuf,
    writer: SaveWriter,
    /// Save memory as last handed to the writer, or as loaded.
    written: Vec<u8>,
    /// Whether the file needs writing even though save memory hasn't
    /// changed: it had another size, and a backup now holds it.
    rewrite: bool,
    /// When [`SaveRam::write_if_due`] next compares save memory.
    next_check: Instant,
    /// Save memory as [`SaveRam::back_up`] last copied it.
    backed_up: Option<Vec<u8>>,
}

impl SaveRam {
    /// Fills the core's save memory from the file at `path`, as a frontend
    /// does right after loading content, to keep the file up to date from
    /// then on.
    ///
    /// A missing file leaves save memory as the core made it. A file of
    /// another size fills what fits, but writing save memory back would lose
    /// the rest, so the file is copied to a backup beside it first, and
    /// loading fails if that copy can't be made.
    pub(crate) fn load(path: &Path, memory: &mut [u8]) -> io::Result<Self> {
        let rewrite = match fs::read(path) {
            Ok(data) => {
                let mismatched = data.len() != memory.len();
                if mismatched {
                    let backup = back_up(path, &data)?;
                    log::warn!(
                        "save file {} has {} bytes, but the core's save memory has {}; kept a copy at {}",
                        path.display(),
                        data.len(),
                        memory.len(),
                        backup.display()
                    );
                }
                let n = data.len().min(memory.len());
                memory[..n].copy_from_slice(&data[..n]);
                log::info!("loaded save memory from {}", path.display());
                mismatched
            }
            Err(e) if e.kind() == io::ErrorKind::NotFound => false,
            Err(e) => return Err(e),
        };
        Ok(Self {
            path: path.to_owned(),
            written: memory.to_vec(),
            writer: SaveWriter::new(path.to_owned())?,
            rewrite,
            next_check: Instant::now() + CHECK_INTERVAL,
            backed_up: None,
        })
    }

    /// Copies save memory to a backup beside its file before something
    /// replaces all of it, as loading a state does, unless the last backup
    /// holds the same bytes.
    pub(crate) fn back_up(&mut self, memory: &[u8]) -> io::Result<()> {
        if self.backed_up.as_deref() != Some(memory) {
            back_up(&self.path, memory)?;
            self.backed_up = Some(memory.to_vec());
        }
        Ok(())
    }

    /// Hands save memory to the writer if it changed since the last write,
    /// or the file needs it anyway.
    fn write_if_changed(&mut self, memory: &[u8]) {
        if self.rewrite || self.writer.failed() || memory != self.written.as_slice() {
            self.rewrite = false;
            self.written.clear();
            self.written.extend_from_slice(memory);
            self.writer.write(memory.to_vec());
        }
    }

    /// Calls [`SaveRam::write_if_changed`] every [`CHECK_INTERVAL`].
    pub(crate) fn write_if_due(&mut self, memory: &[u8]) {
        let now = Instant::now();
        if now >= self.next_check {
            self.next_check = now + CHECK_INTERVAL;
            self.write_if_changed(memory);
        }
    }

    /// Writes save memory if it changed, and waits until every write so far
    /// is on disk.
    pub(crate) fn flush(&mut self, memory: &[u8]) -> io::Result<()> {
        self.write_if_changed(memory);
        self.writer.wait()
    }
}

/// A thread that writes one save file, so that waiting for the disk never
/// holds up the thread that hands it data.
struct SaveWriter {
    jobs: Option<Sender<Job>>,
    /// Whether the last write failed, so the caller writes again.
    failed: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

enum Job {
    Write(Vec<u8>),
    Wait(Sender<io::Result<()>>),
}

impl SaveWriter {
    fn new(path: PathBuf) -> io::Result<Self> {
        let (jobs, receiver) = mpsc::channel();
        let failed = Arc::new(AtomicBool::new(false));
        let thread_failed = failed.clone();
        let thread = std::thread::Builder::new()
            .name("save-writer".into())
            .spawn(move || write_jobs(&path, &receiver, &thread_failed))?;
        Ok(Self {
            jobs: Some(jobs),
            failed,
            thread: Some(thread),
        })
    }

    /// Writes `data` over the file, after any writes before it.
    fn write(&self, data: Vec<u8>) {
        self.send(Job::Write(data));
    }

    /// Whether the last write failed.
    fn failed(&self) -> bool {
        self.failed.load(Ordering::Acquire)
    }

    /// Waits for every write so far, and returns the last one's error, if it
    /// failed.
    fn wait(&self) -> io::Result<()> {
        let (reply, result) = mpsc::channel();
        self.send(Job::Wait(reply));
        result
            .recv()
            .unwrap_or_else(|_| Err(io::Error::other("the save writer stopped")))
    }

    fn send(&self, job: Job) {
        if let Some(jobs) = &self.jobs {
            // The thread only ends when `jobs` drops.
            let _ = jobs.send(job);
        }
    }
}

impl Drop for SaveWriter {
    fn drop(&mut self) {
        self.jobs = None;
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn write_jobs(path: &Path, jobs: &Receiver<Job>, failed: &AtomicBool) {
    let mut error: Option<io::Error> = None;
    let mut next = jobs.recv().ok();
    while let Some(job) = next.take() {
        match job {
            Job::Write(mut data) => {
                // Only the newest of the writes queued up matters.
                loop {
                    match jobs.try_recv() {
                        Ok(Job::Write(newer)) => data = newer,
                        Ok(other) => {
                            next = Some(other);
                            break;
                        }
                        Err(_) => break,
                    }
                }
                match write_atomically(path, &data) {
                    Ok(()) => {
                        log::info!("wrote save memory to {}", path.display());
                        failed.store(false, Ordering::Release);
                        error = None;
                    }
                    Err(e) => {
                        if !failed.swap(true, Ordering::AcqRel) {
                            log::error!("can't write save memory to {}: {e}", path.display());
                        }
                        error = Some(e);
                    }
                }
            }
            Job::Wait(reply) => {
                let result = match &error {
                    Some(e) => Err(io::Error::new(e.kind(), e.to_string())),
                    None => Ok(()),
                };
                let _ = reply.send(result);
            }
        }
        if next.is_none() {
            next = jobs.recv().ok();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(dir: &Path) -> Vec<String> {
        let mut names: Vec<_> = fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .collect();
        names.sort();
        names
    }

    #[test]
    fn writes_atomically_and_replaces() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("nested").join("save.srm");
        write_atomically(&path, b"one").unwrap();
        write_atomically(&path, b"two").unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"two");
        assert_eq!(names(path.parent().unwrap()), ["save.srm"]);
    }

    #[test]
    fn backups_in_the_same_second_keep_their_own_contents() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("game.srm");
        let first = back_up(&path, b"one").unwrap();
        let second = back_up(&path, b"two").unwrap();
        assert_ne!(first, second);
        assert_eq!(fs::read(&first).unwrap(), b"one");
        assert_eq!(fs::read(&second).unwrap(), b"two");
    }

    #[test]
    fn the_writer_writes_in_order_and_reports_failures() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("game.srm");
        let writer = SaveWriter::new(path.clone()).unwrap();
        writer.write(b"one".to_vec());
        writer.write(b"two".to_vec());
        writer.wait().unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"two");
        assert!(!writer.failed());

        // A directory where the file goes can't be replaced by one.
        let blocked = dir.path().join("blocked.srm");
        fs::create_dir_all(blocked.join("inside")).unwrap();
        let writer = SaveWriter::new(blocked.clone()).unwrap();
        writer.write(b"one".to_vec());
        assert!(writer.wait().is_err());
        assert!(writer.failed());
        fs::remove_dir_all(&blocked).unwrap();
        writer.write(b"two".to_vec());
        writer.wait().unwrap();
        assert!(!writer.failed());
        assert_eq!(fs::read(&blocked).unwrap(), b"two");
    }
}
