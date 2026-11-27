//! Android's logging and windows.

use std::ffi::c_void;
use std::ptr::NonNull;

use crate::{NativeWindow, SessionError, WindowHandle};

#[link(name = "android")]
unsafe extern "C" {
    fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut c_void;
    fn ANativeWindow_release(window: *mut c_void);
}

unsafe extern "C" {
    fn clock_gettime(clock: i32, time: *mut Timespec) -> i32;
}

/// `struct timespec` on the 64-bit ABIs the session builds for.
#[repr(C)]
struct Timespec {
    seconds: i64,
    nanos: i64,
}

const CLOCK_MONOTONIC: i32 = 1;

/// Now on `CLOCK_MONOTONIC`, the clock of Choreographer's and AAudio's
/// timestamps, in nanoseconds.
pub(crate) fn monotonic_nanos() -> i64 {
    let mut time = Timespec {
        seconds: 0,
        nanos: 0,
    };
    // SAFETY: `time` is a valid `timespec` on 64-bit Android.
    unsafe { clock_gettime(CLOCK_MONOTONIC, &mut time) };
    time.seconds * 1_000_000_000 + time.nanos
}

/// Sends the session's logs, and its cores', to logcat.
pub(crate) fn init_logging() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("HandheldSession")
            .with_max_level(log::LevelFilter::Info),
    );
}

/// One reference to an `ANativeWindow`, released when dropped.
pub(crate) struct PlatformWindow(NonNull<c_void>);

// SAFETY: an `ANativeWindow` reference may be used and released from any
// thread.
unsafe impl Send for PlatformWindow {}

impl PlatformWindow {
    /// Takes over the reference that `handle` hands over, as
    /// [`WindowHandle::Android`] documents.
    pub(crate) fn adopt(handle: WindowHandle) -> Result<Self, SessionError> {
        let WindowHandle::Android { window } = handle;
        NonNull::new(window as usize as *mut c_void)
            .map(Self)
            .ok_or(SessionError::UnsupportedWindow)
    }

    pub(crate) fn native(&self) -> NativeWindow {
        // SAFETY: the reference keeps the window alive while `self` lives,
        // and the session detaches it before dropping `self`.
        unsafe { NativeWindow::android(self.0) }
    }
}

impl Drop for PlatformWindow {
    fn drop(&mut self) {
        // SAFETY: `self` holds one reference.
        unsafe { ANativeWindow_release(self.0.as_ptr()) };
    }
}

/// `NativeWindows.acquire(Surface)` in the Kotlin bindings: the
/// `ANativeWindow` behind a `Surface`, with a reference for the caller, as a
/// pointer for `WindowHandle.Android`, or 0 if the surface has none.
///
/// # Safety
///
/// Called by the JVM, with `surface` an `android.view.Surface`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_sargunv_handheld_session_NativeWindows_acquire(
    env: *mut c_void,
    _class: *mut c_void,
    surface: *mut c_void,
) -> i64 {
    // SAFETY: the JVM passes its environment and a Surface reference.
    unsafe { ANativeWindow_fromSurface(env, surface) as usize as i64 }
}
