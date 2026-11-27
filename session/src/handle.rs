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
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::achievements::{
    AchievementEvent, AchievementOptions, AchievementStatus, AchievementsRequest,
    SendAchievementsRequest,
};
use crate::audio::{AudioSink, NullAudioSink};
use crate::input::{Button, InputState, MAX_PORTS, Stick};
use crate::pacing::{self, Vsync};
use crate::save::SaveRam;
use crate::session::{AvInfo, Session, SessionConfig, SessionError, VideoOutput};
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
    /// The bottom screen's share of the frame's width, from 0 to 1, when a
    /// two-screen system's core draws it narrower than the top screen and
    /// centred under it, as Azahar draws the 3DS's 320-pixel-wide bottom
    /// screen under its 400-pixel-wide top one: 0.8. A lower window shows
    /// only those columns, and its touches land on them. `None` shows the
    /// frame's whole width.
    #[uniffi(default = None)]
    pub lower_screen_width_share: Option<f32>,
    /// How the session takes part in RetroAchievements, or `None` for not at
    /// all. Its requests go to the `http` that [`GameSession::start`] takes,
    /// so without one, the session doesn't take part either.
    #[uniffi(default = None)]
    pub achievements: Option<AchievementOptions>,
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
}

impl SessionInfo {
    /// Takes the frame size, aspect ratio and frame rate in `av`, and returns
    /// whether they changed.
    fn set_av(&mut self, av: AvInfo) -> bool {
        let changed = self.fps != av.fps
            || self.base_width != av.base_width
            || self.base_height != av.base_height
            || self.aspect_ratio != av.aspect_ratio;
        self.fps = av.fps;
        self.base_width = av.base_width;
        self.base_height = av.base_height;
        self.aspect_ratio = av.aspect_ratio;
        changed
    }
}

/// What a session tells the app as it runs. The session calls it on a thread
/// of its own, one call at a time, so it may call the session back.
#[uniffi::export(with_foreign)]
pub trait SessionListener: Send + Sync {
    /// The core asked to quit, as when a game offers an exit. The session has
    /// stopped, writing save memory as it does when stopped, and unloaded the
    /// core, so the app should close it.
    fn core_quit(&self);

    /// Something happened to the game's achievements, such as an unlock.
    fn achievement_event(&self, event: AchievementEvent);
}

/// Sends a session's requests to RetroAchievements, which the app does with
/// its own HTTP client. The session calls it on a thread of its own, one call
/// at a time, and it should return at once: the app sends the request in the
/// background, then hands its response to
/// [`GameSession::achievements_response`] with the request's `id`.
///
/// RetroAchievements asks clients to name themselves in the User-Agent, as
/// `HandheldLauncher/<version> rcheevos/<version>`, which
/// [`crate::rcheevos_user_agent_clause`] ends.
#[uniffi::export(with_foreign)]
pub trait AchievementsHttp: Send + Sync {
    fn request(&self, id: u64, request: AchievementsRequest);
}

/// What the session thread tells the listener.
enum Event {
    CoreQuit,
    Achievement(AchievementEvent),
    Request(u64, AchievementsRequest),
}

/// Sends events to the listener, if the app gave one, on a thread of their
/// own, so a listener that calls the session back can't block it.
#[derive(Clone)]
struct Events(Option<Sender<Event>>);

