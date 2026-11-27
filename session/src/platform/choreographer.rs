//! The display's vsync timing, from Choreographer on a thread of its own.

use std::ffi::c_void;
use std::ptr;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock, mpsc};
use std::time::{Duration, Instant};

use ndk::looper::{ForeignLooper, ThreadLooper};
use ndk_sys::{AChoreographer, AChoreographer_getInstance, AChoreographer_postFrameCallback64};

use crate::pacing::{Vsync, VsyncEstimator};
use crate::platform::android::monotonic_nanos;

/// The clock's thread and what it shares with the sessions.
struct Shared {
    estimator: Mutex<VsyncEstimator>,
    /// How many sessions follow vsync. While none do, the thread sleeps.
    running: AtomicUsize,
    /// The thread's looper, to wake it.
    looper: ForeignLooper,
}

/// The process's one vsync thread, or `None` if Choreographer isn't
/// available. One thread serves every session, since Choreographer keeps a
/// thread's instance until the process ends.
static CLOCK: OnceLock<Option<Arc<Shared>>> = OnceLock::new();

fn start() -> Option<Arc<Shared>> {
    let (ready, shared) = mpsc::channel();
    std::thread::Builder::new()
        .name("vsync".into())
        .spawn(move || follow(ready))
        .inspect_err(|e| log::warn!("can't start the vsync thread: {e}"))
        .ok()?;
    shared
        .recv()
        .inspect_err(|_| log::warn!("no Choreographer, so frames are paced by a timer"))
        .ok()
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
        self.shared.looper.wake();
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
    choreographer: *mut AChoreographer,
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
                Some(on_frame),
                ptr::from_mut(self).cast(),
            );
        }
    }
}

/// The clock's thread: posts a frame callback every vsync while running.
fn follow(ready: mpsc::Sender<Arc<Shared>>) {
    let looper = ThreadLooper::prepare();
    // SAFETY: the instance is for this thread, which has a looper.
    let choreographer = unsafe { AChoreographer_getInstance() };
    if choreographer.is_null() {
        return;
    }
    let shared = Arc::new(Shared {
        estimator: Mutex::default(),
        running: AtomicUsize::new(0),
        looper: looper.as_foreign().clone(),
    });
    if ready.send(shared.clone()).is_err() {
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
        let _ = looper.poll_once();
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
