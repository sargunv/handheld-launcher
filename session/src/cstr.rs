//! C strings to and from Rust's, for the libretro and rcheevos APIs.

use std::ffi::{CStr, CString, c_char};
use std::os::unix::ffi::OsStrExt;
use std::path::Path;

/// A C string as an owned string, with invalid UTF-8 replaced, or `None` for
/// null.
///
/// # Safety
///
/// `s` is null or a valid C string.
pub(crate) unsafe fn opt_string(s: *const c_char) -> Option<String> {
    // SAFETY: forwarded from the caller.
    (!s.is_null()).then(|| unsafe { CStr::from_ptr(s) }.to_string_lossy().into_owned())
}

/// As [`opt_string`], but empty for null.
///
/// # Safety
///
/// `s` is null or a valid C string.
pub(crate) unsafe fn string(s: *const c_char) -> String {
    // SAFETY: forwarded from the caller.
    unsafe { opt_string(s) }.unwrap_or_default()
}

/// `path` as a C string. No file's path holds a NUL byte, so one that does
/// becomes the empty path, which names nothing.
pub(crate) fn path_cstring(path: &Path) -> CString {
    CString::new(path.as_os_str().as_bytes()).unwrap_or_else(|_| {
        log::warn!("path {} contains a NUL byte", path.display());
        CString::default()
    })
}
