//! A session on a thread of its own, behind the object the app drives
//! through UniFFI.
//!
//! A [`Session`] stays on the thread that created it, so [`GameSession`]
//! starts a thread that loads the core, runs it at the core's frame rate, and
//! takes commands from the app between frames. Input skips the queue: the app
//! writes it straight to the shared [`InputState`].

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender, TryRecvError};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::audio::{AudioSink, NullAudioSink};
use crate::input::{Button, InputState, MAX_PORTS, Stick};
use crate::session::{Session, SessionConfig, SessionError, VideoOutput};
use crate::video::{CapturedFrame, Frame, VideoSink};

/// What [`GameSession::start`] needs.
#[derive(Clone, Debug, uniffi::Record)]
pub struct SessionOptions {
    /// The core's shared library.
    pub core_path: String,
    /// The content to load, or `None` for cores that run without content.
    pub content_path: Option<String>,
    /// Where the core looks for BIOS and other system files.
    pub system_dir: String,
    /// Where the core may write its own save files.
    pub save_dir: String,
    /// The file the core's save memory loads from when the session starts,
    /// and is written to when it pauses or stops. A missing file starts the
    /// game with blank save memory; one that can't be read fails the start.
    /// `None` keeps save memory only in memory.
    pub save_ram_path: Option<String>,
    /// Core option values to use instead of the core's defaults, by key.
    pub core_options: HashMap<String, String>,
    pub video: VideoMode,
}

/// Where a session's frames go.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum VideoMode {
    /// To the window attached with [`GameSession::attach_window`], through an
    /// OpenGL ES context the session keeps while windows come and go.
    Window,
    /// Nowhere: the session only keeps the last frame, for
    /// [`GameSession::capture_frame`].
    Offscreen,
}

/// A window to present to, from the platform's own window system.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum WindowHandle {
    /// An Android `ANativeWindow`, as a pointer. The session takes over one
    /// reference to it, such as `ANativeWindow_fromSurface` returns, and
    /// releases it when the window is detached or replaced, or attaching it
    /// fails.
    Android { window: u64 },
}

/// What a running session's core says about itself, as it started.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SessionInfo {
    pub library_name: String,
    pub library_version: String,
    /// Frames per second the session runs at.
    pub fps: f64,
    /// The usual frame size.
    pub base_width: u32,
    pub base_height: u32,
    /// The intended display aspect ratio, or 0 for the frame's own.
    pub aspect_ratio: f32,
    /// Whether the core renders with OpenGL.
    pub hardware_rendered: bool,
}

/// A core running content on a thread of its own.
///
/// A session starts paused, so the app can attach a window first. Every
/// method may be called from any thread. Once the session stops, by
/// [`GameSession::stop`], because the core asked to quit, or because its
/// thread failed, methods that need the core return [`SessionError::Stopped`]
/// and the rest do nothing.
#[derive(uniffi::Object)]
pub struct GameSession {
    commands: Sender<Command>,
    input: Arc<InputState>,
    info: SessionInfo,
    thread: Mutex<Option<JoinHandle<()>>>,
}

type Reply<T> = Sender<Result<T, SessionError>>;

enum Command {
    AttachWindow(PlatformWindow, u32, u32, Reply<()>),
    ResizeWindow(u32, u32),
    DetachWindow(Reply<()>),
    Pause(Reply<()>),
    Resume,
    Advance(u32, Reply<()>),
    Reset,
    SaveState(PathBuf, Reply<()>),
    LoadState(PathBuf, Reply<()>),
    CaptureFrame(Reply<Option<CapturedFrame>>),
    Stop(Reply<()>),
}

