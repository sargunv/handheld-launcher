//! The game session: hosts libretro cores and owns their video, audio and
//! input. The Kotlin app drives it through UniFFI.
//!
//! A [`Session`] loads a core and its content and runs it a frame at a time.
//! The platform layer plugs in through [`VideoSink`] or a [`NativeWindow`]
//! for video, [`AudioSink`] for audio, and [`InputState`] for controllers.
//!
//! [`GameSession`] is what the app uses: a session on a thread of its own,
//! with the platform's window and audio output.

mod audio;
mod core;
mod ffi;
mod gl;
mod handle;
mod host;
mod input;
mod options;
mod pacing;
mod platform;
mod save;
mod session;
mod video;
mod vulkan;

pub use crate::audio::{AudioSink, BufferStatus, NullAudioSink};
pub use crate::core::CoreLoadError;
pub use crate::gl::{GlError, NativeWindow};
pub use crate::handle::{
    GameSession, SessionInfo, SessionListener, SessionOptions, VideoMode, WindowHandle,
};
pub use crate::input::{Button, InputState, MAX_PORTS, Port, Stick};
pub use crate::options::{CoreOption, OptionValue};
pub use crate::session::{
    AvInfo, InputDescriptor, SaveRamLoad, Session, SessionConfig, SessionError, SystemInfo,
    VideoOutput,
};
pub use crate::video::{CapturedFrame, Frame, PixelFormat, VideoSink, convert_frame};
pub use crate::vulkan::VulkanError;

uniffi::setup_scaffolding!();

/// The version of the session library, for the app to check it loaded the
/// build it expects.
#[uniffi::export]
pub fn session_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[cfg(test)]
mod tests {
    #[test]
    fn version_matches_package() {
        assert_eq!(super::session_version(), env!("CARGO_PKG_VERSION"));
    }
}
