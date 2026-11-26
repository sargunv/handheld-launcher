//! A running core: loading it with its content, running frames, and saving.

use std::collections::HashMap;
use std::ffi::CString;
use std::fs;
use std::io;
use std::marker::PhantomData;
use std::path::{Path, PathBuf};
use std::ptr::NonNull;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use crate::achievements::{
    AchievementEvent, AchievementOptions, AchievementStatus, Achievements, CoreMemory, HttpClient,
};
use crate::audio::AudioSink;
use crate::content;
use crate::core::{Core, CoreLoadError};
use crate::ffi;
use crate::gl::{Framebuffer, GlApi, GlContext, GlError, NativeWindow, Target};
use crate::host::{self, Gpu, Host};
use crate::input::{InputState, MAX_PORTS};
use crate::options::{Options, opt_string};
use crate::save::write_atomically;
use crate::video::{CapturedFrame, Region, VideoSink};
use crate::vulkan::{VulkanContext, VulkanError};

/// Where a session's frames go.
pub enum VideoOutput {
    /// The session hands every frame to the sink, reading hardware-rendered
    /// frames back from the GPU.
    Sink(Box<dyn VideoSink>),
    /// The session presents every frame to a window itself, through an EGL
    /// context it owns, or a Vulkan device for cores that render with Vulkan,
    /// which presents only to Android windows. [`Session::set_window`]
    /// replaces the window or detaches it, keeping the context.
    Window {
        /// The window to start with. A session that starts without one uses
        /// the platform's default EGL display, so only windows on that
        /// display can be attached later, which on Android is every window.
        window: Option<NativeWindow>,
        /// The window's size in pixels.
        width: u32,
        height: u32,
        /// The bottom screen's share of the frame's width, centred, when a
        /// two-screen system's core draws it narrower than the top screen
        /// above it, so a lower window shows only those columns. `None`
        /// shows the whole width.
        lower_screen_width_share: Option<f32>,
    },
}

/// What a session needs to start.
pub struct SessionConfig {
    /// The core's shared library.
    pub core: PathBuf,
    /// The content to load, or `None` for cores that run without content.
    pub content: Option<PathBuf>,
    /// Where a zipped game is extracted when the core can't open the zip.
    /// The session owns the directory: it empties it before extracting, so it
    /// holds only the last game extracted. `None` passes zips to the core as
    /// they are.
    pub content_cache: Option<PathBuf>,
    /// Where the core looks for BIOS and other system files.
    pub system_dir: PathBuf,
    /// Where the core may write its own save files. The session creates it
    /// if it's missing.
    pub save_dir: PathBuf,
    pub video: VideoOutput,
    pub audio: Box<dyn AudioSink>,
    /// The controllers, which the platform layer keeps up to date.
    pub input: Arc<InputState>,
    /// Core option values to use instead of the core's defaults, by key.
    pub options: HashMap<String, String>,
    /// How the session takes part in RetroAchievements, if it does, and what
    /// sends its requests to the server.
    pub achievements: Option<(AchievementOptions, Box<dyn HttpClient>)>,
}

/// What a core says about itself.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SystemInfo {
    pub library_name: String,
    pub library_version: String,
    /// The content file extensions the core accepts, without dots.
    pub valid_extensions: Vec<String>,
    /// Whether the core reads content from its path rather than from memory.
    pub need_fullpath: bool,
    /// Whether the core wants archives passed to it unextracted.
    pub block_extract: bool,
}

/// The core's video and audio timing and geometry.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct AvInfo {
    /// The usual frame size.
    pub base_width: u32,
    pub base_height: u32,
    /// The largest frame size the core produces.
    pub max_width: u32,
    pub max_height: u32,
    /// The intended display aspect ratio, or 0 for the frame's own.
    pub aspect_ratio: f32,
    /// Frames per second.
    pub fps: f64,
    /// Audio sample rate in Hz, or 0 if the core has no audio.
    pub sample_rate: f64,
}

impl AvInfo {
    pub(crate) fn from_retro(info: &ffi::retro_system_av_info) -> Self {
        Self {
            base_width: info.geometry.base_width,
            base_height: info.geometry.base_height,
            max_width: info.geometry.max_width,
            max_height: info.geometry.max_height,
            aspect_ratio: info.geometry.aspect_ratio,
            fps: info.timing.fps,
            sample_rate: info.timing.sample_rate,
        }
    }
}