#[uniffi::export]
impl GameSession {
    /// Loads the core and its content on a new thread, and returns once
    /// they're ready to run, paused.
    ///
    /// The core is native code that runs in this process: only start cores
    /// the user chose to trust.
    #[uniffi::constructor]
    pub fn start(options: SessionOptions) -> Result<Arc<Self>, SessionError> {
        crate::platform::init_logging();
        let input = Arc::new(InputState::new());
        let (commands, receiver) = mpsc::channel();
        let (started, start_result) = mpsc::channel();
        let thread_input = input.clone();
        let thread = std::thread::Builder::new()
            .name("session".into())
            .spawn(move || run(options, thread_input, receiver, started))?;
        match start_result.recv() {
            Ok(Ok(info)) => Ok(Arc::new(Self {
                commands,
                input,
                info,
                thread: Mutex::new(Some(thread)),
            })),
            Ok(Err(e)) => {
                let _ = thread.join();
                Err(e)
            }
            Err(_) => {
                let _ = thread.join();
                Err(SessionError::Stopped)
            }
        }
    }

    /// What the core said about itself when the session started.
    pub fn info(&self) -> SessionInfo {
        self.info.clone()
    }

    /// Presents to `window` from now on, instead of any window before it, and
    /// shows the last frame on it at once. Only sessions in
    /// [`VideoMode::Window`] present to windows.
    pub fn attach_window(
        &self,
        window: WindowHandle,
        width: u32,
        height: u32,
    ) -> Result<(), SessionError> {
        // Take ownership first, so the window is released on every path.
        let window = PlatformWindow::adopt(window)?;
        self.request(|reply| Command::AttachWindow(window, width, height, reply))
    }

    /// Tells the session the attached window's new size in pixels.
    pub fn resize_window(&self, width: u32, height: u32) {
        let _ = self.commands.send(Command::ResizeWindow(width, height));
    }

    /// Stops presenting to the attached window. When this returns, the
    /// session no longer uses it, so the platform may destroy it.
    pub fn detach_window(&self) {
        let _ = self.request(Command::DetachWindow);
    }

    /// Stops running frames, and writes save memory to its file. When this
    /// returns, the core is between frames.
    pub fn pause(&self) -> Result<(), SessionError> {
        self.request(Command::Pause)
    }

    /// Runs frames again, at the core's rate.
    pub fn resume(&self) {
        let _ = self.commands.send(Command::Resume);
    }

    /// Runs `frames` frames now, as fast as the core can, if the session is
    /// paused. For stepping a frame at a time, and for tests.
    pub fn advance(&self, frames: u32) -> Result<(), SessionError> {
        self.request(|reply| Command::Advance(frames, reply))
    }

    /// Resets the core, like a console's reset button.
    pub fn reset(&self) {
        let _ = self.commands.send(Command::Reset);
    }

    /// Presses or releases `button` on controller `port`. Ports past the last
    /// are ignored.
    pub fn set_button(&self, port: u32, button: Button, pressed: bool) {
        if let Some(port) = self.port(port) {
            port.set_button(button, pressed);
        }
    }

    /// Moves `stick` on controller `port`, each axis from -32768 to 32767,
    /// with positive y down.
    pub fn set_stick(&self, port: u32, stick: Stick, x: i16, y: i16) {
        if let Some(port) = self.port(port) {
            port.set_stick(stick, x, y);
        }
    }

    /// Sets how far the analog trigger `button`, `L2` or `R2`, is pulled on
    /// controller `port`, from 0 to 32767. Other buttons are ignored.
    pub fn set_trigger(&self, port: u32, button: Button, value: i16) {
        if matches!(button, Button::L2 | Button::R2)
            && let Some(port) = self.port(port)
        {
            port.set_trigger(button, value);
        }
    }

    /// Saves the core's state to the file at `path`, replacing it only once
    /// the new contents are on disk.
    pub fn save_state(&self, path: String) -> Result<(), SessionError> {
        self.request(|reply| Command::SaveState(path.into(), reply))
    }

    /// Restores a state that [`GameSession::save_state`] wrote.
    pub fn load_state(&self, path: String) -> Result<(), SessionError> {
        self.request(|reply| Command::LoadState(path.into(), reply))
    }

    /// A copy of the last frame, or `None` before the first.
    pub fn capture_frame(&self) -> Result<Option<CapturedFrame>, SessionError> {
        self.request(Command::CaptureFrame)
    }

