//! What the session needs from the platform it runs on: logging, windows,
//! vsync, and video and audio output.

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
pub(crate) use headless::{PlatformWindow, VsyncClock, audio_sink, init_logging, video_output};

/// Presents to the window the app attaches, through an OpenGL ES context, or
/// a Vulkan device for Vulkan cores, that the session keeps while windows come
/// and go.
#[cfg(target_os = "android")]
pub(crate) fn video_output(lower: crate::Region) -> crate::VideoOutput {
    crate::VideoOutput::Window {
        window: None,
        width: 0,
        height: 0,
        lower,
    }
}
