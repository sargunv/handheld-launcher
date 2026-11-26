//! A session on a thread of its own, behind the object the app drives
//! through UniFFI.
//!
//! A [`Session`] stays on the thread that created it, so [`GameSession`]
//! starts a thread that loads the core, runs it at the core's frame rate, or
//! on the display's vsync as [`crate::pacing`] describes, and takes commands
//! from the app between frames. Input skips the queue: the app
//! writes it straight to the shared [`InputState`]. What the session has to
//! tell the app goes to its [`SessionListener`], on a thread of its own.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender, TryRecvError};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::audio::{AudioSink, NullAudioSink};
use crate::input::{Button, InputState, MAX_PORTS, Stick};
use crate::pacing::{self, FrameStats, Vsync};
use crate::save::SaveWriter;
use crate::session::{AvInfo, SaveRamLoad, Session, SessionConfig, SessionError, VideoOutput};
use crate::video::{CapturedFrame, Frame, Region, VideoSink};

/// What [`GameSession::start`] needs.
#[derive(Clone, Debug, uniffi::Record)]
pub struct SessionOptions {
    /// The core's shared library.
    pub core_path: String,
    /// The content to load, or `None` for cores that run without content.
    pub content_path: Option<String>,
    /// Where a zipped game is extracted when the core can't open the zip,
    /// as RetroArch does. The session owns the directory and keeps only the
    /// last game extracted into it. `None` passes zips to the core as they
    /// are.
    #[uniffi(default = None)]
    pub content_cache_dir: Option<String>,
    /// Where the core looks for BIOS and other system files.
    pub system_dir: String,
    /// Where the core may write its own save files.
    pub save_dir: String,
    /// The file the core's save memory loads from when the session starts,
    /// and is written to, replacing it atomically, within a few seconds of
    /// changing and whenever the session pauses or stops. A missing file
    /// starts the game with blank save memory; one that can't be read fails
    /// the start. A file whose size differs from save memory's is copied to
    /// `<name>.<unix time>.bak` beside it first. `None` keeps save memory
    /// only in memory.
    pub save_ram_path: Option<String>,
    /// Core option values to use instead of the core's defaults, by key.
    pub core_options: HashMap<String, String>,
    pub video: VideoMode,
}

/// Where a session's frames go.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum VideoMode {
    /// To the window attached with [`GameSession::attach_window`], through an
    /// OpenGL ES context, or a Vulkan device for Vulkan cores, that the
    /// session keeps while windows come and go.
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

/// A window that shows the session, for [`GameSession::touch`].
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum Screen {
    /// The window attached with [`GameSession::attach_window`].
    Main,
    /// The window attached with [`GameSession::attach_lower_window`].
    Lower,
}

/// What a running session's core says about itself.
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
    /// Whether the core renders with OpenGL or Vulkan.
    pub hardware_rendered: bool,
}

impl SessionInfo {
    fn with_av(&self, av: AvInfo) -> Self {
        Self {
            fps: av.fps,
            base_width: av.base_width,
            base_height: av.base_height,
            aspect_ratio: av.aspect_ratio,
            ..self.clone()
        }
    }
}

/// What a session tells the app as it runs. The session calls it on a thread
/// of its own, one call at a time, so it may call the session back.
#[uniffi::export(with_foreign)]
pub trait SessionListener: Send + Sync {
    /// The core changed its frame size, aspect ratio or frame rate mid-game,
    /// as `info` now gives them. The session already presents and paces
    /// frames to match; the app may lay out its view again.
    fn info_changed(&self, info: SessionInfo);

    /// The core asked to quit, as when a game offers an exit. The session has
    /// stopped, writing save memory as it does when stopped, and unloaded the
    /// core, so the app should close it.
    fn core_quit(&self);
}

/// What the session thread tells the listener.
enum Event {
    InfoChanged(SessionInfo),
    CoreQuit,
}

/// Sends events to the listener, if the app gave one, on a thread of their
/// own, so a listener that calls the session back can't block it.
#[derive(Clone)]
struct Events(Option<Sender<Event>>);