impl Events {
    fn new(
        listener: Option<Arc<dyn SessionListener>>,
        http: Option<Arc<dyn AchievementsHttp>>,
    ) -> std::io::Result<Self> {
        if listener.is_none() && http.is_none() {
            return Ok(Self(None));
        }
        let (events, receiver) = mpsc::channel();
        std::thread::Builder::new()
            .name("session-events".into())
            .spawn(move || {
                for event in receiver {
                    match (event, &listener, &http) {
                        (Event::CoreQuit, Some(listener), _) => listener.core_quit(),
                        (Event::Achievement(event), Some(listener), _) => {
                            listener.achievement_event(event)
                        }
                        (Event::Request(id, request), _, Some(http)) => http.request(id, request),
                        _ => {}
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
    /// What the lower window shows of each frame.
    lower: Region,
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
    SaveState(PathBuf, Reply<()>),
    LoadState(PathBuf, Reply<()>),
    CaptureFrame(Reply<Option<CapturedFrame>>),
    AchievementStatus(Reply<AchievementStatus>),
    AchievementsResponse(u64, Option<u16>, Vec<u8>),
    Stop(Reply<()>),
}

#[uniffi::export]
impl GameSession {
    /// Loads the core and its content on a new thread, and returns once
    /// they're ready to run, paused. The session tells `listener` when the
    /// core quits or something happens to the game's achievements, and sends
    /// RetroAchievements requests to `http`.
    ///
    /// The core is native code that runs in this process: only start cores
    /// the user chose to trust.
    #[uniffi::constructor(default(listener = None, http = None))]
    pub fn start(
        mut options: SessionOptions,
        listener: Option<Arc<dyn SessionListener>>,
        http: Option<Arc<dyn AchievementsHttp>>,
    ) -> Result<Arc<Self>, SessionError> {
        crate::platform::init_logging();
        if http.is_none() {
            options.achievements = None;
        }
        let input = Arc::new(InputState::new());
        let events = Events::new(listener, http)?;
        let (commands, receiver) = mpsc::channel();
        let (started, start_result) = mpsc::channel();
        let thread_input = input.clone();
        let lower = Region::bottom(options.lower_screen_width_share);
        let thread = std::thread::Builder::new()
            .name("session".into())
            .spawn(move || run(options, thread_input, events, receiver, started))?;
        match start_result.recv() {
            Ok(Ok(info)) => Ok(Arc::new(Self {
                commands,
                input,
                info,
                split: AtomicBool::new(false),
                lower,
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
    /// draws its screens stacked, top above bottom, with no gap. Of the bottom
    /// half, it shows the share of the width that
    /// [`SessionOptions::lower_screen_width_share`] gives. It replaces
    /// any lower window before it and shows the last frame on it at once.
    /// Only sessions in [`VideoMode::Window`] present to a lower window.
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
            Screen::Lower => self.lower,
            Screen::Main if self.split.load(Ordering::Relaxed) => Region::Top,
            Screen::Main => Region::Whole,
        };
        let (base_width, base_height, aspect_ratio) = {
            let info = self.info.lock().unwrap_or_else(|e| e.into_inner());
            (info.base_width, info.base_height, info.aspect_ratio)
        };
        let position = crate::video::pointer_position(
            region,
            base_width,
            base_height,
            aspect_ratio,
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

    /// Where the game's achievements stand, with the list of them once
    /// they've loaded.
    pub fn achievements(&self) -> Result<AchievementStatus, SessionError> {
        self.request(Command::AchievementStatus)
    }

    /// Hands the session the response to the RetroAchievements request `id`:
    /// its HTTP `status` and `body`, or a `status` of `None` when the server
    /// couldn't be reached.
    pub fn achievements_response(&self, id: u64, status: Option<u16>, body: Vec<u8>) {
        let _ = self
            .commands
            .send(Command::AchievementsResponse(id, status, body));
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
    let mut session = match start_session(options, input, &events) {
        Ok(session) => session,
        Err(e) => {
            let _ = started.send(Err(e));
            return;
        }
    };
    let save_ram = match &save_ram_path {
        Some(path) => match SaveRam::load(path, &mut session) {
            Ok(save_ram) => save_ram,
            Err(e) => {
                // Running anyway would replace the file with fresh save
                // memory the first time the game saves.
                log::error!("can't load save memory from {}: {e}", path.display());
                let _ = started.send(Err(SessionError::ReadSaveRam(e)));
                return;
            }
        },
        None => None,
    };
    let av = session.av_info();
    let info = SessionInfo {
        library_name: session.system_info().library_name.clone(),
        library_version: session.system_info().library_version.clone(),
        fps: av.fps,
        base_width: av.base_width,
        base_height: av.base_height,
        aspect_ratio: av.aspect_ratio,
    };
    session.set_audio_paused(true);
    let vsync = presents_to_window.then(vsync_clock).flatten();
    let info = Arc::new(Mutex::new(info));
    if started.send(Ok(info.clone())).is_err() {
        return;
    }
    let runner = Runner {
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
    };
    let quit = runner.run(&commands);
    // The session is gone, and so are its commands, so the listener may
    // stop it or start another.
    drop(commands);
    if quit {
        events.send(Event::CoreQuit);
    }
}

fn start_session(
    options: SessionOptions,
    input: Arc<InputState>,
    events: &Events,
) -> Result<Session, SessionError> {
    let video = match options.video {
        VideoMode::Window => VideoOutput::Window {
            window: None,
            width: 0,
            height: 0,
            lower_screen_width_share: options.lower_screen_width_share,
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
        achievements: options.achievements.map(|achievements| {
            let events = events.clone();
            let send: SendAchievementsRequest =
                Box::new(move |id, request| events.send(Event::Request(id, request)));
            (achievements, send)
        }),
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
    events: Events,
    window: Option<PlatformWindow>,
    lower_window: Option<PlatformWindow>,
    /// Keeps save memory's file up to date, if the core has save memory and
    /// it has a file.
    save_ram: Option<SaveRam>,
    paused: bool,
    /// How many frames run each time one is due: more than one to fast
    /// forward.
    speed: u32,
    /// When the next frame is due, while running.
    next_frame: Instant,
    vsync: Option<VsyncClock>,
    /// How many vsyncs frames are paced to, or `None` for a timer.
    vsyncs: Option<u32>,
}

/// What the session thread does next.
enum Next {
    Command(Command),
    Frame,
    /// Time for achievements' periodic work while paused.
    Idle,
    Exit,
}

/// How often a paused session lets achievements retry and ping the server.
const ACHIEVEMENTS_IDLE_INTERVAL: Duration = Duration::from_secs(1);

/// Waits for the next command, or, while running, until the next frame is
/// due. Commands queued when a frame is due come first, so a core slower than
/// its frame rate still takes them. While paused, it waits until `idle`, if
/// given, for achievements' periodic work.
fn next(
    commands: &Receiver<Command>,
    paused: bool,
    next_frame: Instant,
    idle: Option<Instant>,
) -> Next {
    let (deadline, due) = if paused {
        (idle, Next::Idle)
    } else {
        (Some(next_frame), Next::Frame)
    };
    let Some(deadline) = deadline else {
        return commands.recv().map_or(Next::Exit, Next::Command);
    };
    match commands.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
        Ok(command) => Next::Command(command),
        Err(RecvTimeoutError::Timeout) => due,
        Err(RecvTimeoutError::Disconnected) => Next::Exit,
    }
}

impl Runner {
    /// Runs the session until it's told to stop or the core quits, and
    /// returns whether the core quit. The session is dropped when this
    /// returns.
    fn run(mut self, commands: &Receiver<Command>) -> bool {
        let mut next_idle = Instant::now() + ACHIEVEMENTS_IDLE_INTERVAL;
        let quit = loop {
            let idle = self.session.has_achievements().then_some(next_idle);
            match next(commands, self.paused, self.next_frame, idle) {
                Next::Command(Command::Stop(reply)) => {
                    let _ = reply.send(self.stop());
                    return false;
                }
                Next::Command(command) => self.handle(command),
                Next::Frame => self.run_frame(),
                Next::Idle => {
                    self.session.achievements_idle();
                    next_idle = Instant::now() + ACHIEVEMENTS_IDLE_INTERVAL;
                }
                Next::Exit => break false,
            }
            for event in self.session.take_achievement_events() {
                self.events.send(Event::Achievement(event));
            }
            self.update_info();
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

    /// Updates `info` to a frame size, aspect ratio or frame rate the core
    /// changed to.
    fn update_info(&self) {
        let mut info = self.info.lock().unwrap_or_else(|e| e.into_inner());
        if info.set_av(self.session.av_info()) {
            log::info!(
                "the core changed to {}x{} at {:.2} fps",
                info.base_width,
                info.base_height,
                info.fps
            );
        }
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
        if let Some(save_ram) = &mut self.save_ram {
            save_ram.write_if_due(&self.session);
        }
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
                let _ = reply.send(self.flush_save_ram());
            }
            Command::Resume => self.set_paused(false),
            Command::FastForward(speed) => {
                // Hardcore doesn't allow fast forward.
                self.speed = if self.session.hardcore_restricts() {
                    1
                } else {
                    speed.max(1)
                };
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
                    self.update_info();
                }
                let _ = reply.send(Ok(()));
            }
            Command::SaveState(path, reply) => {
                let _ = reply.send(self.session.save_state(&path));
            }
            Command::LoadState(path, reply) => {
                let _ = reply.send(self.session.load_state(&path));
            }
            Command::CaptureFrame(reply) => {
                let _ = reply.send(Ok(self.session.capture_frame()));
            }
            Command::AchievementStatus(reply) => {
                let _ = reply.send(Ok(self.session.achievement_status()));
            }
            Command::AchievementsResponse(id, status, body) => {
                self.session.achievements_response(id, status, &body);
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
        if !paused {
            self.next_frame = Instant::now();
        }
    }

    fn stop(&mut self) -> Result<(), SessionError> {
        self.set_paused(true);
        self.flush_save_ram()
    }

    fn flush_save_ram(&mut self) -> Result<(), SessionError> {
        match &mut self.save_ram {
            Some(save_ram) => Ok(save_ram.flush(&self.session)?),
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
        assert!(matches!(next(&receiver, false, overdue, None), Next::Frame));
        commands.send(Command::Resume).unwrap();
        assert!(matches!(
            next(&receiver, false, overdue, None),
            Next::Command(Command::Resume)
        ));
        assert!(matches!(next(&receiver, false, overdue, None), Next::Frame));
        drop(commands);
        assert!(matches!(next(&receiver, false, overdue, None), Next::Exit));
    }

    #[test]
    fn waits_for_the_next_frame_or_a_command() {
        let (commands, receiver) = mpsc::channel();
        let soon = Instant::now() + Duration::from_millis(20);
        assert!(matches!(next(&receiver, false, soon, None), Next::Frame));
        assert!(Instant::now() >= soon);
        commands.send(Command::Resume).unwrap();
        let later = Instant::now() + Duration::from_secs(60);
        assert!(matches!(
            next(&receiver, false, later, None),
            Next::Command(Command::Resume)
        ));
        commands.send(Command::Resume).unwrap();
        assert!(matches!(
            next(&receiver, true, Instant::now(), None),
            Next::Command(Command::Resume)
        ));
    }

    #[test]
    fn a_paused_session_wakes_for_achievements() {
        let (commands, receiver) = mpsc::channel();
        let idle = Instant::now() + Duration::from_millis(20);
        let later = Instant::now() + Duration::from_secs(60);
        assert!(matches!(
            next(&receiver, true, later, Some(idle)),
            Next::Idle
        ));
        assert!(Instant::now() >= idle);
        commands.send(Command::Resume).unwrap();
        assert!(matches!(
            next(&receiver, true, later, Some(later)),
            Next::Command(Command::Resume)
        ));
    }
}