/// Why a session failed.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum SessionError {
    #[error("another session is running in this process")]
    AlreadyActive,
    #[error(transparent)]
    Core(#[from] CoreLoadError),
    #[error("the core needs content to run")]
    ContentRequired,
    #[error("can't read the content")]
    ReadContent(#[source] io::Error),
    #[error("the core couldn't load the content")]
    LoadGame,
    #[error("can't open the archive")]
    Archive(#[from] zip::result::ZipError),
    #[error("the archive has no file the core opens")]
    NoContentInArchive,
    #[error("can't read the save memory file")]
    ReadSaveRam(#[source] io::Error),
    #[error("can't set up OpenGL")]
    Gl(#[from] GlError),
    #[error("can't set up Vulkan")]
    Vulkan(#[from] VulkanError),
    #[error("the session doesn't present to a window")]
    NoWindowOutput,
    #[error("the session can't present to this kind of window here")]
    UnsupportedWindow,
    #[error("the session has stopped")]
    Stopped,
    #[error("the core doesn't support save states")]
    StatesUnsupported,
    #[error("the core's states only work in the session that made them")]
    StatesNotPersistent,
    #[error("the core couldn't save its state")]
    Serialize,
    #[error("the core couldn't restore the state")]
    Unserialize,
    #[error("hardcore mode doesn't allow that")]
    Hardcore,
    #[error(transparent)]
    Io(#[from] io::Error),
}

/// Only one session runs per process: cores keep global state, and libretro's
/// callbacks can't tell sessions apart.
static ACTIVE: AtomicBool = AtomicBool::new(false);

struct ActiveClaim;

impl ActiveClaim {
    fn claim() -> Result<Self, SessionError> {
        ACTIVE
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map(|_| ActiveClaim)
            .map_err(|_| SessionError::AlreadyActive)
    }
}

impl Drop for ActiveClaim {
    fn drop(&mut self) {
        ACTIVE.store(false, Ordering::Release);
    }
}

/// A core running content.
///
/// A session stays on the thread that created it, which is the thread the
/// core runs on and, for sessions with OpenGL, the thread its context is
/// current on.
pub struct Session {
    core: Core,
    host: NonNull<Host>,
    system_info: SystemInfo,
    initialized: bool,
    game_loaded: bool,
    /// The game's achievements, while the session takes part in
    /// RetroAchievements. Dropped before the game unloads.
    achievements: Option<Achievements>,
    /// Content the core may keep pointers into.
    _content: Option<(CString, Option<Vec<u8>>)>,
    _claim: ActiveClaim,
    _thread_bound: PhantomData<*mut ()>,
}

impl Session {
    /// Loads the core and its content, and gets them ready to run.
    ///
    /// # Safety
    ///
    /// `config.core` must be a libretro core. Loading it runs its code.
    pub unsafe fn new(config: SessionConfig) -> Result<Self, SessionError> {
        let claim = ActiveClaim::claim()?;
        // Cores open files in it from the start.
        fs::create_dir_all(&config.save_dir)?;
        // SAFETY: the caller vouches for the core.
        let core = unsafe { Core::load(&config.core)? };
        let lower = Region::bottom(match config.video {
            VideoOutput::Window {
                lower_screen_width_share,
                ..
            } => lower_screen_width_share,
            VideoOutput::Sink(_) => None,
        });
        let host = Box::new(Host::new(
            path_cstring(&config.system_dir),
            path_cstring(&config.save_dir),
            Options::new(config.options),
            config.input,
            config.audio,
            config.video,
            lower,
        ));
        let host = NonNull::from(Box::leak(host));
        // SAFETY: the host lives until the session's drop unregisters it, and
        // the session holds no reference to it while calling the core.
        unsafe { host::register(host.as_ptr()) };
        let mut session = Session {
            core,
            host,
            system_info: SystemInfo {
                library_name: String::new(),
                library_version: String::new(),
                valid_extensions: Vec::new(),
                need_fullpath: false,
                block_extract: false,
            },
            initialized: false,
            game_loaded: false,
            achievements: None,
            _content: None,
            _claim: claim,
            _thread_bound: PhantomData,
        };

        // SAFETY: libretro's start-up sequence, with callbacks of the right
        // signatures. The environment comes before retro_init and the rest
        // after it, as RetroArch sets them: some cores, such as Mesen, keep
        // the rest in state that retro_init creates, and crash without it.
        unsafe {
            (session.core.set_environment)(Some(host::environment));
            (session.core.init)();
            (session.core.set_video_refresh)(Some(host::video_refresh));
            (session.core.set_audio_sample)(Some(host::audio_sample));
            (session.core.set_audio_sample_batch)(Some(host::audio_sample_batch));
            (session.core.set_input_poll)(Some(host::input_poll));
            (session.core.set_input_state)(Some(host::input_state));
        }
        session.initialized = true;
        session.system_info = session.read_system_info();
        log::info!(
            "loaded core {} {}",
            session.system_info.library_name,
            session.system_info.library_version
        );

        let content = config
            .content
            .as_deref()
            .map(|path| {
                content::prepare(path, &session.system_info, config.content_cache.as_deref())
            })
            .transpose()?;
        session.load_content(content.as_deref())?;
        session.game_loaded = true;
        if let (Some((options, http)), Some(content)) = (config.achievements, content.as_deref()) {
            session.achievements = Achievements::start(&options, content, http);
            session.set_achievement_memory();
        }

        let mut av = ffi::retro_system_av_info::default();
        // SAFETY: called after retro_load_game, as libretro requires.
        unsafe { (session.core.get_system_av_info)(&mut av) };
        let host = session.host_mut();
        host.av_info = AvInfo::from_retro(&av);
        log::info!("core AV info: {:?}", host.av_info);
        host.reset_audio();

        session.set_up_video()?;
        // A RetroPad on every port, or on every port the core has, as it
        // may not expect calls for ports past its last.
        let ports = session.host().controller_ports.unwrap_or(MAX_PORTS);
        for port in 0..ports.min(MAX_PORTS) as u32 {
            // SAFETY: the game is loaded.
            unsafe { (session.core.set_controller_port_device)(port, ffi::RETRO_DEVICE_JOYPAD) };
        }
        Ok(session)
    }

    /// The host, for code that doesn't call into the core while it holds it.
    fn host_mut(&mut self) -> &mut Host {
        // SAFETY: the host lives as long as the session, and callbacks only
        // reach it while the core runs, which needs `&mut self` elsewhere.
        unsafe { self.host.as_mut() }
    }

    fn host(&self) -> &Host {
        // SAFETY: as in `host_mut`.
        unsafe { self.host.as_ref() }
    }

    fn read_system_info(&self) -> SystemInfo {
        // SAFETY: the struct is plain data the core fills in.
        let mut info: ffi::retro_system_info = unsafe { std::mem::zeroed() };
        // SAFETY: may be called at any time after loading.
        unsafe { (self.core.get_system_info)(&mut info) };
        // SAFETY: the core's strings are null or valid C strings.
        unsafe {
            SystemInfo {
                library_name: opt_string(info.library_name).unwrap_or_default(),
                library_version: opt_string(info.library_version).unwrap_or_default(),
                valid_extensions: opt_string(info.valid_extensions)
                    .map(|exts| {
                        exts.split('|')
                            .filter(|e| !e.is_empty())
                            .map(str::to_owned)
                            .collect()
                    })
                    .unwrap_or_default(),
                need_fullpath: info.need_fullpath,
                block_extract: info.block_extract,
            }
        }
    }

    fn load_content(&mut self, content: Option<&Path>) -> Result<(), SessionError> {
        let loaded = match content {
            None => {
                if !self.host().support_no_game {
                    return Err(SessionError::ContentRequired);
                }
                // SAFETY: the core declared it runs without content.
                unsafe { (self.core.load_game)(std::ptr::null()) }
            }
            Some(path) => {
                let data = if self.system_info.need_fullpath {
                    None
                } else {
                    Some(fs::read(path).map_err(SessionError::ReadContent)?)
                };
                let path = path_cstring(path);
                let info = ffi::retro_game_info {
                    path: path.as_ptr(),
                    data: data
                        .as_ref()
                        .map_or(std::ptr::null(), |d| d.as_ptr().cast()),
                    size: data.as_ref().map_or(0, Vec::len),
                    meta: std::ptr::null(),
                };
                self._content = Some((path, data));
                // SAFETY: the content outlives the session.
                unsafe { (self.core.load_game)(&info) }
            }
        };
        match (loaded, self.host_mut().hw_refused.take()) {
            (true, _) => Ok(()),
            (false, Some(refused)) => Err(refused),
            (false, None) => Err(SessionError::LoadGame),
        }
    }

    /// Creates the GL context and framebuffer when the core renders with
    /// OpenGL or frames go to a window, or the Vulkan context when the core
    /// renders with Vulkan, and lets a hardware-rendered core set up its
    /// state.
    fn set_up_video(&mut self) -> Result<(), SessionError> {
        let host = self.host_mut();
        let target = match &host.video {
            VideoOutput::Window {
                window,
                width,
                height,
                ..
            } => Target::Window(window.as_ref().map(|w| (w, *width, *height))),
            VideoOutput::Sink(_) => Target::Headless,
        };
        let hw = host.hw_render;
        if let Some(cb) = hw.filter(|cb| cb.context_type == ffi::RETRO_HW_CONTEXT_VULKAN) {
            let context = VulkanContext::new(host.negotiation.as_ref(), target)?;
            host.vulkan = Some(context);
            if let Some(reset) = cb.context_reset {
                // SAFETY: the context exists, as libretro requires; no host
                // reference is live.
                unsafe { reset() };
            }
            return Ok(());
        }
        let context = match (hw, target) {
            (Some(cb), target) => {
                let api = host::hw_api(&cb).expect("accepted in SET_HW_RENDER");
                GlContext::new(api, target, cb.debug_context)?
            }
            (None, target @ Target::Window(_)) => {
                GlContext::new(GlApi::Gles { major: 3, minor: 0 }, target, false)?
            }
            (None, Target::Headless) => return Ok(()),
        };
        let mut gpu = Gpu::new(context);
        if let Some(cb) = hw {
            let av = host.av_info;
            let fb = Framebuffer::new(
                &gpu.context.gl,
                av.max_width.max(av.base_width),
                av.max_height.max(av.base_height),
                cb.depth,
                cb.stencil,
            )?;
            gpu.hw = Some(fb);
        }
        host.gpu = Some(gpu);
        if let Some(reset) = hw.and_then(|cb| cb.context_reset) {
            // SAFETY: the context is current and the framebuffer exists, as
            // libretro requires; no host reference is live.
            unsafe { reset() };
        }
        Ok(())
    }

    /// Runs the core for one frame: it reads input, then produces a frame and
    /// its audio.
    pub fn run_frame(&mut self) {
        let host = self.host_mut();
        host.begin_frame_input();
        if let Some(vulkan) = &mut host.vulkan {
            vulkan.begin_frame();
        }
        let frame_time = host.frame_time;
        if let Some(ffi::retro_frame_time_callback {
            callback: Some(callback),
            reference,
        }) = frame_time
        {
            // The session runs at the core's own rate, so every frame takes
            // the core's reference time.
            // SAFETY: the core registered the callback.
            unsafe { callback(reference) };
        }
        // SAFETY: the game is loaded and no host reference is live.
        unsafe { (self.core.run)() };
        self.host_mut().flush_audio();
        if let Some(achievements) = &self.achievements {
            // Some cores only have memory once they've run.
            if !achievements.has_memory() {
                self.set_achievement_memory();
            }
            achievements.do_frame();
        }
    }

    /// Lays out the core's memory for achievements to read.
    fn set_achievement_memory(&self) {
        if let Some(achievements) = &self.achievements {
            let core = CoreMemory {
                data: self.core.get_memory_data,
                size: self.core.get_memory_size,
            };
            // A copy, as the core's memory functions may call back into the
            // host.
            let map = self.host().memory_map.clone();
            achievements.set_memory(map.as_deref(), core);
        }
    }

    /// Lets achievements retry requests and keep the server's session alive
    /// while the game is paused.
    pub fn achievements_idle(&self) {
        if let Some(achievements) = &self.achievements {
            achievements.idle();
        }
    }

    /// Whether the session takes part in RetroAchievements.
    pub fn has_achievements(&self) -> bool {
        self.achievements.is_some()
    }

    /// Hands a RetroAchievements request's response to the session: the HTTP
    /// status and body, or `None` when the server couldn't be reached.
    pub fn achievements_response(&self, id: u64, status: Option<u16>, body: &[u8]) {
        if let Some(achievements) = &self.achievements {
            achievements.respond(id, status, body);
        }
    }

    /// Where the game's achievements stand.
    pub fn achievement_status(&self) -> AchievementStatus {
        self.achievements
            .as_ref()
            .map_or(AchievementStatus::Off, Achievements::status)
    }

    /// What happened to the game's achievements since the last call.
    pub fn take_achievement_events(&self) -> Vec<AchievementEvent> {
        self.achievements
            .as_ref()
            .map(Achievements::take_events)
            .unwrap_or_default()
    }

    /// Whether hardcore's rules apply: no loading states and no fast forward,
    /// while a game with achievements plays in hardcore.
    pub fn hardcore_restricts(&self) -> bool {
        self.achievements
            .as_ref()
            .is_some_and(Achievements::restricts)
    }

    /// What the core says about itself.
    pub fn system_info(&self) -> &SystemInfo {
        &self.system_info
    }

    /// The core's current timing and geometry.
    pub fn av_info(&self) -> AvInfo {
        self.host().av_info
    }

    /// Whether the core asked to quit, as when a game offers an exit.
    pub fn shutdown_requested(&self) -> bool {
        self.host().shutdown_requested
    }

    /// Tells the session the window it presents to changed size.
    pub fn resize_window(&mut self, width: u32, height: u32) {
        let host = self.host_mut();
        if let VideoOutput::Window {
            width: w,
            height: h,
            ..
        } = &mut host.video
        {
            (*w, *h) = (width, height);
            if let Some(gpu) = &host.gpu {
                gpu.context.resize(width, height);
            }
            if let Some(vulkan) = &mut host.vulkan {
                vulkan.resize(width, height);
            }
            host.redraw();
        }
    }

    /// Replaces the window the session presents to, or detaches it with
    /// `None`, keeping the GL context and everything in it. The new window
    /// shows the last frame at once. On an error, the session has no window.
    ///
    /// When this returns, the session no longer uses the old window.
    ///
    /// # Safety
    ///
    /// The window outlives its attachment: until the session drops or this
    /// is called again.
    pub unsafe fn set_window(
        &mut self,
        window: Option<(NativeWindow, u32, u32)>,
    ) -> Result<(), SessionError> {
        let host = self.host_mut();
        let VideoOutput::Window {
            window: current,
            width,
            height,
            ..
        } = &mut host.video
        else {
            return Err(SessionError::NoWindowOutput);
        };
        *current = window.map(|(w, ..)| w);
        if let Some((_, w, h)) = window {
            (*width, *height) = (w, h);
        }
        let window = window
            .as_ref()
            .map(|(w, width, height)| (w, *width, *height));
        let result: Result<(), SessionError> = match (&mut host.gpu, &mut host.vulkan) {
            (Some(gpu), _) => gpu.context.set_window(window).map_err(Into::into),
            (None, Some(vulkan)) => vulkan.set_window(window).map_err(Into::into),
            (None, None) => return Ok(()),
        };
        if result.is_err() {
            *current = None;
        }
        result?;
        host.redraw();
        Ok(())
    }

    /// Replaces the lower window, or detaches it with `None`. While a lower
    /// window is attached, the main window shows the top half of each frame
    /// and the lower window its bottom half, as a two-screen system's core
    /// draws its screens stacked. The new window shows the last frame at once.
    /// On an error, the session has no lower window.
    ///
    /// When this returns, the session no longer uses the old lower window.
    ///
    /// # Safety
    ///
    /// The window outlives its attachment: until the session drops or this
    /// is called again.
    pub unsafe fn set_lower_window(
        &mut self,
        window: Option<(NativeWindow, u32, u32)>,
    ) -> Result<(), SessionError> {
        let host = self.host_mut();
        if !matches!(host.video, VideoOutput::Window { .. }) {
            return Err(SessionError::NoWindowOutput);
        }
        let window = window
            .as_ref()
            .map(|(w, width, height)| (w, *width, *height));
        match (&mut host.gpu, &mut host.vulkan) {
            (Some(gpu), _) => gpu.context.set_lower_window(window)?,
            (None, Some(vulkan)) => vulkan.set_lower_window(window)?,
            (None, None) if window.is_some() => return Err(SessionError::UnsupportedWindow),
            (None, None) => return Ok(()),
        }
        host.redraw();
        Ok(())
    }

    /// Tells the session it runs frames at `speed` times the core's own
    /// rate, as when it paces them to the display, so that it resamples the
    /// audio to play in step with them.
    pub(crate) fn set_speed(&mut self, speed: f64) {
        self.host_mut().audio_speed = speed;
    }

    /// Drops the core's audio while `muted`, rather than playing it, as when
    /// the session runs faster than the audio could keep up with.
    pub fn set_audio_muted(&mut self, muted: bool) {
        self.host_mut().audio_muted = muted;
    }

    /// Tells the audio sink whether the session is paused, so it can stop its
    /// output while no audio comes.
    pub fn set_audio_paused(&mut self, paused: bool) {
        self.host_mut().audio_sink.set_paused(paused);
    }

    /// A copy of the last frame, or `None` before the first.
    pub fn capture_frame(&mut self) -> Option<CapturedFrame> {
        self.host_mut().capture()
    }

    /// The core's battery-backed save memory, if it has any.
    pub fn save_ram(&self) -> Option<&[u8]> {
        // SAFETY: the core's memory stays valid while the game is loaded,
        // and the core can't run while this borrow lives.
        unsafe {
            let size = (self.core.get_memory_size)(ffi::RETRO_MEMORY_SAVE_RAM);
            let data = (self.core.get_memory_data)(ffi::RETRO_MEMORY_SAVE_RAM);
            (size > 0 && !data.is_null())
                .then(|| std::slice::from_raw_parts(data.cast::<u8>(), size))
        }
    }

    pub(crate) fn save_ram_mut(&mut self) -> Option<&mut [u8]> {
        // SAFETY: as in `save_ram`.
        unsafe {
            let size = (self.core.get_memory_size)(ffi::RETRO_MEMORY_SAVE_RAM);
            let data = (self.core.get_memory_data)(ffi::RETRO_MEMORY_SAVE_RAM);
            (size > 0 && !data.is_null())
                .then(|| std::slice::from_raw_parts_mut(data.cast::<u8>(), size))
        }
    }

    /// The core's complete state, which [`Session::unserialize_state`]
    /// restores.
    pub fn serialize_state(&mut self) -> Result<Vec<u8>, SessionError> {
        // SAFETY: the game is loaded and no host reference is live.
        let size = unsafe { (self.core.serialize_size)() };
        if size == 0 {
            return Err(SessionError::StatesUnsupported);
        }
        let mut state = vec![0; size];
        // SAFETY: `state` has the size the core asked for.
        if unsafe { (self.core.serialize)(state.as_mut_ptr().cast(), size) } {
            Ok(state)
        } else {
            Err(SessionError::Serialize)
        }
    }

    /// Restores a state from [`Session::serialize_state`]. Hardcore mode
    /// doesn't allow it.
    pub fn unserialize_state(&mut self, state: &[u8]) -> Result<(), SessionError> {
        if self.hardcore_restricts() {
            return Err(SessionError::Hardcore);
        }
        // SAFETY: the game is loaded, the core only reads `state`, and no
        // host reference is live.
        if !unsafe { (self.core.unserialize)(state.as_ptr().cast(), state.len()) } {
            return Err(SessionError::Unserialize);
        }
        if let Some(achievements) = &self.achievements {
            achievements.state_loaded();
        }
        Ok(())
    }

    /// Saves the core's state to `path`, replacing the file only once the new
    /// contents are on disk.
    pub fn save_state(&mut self, path: &Path) -> Result<(), SessionError> {
        if !self.host().states_persist() {
            return Err(SessionError::StatesNotPersistent);
        }
        let state = self.serialize_state()?;
        Ok(write_atomically(path, &state)?)
    }

    /// Restores a state that [`Session::save_state`] wrote. Hardcore mode
    /// doesn't allow it.
    pub fn load_state(&mut self, path: &Path) -> Result<(), SessionError> {
        if self.hardcore_restricts() {
            return Err(SessionError::Hardcore);
        }
        if !self.host().states_persist() {
            return Err(SessionError::StatesNotPersistent);
        }
        let state = fs::read(path)?;
        self.unserialize_state(&state)
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        // The client reads the core's memory until it's gone.
        self.achievements = None;
        if self.game_loaded {
            let host = self.host_mut();
            let destroy = host.hw_render.and_then(|cb| cb.context_destroy);
            let ready = match (&host.gpu, &mut host.vulkan) {
                (Some(gpu), _) => gpu.context.make_current().is_ok(),
                (None, Some(vulkan)) => {
                    // The core frees what the session's work may still use.
                    vulkan.release_image();
                    true
                }
                (None, None) => false,
            };
            if let (Some(destroy), true) = (destroy, ready) {
                // SAFETY: the context is current; no host reference is live.
                unsafe { destroy() };
            }
            // SAFETY: the game is loaded.
            unsafe { (self.core.unload_game)() };
        }
        if self.initialized {
            // SAFETY: retro_init ran.
            unsafe { (self.core.deinit)() };
        }
        host::unregister();
        // SAFETY: the host came from `Box::leak`, and the core can no longer
        // reach it.
        drop(unsafe { Box::from_raw(self.host.as_ptr()) });
    }
}

fn path_cstring(path: &Path) -> CString {
    use std::os::unix::ffi::OsStrExt;
    CString::new(path.as_os_str().as_bytes()).unwrap_or_else(|_| {
        log::warn!("path {} contains a null byte", path.display());
        CString::default()
    })
}