    /// Writes save memory to its file, unloads the core and ends the
    /// session's thread. Stopping a stopped session does nothing.
    pub fn stop(&self) -> Result<(), SessionError> {
        let result = match self.request(Command::Stop) {
            Err(SessionError::Stopped) => Ok(()),
            result => result,
        };
        let thread = self.thread.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(thread) = thread {
            let _ = thread.join();
        }
        result
    }
}

impl GameSession {
    fn port(&self, port: u32) -> Option<&crate::input::Port> {
        let port = usize::try_from(port).ok().filter(|&p| p < MAX_PORTS)?;
        Some(self.input.port(port))
    }

    /// Sends a command and waits for the session thread's reply.
    fn request<T>(&self, command: impl FnOnce(Reply<T>) -> Command) -> Result<T, SessionError> {
        let (reply, response) = mpsc::channel();
        self.commands
            .send(command(reply))
            .map_err(|_| SessionError::Stopped)?;
        response.recv().map_err(|_| SessionError::Stopped)?
    }
}

impl Drop for GameSession {
    fn drop(&mut self) {
        if let Err(e) = self.stop() {
            log::error!("the session failed while stopping: {e}");
        }
    }
}

/// A window the session owns while it's attached.
struct PlatformWindow {
    #[cfg(target_os = "android")]
    window: crate::platform::AndroidWindow,
}

impl PlatformWindow {
    fn adopt(handle: WindowHandle) -> Result<Self, SessionError> {
        match handle {
            #[cfg(target_os = "android")]
            WindowHandle::Android { window } => {
                // SAFETY: the caller hands over a reference, as documented on
                // `WindowHandle::Android`.
                let window = unsafe { crate::platform::AndroidWindow::adopt(window) }
                    .ok_or(SessionError::UnsupportedWindow)?;
                Ok(Self { window })
            }
            #[cfg(not(target_os = "android"))]
            WindowHandle::Android { .. } => Err(SessionError::UnsupportedWindow),
        }
    }

    fn native(&self) -> crate::NativeWindow {
        #[cfg(target_os = "android")]
        {
            self.window.native()
        }
        #[cfg(not(target_os = "android"))]
        unreachable!("no window type exists on this platform")
    }
}

// SAFETY: an `ANativeWindow` reference may be used and released from any
// thread.
unsafe impl Send for PlatformWindow {}

/// The sink of a session in [`VideoMode::Offscreen`]. The session keeps the
/// last frame itself, for [`Session::capture_frame`].
struct DiscardFrames;

