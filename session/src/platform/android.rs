//! Android's logging and windows.

use std::ffi::c_void;
use std::ptr::NonNull;

use crate::NativeWindow;

#[link(name = "android")]
unsafe extern "C" {
    fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut c_void;
    fn ANativeWindow_release(window: *mut c_void);
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
pub(crate) struct AndroidWindow(NonNull<c_void>);

impl AndroidWindow {
    /// Takes over the reference that `pointer` stands for, or returns `None`
    /// for a null pointer.
    ///
    /// # Safety
    ///
    /// `pointer` is null or an `ANativeWindow` the caller holds a reference
    /// to, which it gives up.
    pub(crate) unsafe fn adopt(pointer: u64) -> Option<Self> {
        NonNull::new(pointer as usize as *mut c_void).map(Self)
    }

    pub(crate) fn native(&self) -> NativeWindow {
        // SAFETY: the reference keeps the window alive while `self` lives,
        // and the session detaches it before dropping `self`.
        unsafe { NativeWindow::android(self.0) }
    }
}

impl Drop for AndroidWindow {
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
