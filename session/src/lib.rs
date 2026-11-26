//! The game session: hosts libretro cores and owns their video, audio and
//! input. The Kotlin app drives it through UniFFI.
//!
//! A [`Session`] loads a core and its content and runs it a frame at a time.
//! The platform layer plugs in through [`VideoSink`] or a [`NativeWindow`]
//! for video, [`AudioSink`] for audio, and [`InputState`] for controllers.
//!
//! [`GameSession`] is what the app uses: a session on a thread of its own,
//! with the platform's window and audio output.

mod achievements;
mod audio;
mod content;
mod core;
mod ffi;
mod gl;
mod handle;
mod host;
mod input;
mod options;
mod pacing;
mod platform;
mod rom_header;
mod save;
mod session;
mod video;
mod vulkan;

pub use crate::achievements::{
    Achievement, AchievementEvent, AchievementOptions, AchievementStatus, AchievementsRequest,
    HttpClient as AchievementsHttpClient, rcheevos_user_agent_clause,
};
pub use crate::audio::{AudioSink, BufferStatus, NullAudioSink};
pub use crate::core::CoreLoadError;
pub use crate::gl::{GlError, NativeWindow};
pub use crate::handle::{
    AchievementsHttp, GameSession, SessionInfo, SessionListener, SessionOptions, VideoMode,
    WindowHandle,
};
pub use crate::input::{Button, InputState, MAX_PORTS, Port, Stick};
pub use crate::rom_header::{HeaderField, rom_game_id, rom_header_fields};
pub use crate::save::SaveRam;
pub use crate::session::{AvInfo, Session, SessionConfig, SessionError, SystemInfo, VideoOutput};
pub use crate::video::{CapturedFrame, Frame, VideoSink};
pub use crate::vulkan::VulkanError;

uniffi::setup_scaffolding!();
