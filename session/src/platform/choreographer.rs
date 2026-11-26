//! The display's vsync timing, from Choreographer on a thread of its own.

use std::ffi::{c_int, c_void};
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::pacing::{Vsync, VsyncEstimator};
use crate::platform::android::monotonic_nanos;

#[link(name = "android")]
unsafe extern "C" {
    fn ALooper_prepare(opts: c_int) -> *mut c_void;
    fn ALooper_acquire(looper: *mut c_void);
    fn ALooper_release(looper: *mut c_void);
    fn ALooper_wake(looper: *mut c_void);
    fn ALooper_pollOnce(
        timeout_millis: c_int,
        out_fd: *mut c_int,
        out_events: *mut c_int,
        out_data: *mut *mut c_void,
    ) -> c_int;
    fn AChoreographer_getInstance() -> *mut c_void;
    fn AChoreographer_postFrameCallback64(
        choreographer: *mut c_void,
        callback: unsafe extern "C" fn(i64, *mut c_void),
        data: *mut c_void,
    );
}

/// What the clock's thread shares with its owner.
#[derive(Default)]
struct Shared {
    estimator: Mutex<VsyncEstimator>,
    /// Whether to follow vsync. While off, the thread sleeps.
    running: AtomicBool,
    stop: AtomicBool,
}

/// Follows the display's vsync while running.
pub(crate) struct VsyncClock {
    shared: Arc<Shared>,
    /// The thread's looper, with a reference of ours, to wake it.
    looper: usize,
    thread: Option<JoinHandle<()>>,
}

impl VsyncClock {
    /// Starts the clock's thread, stopped, or returns `None` if
    /// Choreographer isn't available.
    pub(crate) fn new() -> Option<Self> {
        let shared = Arc::new(Shared::default());
        let (ready, looper) = mpsc::channel();
        let thread_shared = shared.clone();
        let thread = std::thread::Builder::new()
            .name("vsync".into())
            .spawn(move || follow(thread_shared, ready))
            .inspect_err(|e| log::warn!("can't start the vsync thread: {e}"))
            .ok()?;
        match looper.recv() {
            Ok(looper) => Some(Self {
                shared,
                looper,
                thread: Some(thread),
            }),
            Err(_) => {
                let _ = thread.join();
                log::warn!("no Choreographer, so frames are paced by a timer");
                None
            }
        }
    }

    /// Starts or stops following vsync.
    pub(crate) fn set_running(&self, running: bool) {
        self.shared.running.store(running, Ordering::Release);
        // SAFETY: we hold a reference to the looper.
        unsafe { ALooper_wake(self.looper as *mut c_void) };
    }

    /// The display's vsync timing, once known.
    pub(crate) fn vsync(&self) -> Option<Vsync> {
        self.shared
            .estimator
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .vsync()
    }
}

impl Drop for VsyncClock {
    fn drop(&mut self) {
        self.shared.stop.store(true, Ordering::Release);
        // SAFETY: we hold a reference to the looper, released last.
        unsafe { ALooper_wake(self.looper as *mut c_void) };
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
        unsafe { ALooper_release(self.looper as *mut c_void) };
    }
}

/// The clock thread's state, which frame callbacks receive.
struct Follower {
    shared: Arc<Shared>,
    choreographer: *mut c_void,
    /// Whether a frame callback is posted.
    posted: bool,
}

impl Follower {
    fn post(&mut self) {
        self.posted = true;
        // SAFETY: the choreographer is this thread's, and `self` outlives
        // the callback, which runs on this thread while it polls.
        unsafe {
            AChoreographer_postFrameCallback64(
                self.choreographer,
                on_frame,
                ptr::from_mut(self).cast(),
            );
        }
    }
}

/// The clock's thread: posts a frame callback every vsync while running.
fn follow(shared: Arc<Shared>, ready: mpsc::Sender<usize>) {
    // SAFETY: called on this new thread, which has no looper yet.
    let looper = unsafe { ALooper_prepare(0) };
    // SAFETY: the instance is for this thread, which has a looper.
    let choreographer = unsafe { AChoreographer_getInstance() };
    if looper.is_null() || choreographer.is_null() {
        return;
    }
    // SAFETY: `looper` is this thread's; the reference is the owner's.
    unsafe { ALooper_acquire(looper) };
    if ready.send(looper as usize).is_err() {
        unsafe { ALooper_release(looper) };
        return;
    }
    let mut follower = Box::new(Follower {
        shared,
        choreographer,
        posted: false,
    });
    while !follower.shared.stop.load(Ordering::Acquire) {
        if follower.shared.running.load(Ordering::Acquire) && !follower.posted {
            follower.post();
        }
        // SAFETY: the looper is this thread's; null outputs are allowed.
        unsafe { ALooper_pollOnce(-1, ptr::null_mut(), ptr::null_mut(), ptr::null_mut()) };
    }
    // A posted callback never runs: nothing polls the looper again.
}

unsafe extern "C" fn on_frame(frame_time_nanos: i64, data: *mut c_void) {
    // SAFETY: `data` is the thread's `Follower`, which outlives its polling,
    // and nothing else uses it while this runs.
    let follower = unsafe { &mut *data.cast::<Follower>() };
    follower.posted = false;
    if !follower.shared.running.load(Ordering::Acquire) {
        return;
    }
    let now = Instant::now();
    let age =
        Duration::from_nanos(monotonic_nanos().saturating_sub(frame_time_nanos).max(0) as u64);
    follower
        .shared
        .estimator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .tick(now.checked_sub(age).unwrap_or(now));
    follower.post();
}
