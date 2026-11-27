//! Platforms the session has no windows, vsync or audio output on, such as
//! the desktop it's tested on.

use crate::audio::{AudioSink, NullAudioSink};
use crate::pacing::Vsync;
use crate::{Frame, NativeWindow, Region, SessionError, VideoOutput, VideoSink, WindowHandle};

/// The app's own logger takes the session's logs.
pub(crate) fn init_logging() {}

pub(crate) enum PlatformWindow {}

impl PlatformWindow {
    pub(crate) fn adopt(_handle: WindowHandle) -> Result<Self, SessionError> {
        Err(SessionError::UnsupportedWindow)
    }

    pub(crate) fn native(&self) -> NativeWindow {
        match *self {}
    }
}

pub(crate) enum VsyncClock {}

impl VsyncClock {
    pub(crate) fn new() -> Option<Self> {
        None
    }

    pub(crate) fn set_running(&mut self, _running: bool) {
        match *self {}
    }

    pub(crate) fn vsync(&self) -> Option<Vsync> {
        match *self {}
    }
}

pub(crate) fn audio_sink() -> Box<dyn AudioSink> {
    Box::new(NullAudioSink)
}

/// Discards frames: the session keeps the last one itself, for
/// `GameSession::capture_frame`.
pub(crate) fn video_output(_lower: Region) -> VideoOutput {
    VideoOutput::Sink(Box::new(DiscardFrames))
}

struct DiscardFrames;

impl VideoSink for DiscardFrames {
    fn frame(&mut self, _frame: Frame<'_>) {}
}