impl Events {
    fn new(listener: Option<Arc<dyn SessionListener>>) -> std::io::Result<Self> {
        let Some(listener) = listener else {
            return Ok(Self(None));
        };
        let (events, receiver) = mpsc::channel();
        std::thread::Builder::new()
            .name("session-events".into())
            .spawn(move || {
                for event in receiver {
                    match event {
                        Event::InfoChanged(info) => listener.info_changed(info),
                        Event::CoreQuit => listener.core_quit(),
                    }
                }
            })?;
        Ok(Self(Some(events)))
    }

    fn send(&self, event: Event) {
        if let Some(events) = &self.0 {
            let _ = events.send(event);
        }
    }
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
    info: Arc<Mutex<SessionInfo>>,
    /// Whether a lower window is attached, so the main window shows only the
    /// frame's top half.
    split: AtomicBool,
    thread: Mutex<Option<JoinHandle<()>>>,
}

type Reply<T> = Sender<Result<T, SessionError>>;

enum Command {
    AttachWindow(PlatformWindow, u32, u32, Reply<()>),
    ResizeWindow(u32, u32),
    DetachWindow(Reply<()>),
    AttachLowerWindow(PlatformWindow, u32, u32, Reply<()>),
    DetachLowerWindow(Reply<()>),
    Pause(Reply<()>),
    Resume,
    FastForward(u32),
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
    /// they're ready to run, paused. The session tells `listener` when the
    /// core changes its video mode or quits.
    ///
    /// The core is native code that runs in this process: only start cores
    /// the user chose to trust.
    #[uniffi::constructor(default(listener = None))]
    pub fn start(
        options: SessionOptions,
        listener: Option<Arc<dyn SessionListener>>,
    ) -> Result<Arc<Self>, SessionError> {
        crate::platform::init_logging();
        let input = Arc::new(InputState::new());
        let events = Events::new(listener)?;
        let (commands, receiver) = mpsc::channel();
        let (started, start_result) = mpsc::channel();
        let thread_input = input.clone();
        let thread = std::thread::Builder::new()
            .name("session".into())
            .spawn(move || run(options, thread_input, events, receiver, started))?;
        match start_result.recv() {
            Ok(Ok(info)) => Ok(Arc::new(Self {
                commands,
                input,
                info,
                split: AtomicBool::new(false),
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

    /// What the core says about itself: as it started, with the frame size,
    /// aspect ratio and frame rate it changed to since.
    pub fn info(&self) -> SessionInfo {
        self.info.lock().unwrap_or_else(|e| e.into_inner()).clone()
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

    /// Presents the bottom half of each frame to `window` from now on, and
    /// the top half to the main window, as for a two-screen system whose core
    /// draws its screens stacked, top above bottom, with no gap. It replaces
    /// any lower window before it and shows the last frame on it at once.
    /// Only sessions in [`VideoMode::Window`] whose core doesn't render with
    /// Vulkan present to a lower window.
    pub fn attach_lower_window(
        &self,
        window: WindowHandle,
        width: u32,
        height: u32,
    ) -> Result<(), SessionError> {
        let window = PlatformWindow::adopt(window)?;
        let result = self.request(|reply| Command::AttachLowerWindow(window, width, height, reply));
        self.split.store(result.is_ok(), Ordering::Relaxed);
        result
    }

    /// Stops presenting to the lower window, and shows the whole frame on the
    /// main window again. When this returns, the session no longer uses it,
    /// so the platform may destroy it.
    pub fn detach_lower_window(&self) {
        self.split.store(false, Ordering::Relaxed);
        let _ = self.request(Command::DetachLowerWindow);
    }

    /// Touches `screen` at (`x`, `y`), in pixels from the top left of its
    /// `width` by `height` window, or moves the touch there, or lifts it
    /// there when `pressed` is false. The core reads it as libretro's pointer
    /// on port 0, in its frame's coordinates, wherever the window shows the
    /// frame. A touch on the window's black bars lifts the pointer. A touch
    /// lifted before the next frame still reaches the core.
    pub fn touch(&self, screen: Screen, x: f32, y: f32, width: u32, height: u32, pressed: bool) {
        let region = match screen {
            Screen::Lower => Region::Bottom,
            Screen::Main if self.split.load(Ordering::Relaxed) => Region::Top,
            Screen::Main => Region::Whole,
        };
        let info = self.info();
        let position = crate::video::pointer_position(
            region,
            info.base_width,
            info.base_height,
            info.aspect_ratio,
            width,
            height,
            x,
            y,
        );
        let port = self.input.port(0);
        match position {
            Some((x, y)) => port.set_pointer(x, y, pressed),
            None => port.release_pointer(),
        }
    }

    /// Lifts the pointer, as when the window that had the touch goes.
    pub fn release_touch(&self) {
        self.input.port(0).release_pointer();
    }

    /// Stops running frames, and writes save memory to its file. When this
    /// returns, the core is between frames and the file is on disk. Writing
    /// can take a while, so call it off the UI thread.
    pub fn pause(&self) -> Result<(), SessionError> {
        self.request(Command::Pause)
    }

    /// Runs frames again, at the core's rate.
    pub fn resume(&self) {
        let _ = self.commands.send(Command::Resume);
    }

    /// Runs the game `speed` times as fast as the core's own rate, without
    /// sound, from the next frame on, or at its own rate, with sound, for a
    /// `speed` of 1 or 0. The session runs `speed` frames each time a frame
    /// is due and shows each of them.
    pub fn set_fast_forward(&self, speed: u32) {
        let _ = self.commands.send(Command::FastForward(speed));
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
    events: Events,
    commands: Receiver<Command>,
    started: Sender<Result<Arc<Mutex<SessionInfo>>, SessionError>>,
) {
    let save_ram_path = options.save_ram_path.clone().map(PathBuf::from);
    let presents_to_window = options.video == VideoMode::Window;
    let mut session = match start_session(options, input) {
        Ok(session) => session,
        Err(e) => {
            let _ = started.send(Err(e));
            return;
        }
    };
    let loaded = match &save_ram_path {
        Some(path) => match session.load_save_ram(path) {
            Ok(loaded) => {
                if loaded != SaveRamLoad::Nothing {
                    log::info!("loaded save memory from {}", path.display());
                }
                loaded
            }
            Err(e) => {
                // Running anyway would replace the file with fresh save
                // memory the first time the game saves.
                log::error!("can't load save memory from {}: {e}", path.display());
                let _ = started.send(Err(SessionError::ReadSaveRam(e)));
                return;
            }
        },
        None => SaveRamLoad::Nothing,
    };
    let save_ram = match SaveRam::new(save_ram_path, &session, &loaded) {
        Ok(save_ram) => save_ram,
        Err(e) => {
            let _ = started.send(Err(e.into()));
            return;
        }
    };
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
    let vsync = presents_to_window.then(vsync_clock).flatten();
    let info = Arc::new(Mutex::new(info));
    if started.send(Ok(info.clone())).is_err() {
        return;
    }
    let runner = Runner {
        reported_av: session.av_info(),
        info,
        events: events.clone(),
        session,
        window: None,
        lower_window: None,
        save_ram,
        paused: true,
        speed: 1,
        next_frame: Instant::now(),
        vsync,
        vsyncs: None,
        stats: FrameStats::new(Instant::now()),
    };
    let quit = runner.run(&commands);
    // The session is gone, and so are its commands, so the listener may
    // stop it or start another.
    drop(commands);
    if quit {
        events.send(Event::CoreQuit);
    }
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
        content_cache: options.content_cache_dir.map(PathBuf::from),
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

/// The display's vsync, for a session that presents to windows on a platform
/// that tells it.
#[cfg(target_os = "android")]
type VsyncClock = crate::platform::VsyncClock;

#[cfg(target_os = "android")]
fn vsync_clock() -> Option<VsyncClock> {
    VsyncClock::new()
}

#[cfg(not(target_os = "android"))]
enum VsyncClock {}

#[cfg(not(target_os = "android"))]
impl VsyncClock {
    fn set_running(&mut self, _running: bool) {
        match *self {}
    }

    fn vsync(&self) -> Option<Vsync> {
        match *self {}
    }
}

#[cfg(not(target_os = "android"))]
fn vsync_clock() -> Option<VsyncClock> {
    None
}

/// The session thread's state.
struct Runner {
    session: Session,
    /// The info the app sees, which the session updates as the core changes
    /// its video mode.
    info: Arc<Mutex<SessionInfo>>,
    /// The timing and geometry `info` last reported.
    reported_av: AvInfo,
    events: Events,
    window: Option<PlatformWindow>,
    lower_window: Option<PlatformWindow>,
    save_ram: SaveRam,
    paused: bool,
    /// How many frames run each time one is due: more than one to fast
    /// forward.
    speed: u32,
    /// When the next frame is due, while running.
    next_frame: Instant,
    vsync: Option<VsyncClock>,
    /// How many vsyncs frames are paced to, or `None` for a timer.
    vsyncs: Option<u32>,
    /// Frame timing since the session last resumed or logged it.
    stats: FrameStats,
}

/// How often a running session logs its frame timing.
const STATS_INTERVAL: Duration = Duration::from_secs(60);

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
    /// Runs the session until it's told to stop or the core quits, and
    /// returns whether the core quit. The session is dropped when this
    /// returns.
    fn run(mut self, commands: &Receiver<Command>) -> bool {
        let quit = loop {
            match next(commands, self.paused, self.next_frame) {
                Next::Command(Command::Stop(reply)) => {
                    let _ = reply.send(self.stop());
                    return false;
                }
                Next::Command(command) => self.handle(command),
                Next::Frame => self.run_frame(),
                Next::Exit => break false,
            }
            self.report_av();
            if self.session.shutdown_requested() {
                log::info!("the core asked to quit");
                break true;
            }
        };
        if let Err(e) = self.stop() {
            log::error!("can't write save memory: {e}");
        }
        quit
    }

    /// Tells the app about a frame size, aspect ratio or frame rate the core
    /// changed to.
    fn report_av(&mut self) {
        let av = self.session.av_info();
        if av == self.reported_av {
            return;
        }
        self.reported_av = av;
        let info = {
            let mut info = self.info.lock().unwrap_or_else(|e| e.into_inner());
            let changed = info.with_av(av);
            if changed == *info {
                return;
            }
            *info = changed.clone();
            changed
        };
        log::info!(
            "the core changed to {}x{} at {:.2} fps",
            info.base_width,
            info.base_height,
            info.fps
        );
        self.events.send(Event::InfoChanged(info));
    }

    fn run_frame(&mut self) {
        for _ in 0..self.speed {
            self.session.run_frame();
            if self.session.shutdown_requested() {
                break;
            }
        }
        let now = Instant::now();
        let fps = self.session.av_info().fps;
        let vsync = self.vsync();
        self.stats.record(now, fps, vsync);
        if self.stats.elapsed(now) >= STATS_INTERVAL {
            self.log_stats(now);
        }
        self.save_ram.write_if_due(&self.session);
        let schedule = pacing::next_frame(self.next_frame, now, fps, vsync);
        self.next_frame = schedule.due;
        self.session.set_speed(schedule.speed);
        if schedule.vsyncs != self.vsyncs {
            self.vsyncs = schedule.vsyncs;
            match self.vsyncs {
                Some(n) => log::info!(
                    "pacing frames every {n} vsyncs, at {:.4}x the core's rate",
                    schedule.speed
                ),
                None => log::info!("pacing frames by a timer"),
            }
        }
    }

    /// The display's vsync, while the session presents to it.
    fn vsync(&self) -> Option<Vsync> {
        self.window.as_ref()?;
        self.vsync.as_ref()?.vsync()
    }

    fn log_stats(&mut self, now: Instant) {
        if let Some(report) = self.stats.report(now) {
            log::info!("{report}");
        }
        self.stats = FrameStats::new(now);
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
            Command::AttachLowerWindow(window, width, height, reply) => {
                let native = window.native();
                // SAFETY: as for the main window.
                let result =
                    unsafe { self.session.set_lower_window(Some((native, width, height))) };
                self.lower_window = result.is_ok().then_some(window);
                let _ = reply.send(result);
            }
            Command::DetachLowerWindow(reply) => {
                // SAFETY: detaching needs no window.
                let result = unsafe { self.session.set_lower_window(None) };
                self.lower_window = None;
                let _ = reply.send(result);
            }
            Command::Pause(reply) => {
                self.set_paused(true);
                let _ = reply.send(self.save_ram.flush(&self.session));
            }
            Command::Resume => self.set_paused(false),
            Command::FastForward(speed) => {
                self.speed = speed.max(1);
                // Audio at the core's rate can't keep up with more frames.
                self.session.set_audio_muted(self.speed > 1);
            }
            Command::Advance(frames, reply) => {
                if self.paused {
                    for _ in 0..frames {
                        if self.session.shutdown_requested() {
                            break;
                        }
                        self.session.run_frame();
                    }
                    // So info() is current once advance() returns.
                    self.report_av();
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
        if let Some(vsync) = &mut self.vsync {
            vsync.set_running(!paused);
        }
        let now = Instant::now();
        if paused {
            self.log_stats(now);
        } else {
            self.next_frame = now;
            self.stats = FrameStats::new(now);
        }
    }

    fn stop(&mut self) -> Result<(), SessionError> {
        self.set_paused(true);
        self.save_ram.flush(&self.session)
    }
}

/// How often a running session compares save memory with its file.
const SAVE_RAM_CHECK_INTERVAL: Duration = Duration::from_secs(2);

/// Keeps save memory's file up to date: the session thread compares save
/// memory with what it last wrote, and a writer thread writes the changes.
struct SaveRam {
    /// The writer, if the core has save memory and it has a file.
    writer: Option<SaveWriter>,
    /// Save memory as last handed to the writer.
    written: Vec<u8>,
    /// When a running session next compares save memory.
    next_check: Instant,
}

impl SaveRam {
    fn new(
        path: Option<PathBuf>,
        session: &Session,
        loaded: &SaveRamLoad,
    ) -> std::io::Result<Self> {
        let writer = match (path, session.save_ram()) {
            (Some(path), Some(_)) => Some(SaveWriter::new(path)?),
            _ => None,
        };
        let written = match loaded {
            // A file of another size gets rewritten at save memory's size,
            // now that a backup holds the original.
            SaveRamLoad::Mismatched { .. } => Vec::new(),
            SaveRamLoad::Nothing | SaveRamLoad::Loaded => {
                session.save_ram().map(<[u8]>::to_vec).unwrap_or_default()
            }
        };
        Ok(Self {
            writer,
            written,
            next_check: Instant::now() + SAVE_RAM_CHECK_INTERVAL,
        })
    }

    /// Hands save memory to the writer if it changed since the last write,
    /// or the last write failed.
    fn write_if_changed(&mut self, session: &Session) {
        let (Some(writer), Some(memory)) = (&self.writer, session.save_ram()) else {
            return;
        };
        if memory != self.written.as_slice() || writer.failed() {
            self.written.clear();
            self.written.extend_from_slice(memory);
            writer.write(memory.to_vec());
        }
    }

    /// Calls [`SaveRam::write_if_changed`] every
    /// [`SAVE_RAM_CHECK_INTERVAL`].
    fn write_if_due(&mut self, session: &Session) {
        let now = Instant::now();
        if now >= self.next_check {
            self.next_check = now + SAVE_RAM_CHECK_INTERVAL;
            self.write_if_changed(session);
        }
    }

    /// Writes save memory if it changed, and waits until every write so far
    /// is on disk.
    fn flush(&mut self, session: &Session) -> Result<(), SessionError> {
        self.write_if_changed(session);
        match &self.writer {
            Some(writer) => Ok(writer.wait()?),
            None => Ok(()),
        }
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