impl VideoSink for DiscardFrames {
    fn frame(&mut self, _frame: Frame<'_>) {}
}

/// The session thread: loads the session, then runs it until told to stop.
fn run(
    options: SessionOptions,
    input: Arc<InputState>,
    commands: Receiver<Command>,
    started: Sender<Result<SessionInfo, SessionError>>,
) {
    let save_ram_path = options.save_ram_path.clone().map(PathBuf::from);
    let mut session = match start_session(options, input) {
        Ok(session) => session,
        Err(e) => {
            let _ = started.send(Err(e));
            return;
        }
    };
    if let Some(path) = &save_ram_path {
        match session.load_save_ram(path) {
            Ok(true) => log::info!("loaded save memory from {}", path.display()),
            Ok(false) => {}
            Err(e) => {
                // Running anyway would replace the file with fresh save
                // memory the first time the game saves.
                log::error!("can't load save memory from {}: {e}", path.display());
                let _ = started.send(Err(SessionError::ReadSaveRam(e)));
                return;
            }
        }
    }
    let av = session.av_info();
    let info = SessionInfo {
        library_name: session.system_info().library_name.clone(),
        library_version: session.system_info().library_version.clone(),
        fps: av.fps,
        base_width: av.base_width,
        base_height: av.base_height,
        aspect_ratio: av.aspect_ratio,
        hardware_rendered: session.is_hardware_rendered(),
    };
    session.set_audio_paused(true);
    if started.send(Ok(info)).is_err() {
        return;
    }
    let mut runner = Runner {
        session,
        window: None,
        save_ram: SaveRam::new(save_ram_path),
        paused: true,
        next_frame: Instant::now(),
    };
    runner.save_ram.mark_clean(&runner.session);
    runner.run(&commands);
}

fn start_session(options: SessionOptions, input: Arc<InputState>) -> Result<Session, SessionError> {
    let video = match options.video {
        VideoMode::Window => VideoOutput::Window {
            window: None,
            width: 0,
            height: 0,
            // The session paces frames itself.
            vsync: false,
        },
        VideoMode::Offscreen => VideoOutput::Sink(Box::new(DiscardFrames)),
    };
    let config = SessionConfig {
        core: options.core_path.into(),
        content: options.content_path.map(PathBuf::from),
        system_dir: options.system_dir.into(),
        save_dir: options.save_dir.into(),
        video,
        audio: audio_sink(),
        input,
        options: options.core_options,
    };
    // SAFETY: `GameSession::start` documents that the core runs as native
    // code; choosing it is the app's call.
    unsafe { Session::new(config) }
}

/// The platform's audio output, or none where the session has no output.
fn audio_sink() -> Box<dyn AudioSink> {
    #[cfg(target_os = "android")]
    match crate::platform::AAudioSink::new() {
        Ok(sink) => return Box::new(sink),
        Err(e) => log::error!("can't open audio output, so the session plays none: {e}"),
    }
    Box::new(NullAudioSink)
}

/// The session thread's state.
struct Runner {
    session: Session,
    window: Option<PlatformWindow>,
    save_ram: SaveRam,
    paused: bool,
    /// When the next frame is due, while running.
    next_frame: Instant,
}

/// How far behind schedule the session may fall before it gives up catching
/// up and starts counting from now.
const MAX_LAG_FRAMES: u32 = 4;

/// What the session thread does next.
enum Next {
    Command(Command),
    Frame,
    Exit,
}

/// Waits for the next command, or, while running, until the next frame is
/// due. Commands queued when a frame is due come first, so a core slower than
/// its frame rate still takes them.
fn next(commands: &Receiver<Command>, paused: bool, next_frame: Instant) -> Next {
    if paused {
        return commands.recv().map_or(Next::Exit, Next::Command);
    }
    let wait = next_frame.saturating_duration_since(Instant::now());
    if wait.is_zero() {
        return match commands.try_recv() {
            Ok(command) => Next::Command(command),
            Err(TryRecvError::Empty) => Next::Frame,
            Err(TryRecvError::Disconnected) => Next::Exit,
        };
    }
    match commands.recv_timeout(wait) {
        Ok(command) => Next::Command(command),
        Err(RecvTimeoutError::Timeout) => Next::Frame,
        Err(RecvTimeoutError::Disconnected) => Next::Exit,
    }
}

impl Runner {
    fn run(mut self, commands: &Receiver<Command>) {
        loop {
            match next(commands, self.paused, self.next_frame) {
                Next::Command(Command::Stop(reply)) => {
                    let _ = reply.send(self.stop());
                    return;
                }
                Next::Command(command) => self.handle(command),
                Next::Frame => {
                    self.run_frame();
                    if self.session.shutdown_requested() {
                        log::info!("the core asked to quit");
                        break;
                    }
                }
                Next::Exit => break,
            }
        }
        if let Err(e) = self.stop() {
            log::error!("can't write save memory: {e}");
        }
    }

    fn run_frame(&mut self) {
        self.session.run_frame();
        let period = self.frame_period();
        self.next_frame += period;
        let now = Instant::now();
        if now > self.next_frame + period * MAX_LAG_FRAMES {
            self.next_frame = now;
        }
    }

    fn frame_period(&self) -> Duration {
        let fps = self.session.av_info().fps;
        if fps.is_finite() && fps > 1.0 {
            Duration::from_secs_f64(1.0 / fps)
        } else {
            Duration::from_secs_f64(1.0 / 60.0)
        }
    }

