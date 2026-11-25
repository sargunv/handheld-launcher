//! Fixtures and recording sinks for the session's integration tests.

#![allow(dead_code)]

use std::cell::RefCell;
use std::collections::HashMap;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::{Arc, Mutex, MutexGuard};

use handheld_session::{
    AudioSink, Frame, InputState, Session, SessionConfig, SessionError, VideoOutput, VideoSink,
};
use sha2::{Digest, Sha256};

/// The path to a fixture that `mise run session:fixtures` provides.
///
/// # Panics
///
/// If the fixture is missing.
pub fn fixture(name: &str) -> PathBuf {
    let dir = std::env::var_os("HANDHELD_SESSION_FIXTURES")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            let cache = std::env::var_os("XDG_CACHE_HOME")
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from(std::env::var_os("HOME").unwrap()).join(".cache"));
            cache.join("handheld/session-fixtures")
        });
    let path = dir.join(name);
    assert!(
        path.exists(),
        "missing test fixture {}; run `mise run session:fixtures` to build it",
        path.display()
    );
    path
}

/// Serializes the tests in one binary: a process runs one session at a time.
pub fn lock() -> MutexGuard<'static, ()> {
    static LOCK: Mutex<()> = Mutex::new(());
    LOCK.lock().unwrap_or_else(|e| e.into_inner())
}

/// A frame the session produced.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OwnedFrame {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
}

impl OwnedFrame {
    pub fn pixel(&self, x: u32, y: u32) -> [u8; 4] {
        Frame {
            width: self.width,
            height: self.height,
            pixels: &self.pixels,
        }
        .pixel(x, y)
    }

    pub fn sha256(&self) -> String {
        let digest = Sha256::digest(&self.pixels);
        digest.iter().map(|b| format!("{b:02x}")).collect()
    }

    /// The number of distinct colors, to tell a picture from a blank screen.
    pub fn colors(&self) -> usize {
        let mut colors: Vec<&[u8; 4]> = self.pixels.as_chunks::<4>().0.iter().collect();
        colors.sort_unstable();
        colors.dedup();
        colors.len()
    }

    /// Writes the frame as a PNG to `$HANDHELD_SESSION_DUMP_DIR`, if set, for
    /// looking at what a test saw.
    pub fn dump(&self, name: &str) {
        let Some(dir) = std::env::var_os("HANDHELD_SESSION_DUMP_DIR") else {
            return;
        };
        let path = PathBuf::from(dir).join(format!("{name}.png"));
        let file = std::fs::File::create(&path).unwrap();
        let mut encoder = png::Encoder::new(file, self.width, self.height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&self.pixels)
            .unwrap();
    }
}

/// The frames a session produced, shared between the sink and the test.
#[derive(Clone, Default)]
pub struct Frames(Rc<RefCell<(usize, Option<OwnedFrame>)>>);

impl Frames {
    pub fn count(&self) -> usize {
        self.0.borrow().0
    }

    pub fn last(&self) -> OwnedFrame {
        self.0.borrow().1.clone().expect("no frame yet")
    }
}

impl VideoSink for Frames {
    fn frame(&mut self, frame: Frame<'_>) {
        let mut inner = self.0.borrow_mut();
        inner.0 += 1;
        inner.1 = Some(OwnedFrame {
            width: frame.width,
            height: frame.height,
            pixels: frame.pixels.to_vec(),
        });
    }
}

/// The audio a session produced at 48 kHz, shared between the sink and the
/// test.
#[derive(Clone, Default)]
pub struct Audio(Rc<RefCell<Vec<f32>>>);

impl Audio {
    pub const RATE: u32 = 48_000;

    pub fn samples(&self) -> Vec<f32> {
        self.0.borrow().clone()
    }

    /// Stereo frames received.
    pub fn frames(&self) -> usize {
        self.0.borrow().len() / 2
    }
}

impl AudioSink for Audio {
    fn sample_rate(&self) -> u32 {
        Self::RATE
    }

    fn write(&mut self, samples: &[f32]) {
        self.0.borrow_mut().extend_from_slice(samples);
    }
}

/// A session with recording sinks.
pub struct Harness {
    pub session: Session,
    pub frames: Frames,
    pub audio: Audio,
    pub input: Arc<InputState>,
    pub dirs: tempfile::TempDir,
}

impl Harness {
    pub fn start(core: &str, content: Option<&str>) -> Result<Self, SessionError> {
        Self::start_with(core, content, HashMap::new())
    }

    pub fn start_with(
        core: &str,
        content: Option<&str>,
        options: HashMap<String, String>,
    ) -> Result<Self, SessionError> {
        let frames = Frames::default();
        let audio = Audio::default();
        let input = Arc::new(InputState::new());
        let dirs = tempfile::tempdir().unwrap();
        let config = SessionConfig {
            core: fixture(core),
            content: content.map(fixture),
            system_dir: dirs.path().join("system"),
            save_dir: dirs.path().join("saves"),
            video: VideoOutput::Sink(Box::new(frames.clone())),
            audio: Box::new(audio.clone()),
            input: input.clone(),
            options,
        };
        // SAFETY: the fixtures are libretro cores.
        let session = unsafe { Session::new(config)? };
        Ok(Self {
            session,
            frames,
            audio,
            input,
            dirs,
        })
    }

    pub fn run(&mut self, frames: usize) {
        for _ in 0..frames {
            self.session.run_frame();
        }
    }
}

/// Counts sign changes in the left channel of interleaved stereo samples.
pub fn zero_crossings(samples: &[f32]) -> usize {
    let left: Vec<f32> = samples.as_chunks::<2>().0.iter().map(|f| f[0]).collect();
    left.windows(2)
        .filter(|w| (w[0] < 0.0) != (w[1] < 0.0))
        .count()
}
