//! Android's logging, windows and video output.

use std::ffi::c_void;
use std::mem::ManuallyDrop;
use std::ptr::NonNull;

use ndk::native_window::NativeWindow as AndroidWindow;
use raw_window_handle::{
    AndroidDisplayHandle, AndroidNdkWindowHandle, RawDisplayHandle, RawWindowHandle,
};

use crate::{NativeWindow, Region, SessionError, VideoOutput};

/// Now on `CLOCK_MONOTONIC`, the clock of Choreographer's and AAudio's
/// timestamps, in nanoseconds.
pub(crate) fn monotonic_nanos() -> i64 {
    let mut time = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    // SAFETY: `time` is a valid `timespec`.
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut time) };
    time.tv_sec * 1_000_000_000 + time.tv_nsec
}

/// Sends the session's logs, and its cores', to logcat.
pub(crate) fn init_logging() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("HandheldSession")
            .with_max_level(log::LevelFilter::Info),
    );
}

/// Presents to the window the app attaches, through an OpenGL ES context, or
/// a Vulkan device for Vulkan cores, that the session keeps while windows come
/// and go.
pub(crate) fn video_output(lower: Region) -> VideoOutput {
    VideoOutput::Window {
        window: None,
        lower,
    }
}

/// One reference to an `ANativeWindow`, released when dropped.
pub(crate) struct PlatformWindow(AndroidWindow);

impl PlatformWindow {
    /// Takes over the reference to the `ANativeWindow` at `window`, as
    /// [`GameSession::attach_window`](crate::GameSession::attach_window) documents.
    pub(crate) fn adopt(window: u64) -> Result<Self, SessionError> {
        let window =
            NonNull::new(window as usize as *mut _).ok_or(SessionError::UnsupportedWindow)?;
        // SAFETY: the caller hands over a reference to an `ANativeWindow`.
        Ok(Self(unsafe { AndroidWindow::from_ptr(window) }))
    }

    pub(crate) fn native(&self) -> NativeWindow {
        // SAFETY: the reference keeps the window alive while `self` lives,
        // and the session detaches it before dropping `self`.
        unsafe {
            NativeWindow::new(
                RawDisplayHandle::Android(AndroidDisplayHandle::new()),
                RawWindowHandle::AndroidNdk(AndroidNdkWindowHandle::new(self.0.ptr().cast())),
            )
        }
    }
}

/// `NativeWindows.acquire(Surface)` in the Kotlin bindings: the
/// `ANativeWindow` behind a `Surface`, with a reference for the caller, as a
/// pointer for [`GameSession::attach_window`](crate::GameSession::attach_window),
/// or 0 if the surface has none.
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
    let window = unsafe { AndroidWindow::from_surface(env.cast(), surface.cast()) };
    // The reference goes to the caller.
    window.map_or(0, |window| {
        ManuallyDrop::new(window).ptr().as_ptr() as usize as i64
    })
}