    fn handle(&mut self, command: Command) {
        match command {
            Command::AttachWindow(window, width, height, reply) => {
                let native = window.native();
                // SAFETY: `self.window` keeps the window alive until it's
                // detached or replaced, which happens after the session lets
                // go of it.
                let result = unsafe { self.session.set_window(Some((native, width, height))) };
                // The old window, if any, is unused now.
                self.window = result.is_ok().then_some(window);
                let _ = reply.send(result);
            }
            Command::ResizeWindow(width, height) => self.session.resize_window(width, height),
            Command::DetachWindow(reply) => {
                // SAFETY: detaching needs no window.
                let result = unsafe { self.session.set_window(None) };
                self.window = None;
                let _ = reply.send(result);
            }
            Command::Pause(reply) => {
                self.set_paused(true);
                let _ = reply.send(self.save_ram.flush(&self.session));
            }
            Command::Resume => self.set_paused(false),
            Command::Advance(frames, reply) => {
                if self.paused {
                    for _ in 0..frames {
                        self.session.run_frame();
                    }
                }
                let _ = reply.send(Ok(()));
            }
            Command::Reset => self.session.reset(),
            Command::SaveState(path, reply) => {
                let _ = reply.send(self.session.save_state(&path));
            }
            Command::LoadState(path, reply) => {
                let _ = reply.send(self.session.load_state(&path));
            }
            Command::CaptureFrame(reply) => {
                let _ = reply.send(Ok(self.session.capture_frame()));
            }
            Command::Stop(_) => unreachable!("handled by the loop"),
        }
    }

    fn set_paused(&mut self, paused: bool) {
        if self.paused == paused {
            return;
        }
        self.paused = paused;
        self.session.set_audio_paused(paused);
        if !paused {
            self.next_frame = Instant::now();
        }
    }

    fn stop(&mut self) -> Result<(), SessionError> {
        self.set_paused(true);
        self.save_ram.flush(&self.session)
    }
}

/// Writes save memory to its file when it changed since it was last read or
/// written.
struct SaveRam {
    path: Option<PathBuf>,
    written: Vec<u8>,
}

impl SaveRam {
    fn new(path: Option<PathBuf>) -> Self {
        Self {
            path,
            written: Vec::new(),
        }
    }

    fn mark_clean(&mut self, session: &Session) {
        self.written = session.save_ram().map(<[u8]>::to_vec).unwrap_or_default();
    }

    fn flush(&mut self, session: &Session) -> Result<(), SessionError> {
        let (Some(path), Some(memory)) = (&self.path, session.save_ram()) else {
            return Ok(());
        };
        if memory == self.written.as_slice() {
            return Ok(());
        }
        session.flush_save_ram(path)?;
        log::info!("wrote save memory to {}", path.display());
        self.mark_clean(session);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn takes_commands_when_a_frame_is_overdue() {
        let (commands, receiver) = mpsc::channel();
        let overdue = Instant::now() - Duration::from_secs(1);
        assert!(matches!(next(&receiver, false, overdue), Next::Frame));
        commands.send(Command::Resume).unwrap();
        assert!(matches!(
            next(&receiver, false, overdue),
            Next::Command(Command::Resume)
        ));
        assert!(matches!(next(&receiver, false, overdue), Next::Frame));
        drop(commands);
        assert!(matches!(next(&receiver, false, overdue), Next::Exit));
    }

    #[test]
    fn waits_for_the_next_frame_or_a_command() {
        let (commands, receiver) = mpsc::channel();
        let soon = Instant::now() + Duration::from_millis(20);
        assert!(matches!(next(&receiver, false, soon), Next::Frame));
        assert!(Instant::now() >= soon);
        commands.send(Command::Resume).unwrap();
        let later = Instant::now() + Duration::from_secs(60);
        assert!(matches!(
            next(&receiver, false, later),
            Next::Command(Command::Resume)
        ));
        commands.send(Command::Reset).unwrap();
        assert!(matches!(
            next(&receiver, true, Instant::now()),
            Next::Command(Command::Reset)
        ));
    }
}
