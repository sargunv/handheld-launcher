//! What the session needs from the platform it runs on: logging, windows,
//! vsync and audio output.

#[cfg(target_os = "android")]
mod aaudio;
#[cfg(target_os = "android")]
mod android;
#[cfg(target_os = "android")]
mod choreographer;
#[cfg(not(target_os = "android"))]
mod headless;

#[cfg(target_os = "android")]
pub(crate) use aaudio::audio_sink;
#[cfg(target_os = "android")]
pub(crate) use android::{PlatformWindow, init_logging};
#[cfg(target_os = "android")]
pub(crate) use choreographer::VsyncClock;
#[cfg(not(target_os = "android"))]
pub(crate) use headless::{PlatformWindow, VsyncClock, audio_sink, init_logging};
