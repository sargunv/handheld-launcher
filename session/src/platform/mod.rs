//! What the session needs from the platform it runs on: logging, windows and
//! audio output.

#[cfg(target_os = "android")]
mod aaudio;
#[cfg(target_os = "android")]
mod android;

#[cfg(target_os = "android")]
pub(crate) use aaudio::AAudioSink;
#[cfg(target_os = "android")]
pub(crate) use android::{AndroidWindow, init_logging};

/// Sends the session's logs where the platform shows them. The app's own
/// logger takes them elsewhere.
#[cfg(not(target_os = "android"))]
pub(crate) fn init_logging() {}
