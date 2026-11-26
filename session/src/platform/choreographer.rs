//! The display's vsync timing, from Choreographer on a thread of its own.

use std::ffi::{c_int, c_void};
use std::ptr;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock, mpsc};
use std::time::{Duration, Instant};

use crate::pacing::{Vsync, VsyncEstimator};
use crate::platform::android::monotonic_nanos;

#[link(name = "android")]
unsafe extern "C" {
    fn ALooper_prepare(opts: c_int) -> *mut c_void;
    fn ALooper_acquire(looper: *mut c_void);
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

/// The clock's thread and what it shares with the sessions.
#[derive(Default)]
struct Shared {
    estimator: Mutex<VsyncEstimator>,
    /// How many sessions follow vsync. While none do, the thread sleeps.
    running: AtomicUsize,
    /// The thread's looper, with a reference of ours that's never released,
    /// to wake it.
    looper: AtomicUsize,
}

/// The process's one vsync thread, or `None` if Choreographer isn't
/// available. One thread serves every session, since Choreographer keeps a
/// thread's instance until the process ends.
static CLOCK: OnceLock<Option<Arc<Shared>>> = OnceLock::new();

fn start() -> Option<Arc<Shared>> {
    let shared = Arc::new(Shared::default());
    let (ready, looper) = mpsc::channel();
    let thread_shared = shared.clone();
    std::thread::Builder::new()
        .name("vsync".into())
        .spawn(move || follow(thread_shared, ready))
        .inspect_err(|e| log::warn!("can't start the vsync thread: {e}"))
        .ok()?;
    match looper.recv() {
        Ok(looper) => {
            shared.looper.store(looper, Ordering::Release);
            Some(shared)
        }
        Err(_) => {
            log::warn!("no Choreographer, so frames are paced by a timer");
            None
        }
    }
}

/// A session's hold on the vsync thread, which follows vsync while any
/// session's clock runs.
pub(crate) struct VsyncClock {
    shared: Arc<Shared>,
    running: bool,
}

impl VsyncClock {
    /// A stopped clock, or `None` if Choreographer isn't available.
    pub(crate) fn new() -> Option<Self> {
        let shared = CLOCK.get_or_init(start).clone()?;
        Some(Self {
            shared,
            running: false,
        })
    }

    /// Starts or stops following vsync.
    pub(crate) fn set_running(&mut self, running: bool) {
        if self.running == running {
            return;
        }
        self.running = running;
        if running {
            self.shared.running.fetch_add(1, Ordering::AcqRel);
        } else {
            self.shared.running.fetch_sub(1, Ordering::AcqRel);
        }
        // SAFETY: the looper's reference is never released.
        unsafe { ALooper_wake(self.shared.looper.load(Ordering::Acquire) as *mut c_void) };
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
        self.set_running(false);
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
    // SAFETY: `looper` is this thread's; the reference is the clock's.
    unsafe { ALooper_acquire(looper) };
    if ready.send(looper as usize).is_err() {
        return;
    }
    let mut follower = Box::new(Follower {
        shared,
        choreographer,
        posted: false,
    });
    loop {
        if follower.shared.running.load(Ordering::Acquire) > 0 && !follower.posted {
            follower.post();
        }
        // SAFETY: the looper is this thread's; null outputs are allowed.
        unsafe { ALooper_pollOnce(-1, ptr::null_mut(), ptr::null_mut(), ptr::null_mut()) };
    }
}

unsafe extern "C" fn on_frame(frame_time_nanos: i64, data: *mut c_void) {
    // SAFETY: `data` is the thread's `Follower`, which outlives its polling,
    // and nothing else uses it while this runs.
    let follower = unsafe { &mut *data.cast::<Follower>() };
    follower.posted = false;
    if follower.shared.running.load(Ordering::Acquire) == 0 {
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
