//! The frontend side of the libretro API: the state the core's callbacks
//! reach, and the callbacks themselves.
//!
//! libretro's callbacks carry no user data, so the session registers its
//! [`Host`] in a thread-local while it's alive. Callbacks from other threads
//! find no host and do nothing.

use std::cell::Cell;
use std::collections::HashSet;
use std::ffi::{CStr, CString, VaList, c_char, c_int, c_uint, c_void};
use std::sync::Arc;
use std::time::Instant;

use crate::audio::{AudioPipeline, AudioSink};
use crate::cstr::opt_string;
use crate::ffi;
use crate::gl::{self, GlApi};
use crate::input::{self, InputState, MAX_PORTS, PortSnapshot, Presses};
use crate::options::Options;
use crate::renderer::Renderer;
use crate::session::SessionError;
use crate::video::{self, CapturedFrame, Frame, PixelFormat, Region, VideoSink};
use crate::vulkan::{self, Negotiation, VulkanError};
use crate::{AvInfo, GlError};

thread_local! {
    static HOST: Cell<*mut Host> = const { Cell::new(std::ptr::null_mut()) };
}

/// Where the host's frames go.
pub(crate) enum Output {
    /// To the sink, read back from the GPU when the core renders there.
    Sink(Box<dyn VideoSink>),
    /// To the renderer's windows, with `lower` of each frame on the lower
    /// window.
    Window { lower: Region },
}

/// Everything the core's callbacks read and write.
pub(crate) struct Host {
    pub(crate) system_dir: CString,
    pub(crate) save_dir: CString,
    pub(crate) pixel_format: PixelFormat,
    pub(crate) av_info: AvInfo,
    pub(crate) options: Options,
    pub(crate) input: Arc<InputState>,
    input_snapshot: [PortSnapshot; MAX_PORTS],
    /// The buttons pressed since the previous frame, held for this one.
    frame_presses: [Presses; MAX_PORTS],
    pub(crate) audio_sink: Box<dyn AudioSink>,
    pub(crate) audio: Option<AudioPipeline>,
    /// The core rate `audio` resamples from.
    audio_rate: f64,
    audio_samples: Vec<i16>,
    /// The rate the session runs frames at, over the core's own.
    pub(crate) audio_speed: f64,
    /// Whether the core's audio is dropped rather than played.
    pub(crate) audio_muted: bool,
    pub(crate) output: Output,
    pub(crate) renderer: Option<Renderer>,
    /// The core's hardware render request, with our callbacks filled in.
    pub(crate) hw_render: Option<ffi::retro_hw_render_callback>,
    /// The Vulkan context negotiation interface the core set.
    pub(crate) negotiation: Option<Negotiation>,
    /// Why the session turned down the core's hardware render request, when
    /// it had to.
    pub(crate) hw_refused: Option<SessionError>,
    /// The last software frame, converted, for repeats and presenting, or
    /// the last hardware frame read back for the sink.
    frame: Vec<u8>,
    frame_size: (u32, u32),
    /// Whether the last frame was hardware-rendered.
    last_frame_hw: bool,
    pub(crate) support_no_game: bool,
    pub(crate) frame_time: Option<ffi::retro_frame_time_callback>,
    pub(crate) shutdown_requested: bool,
    /// How many controller ports the core declared with
    /// `SET_CONTROLLER_INFO`, if it did.
    pub(crate) controller_ports: Option<usize>,
    /// The `RETRO_SERIALIZATION_QUIRK_*` flags the core declared.
    serialization_quirks: u64,
    /// The memory map the core declared with `SET_MEMORY_MAPS`, copied, for
    /// achievements to read the console's memory by its addresses.
    pub(crate) memory_map: Option<Vec<ffi::retro_memory_descriptor>>,
    unhandled: HashSet<c_uint>,
}

impl Host {
    pub(crate) fn new(
        system_dir: CString,
        save_dir: CString,
        options: Options,
        input: Arc<InputState>,
        audio_sink: Box<dyn AudioSink>,
        output: Output,
    ) -> Self {
        Self {
            system_dir,
            save_dir,
            pixel_format: PixelFormat::default(),
            av_info: AvInfo::default(),
            options,
            input,
            input_snapshot: Default::default(),
            frame_presses: Default::default(),
            audio_sink,
            audio: None,
            audio_rate: 0.0,
            audio_samples: Vec::new(),
            audio_speed: 1.0,
            audio_muted: false,
            output,
            renderer: None,
            hw_render: None,
            negotiation: None,
            hw_refused: None,
            frame: Vec::new(),
            frame_size: (0, 0),
            last_frame_hw: false,
            support_no_game: false,
            frame_time: None,
            shutdown_requested: false,
            controller_ports: None,
            serialization_quirks: 0,
            memory_map: None,
            unhandled: HashSet::new(),
        }
    }

    /// Starts a frame's input: takes the presses since the last frame, and a
    /// snapshot for the core to read.
    pub(crate) fn begin_frame_input(&mut self) {
        self.frame_presses = self.input.take_presses();
        self.poll_input();
    }

    /// Takes a snapshot of the controllers for the core to read.
    pub(crate) fn poll_input(&mut self) {
        self.input_snapshot = self.input.snapshot(&self.frame_presses);
    }

    /// Rebuilds the audio pipeline for the core's current rate, unless it
    /// already resamples from that rate.
    pub(crate) fn reset_audio(&mut self) {
        if self.audio.is_some() && self.audio_rate == self.av_info.sample_rate {
            return;
        }
        self.audio_rate = self.av_info.sample_rate;
        self.audio = AudioPipeline::new(self.av_info.sample_rate, self.audio_sink.sample_rate());
        if self.audio.is_none() && self.av_info.sample_rate > 0.0 {
            log::warn!("the session can't play the core's audio");
        }
    }

    /// Adopts timing and geometry the core set mid-frame: the audio that
    /// came before plays at the old rate, and the hardware framebuffer grows
    /// at once, since the core may draw at the new size before the frame
    /// ends.
    fn apply_av_info(&mut self, av: AvInfo) {
        self.flush_audio();
        self.av_info = av;
        self.reset_audio();
        if let Some(renderer) = &mut self.renderer {
            renderer.grow(
                av.max_width.max(av.base_width),
                av.max_height.max(av.base_height),
            );
        }
    }

    /// Resamples this frame's audio into the sink.
    pub(crate) fn flush_audio(&mut self) {
        if let Some(audio) = &mut self.audio
            && !self.audio_muted
        {
            audio.push(&self.audio_samples);
            audio.flush(self.audio_sink.as_mut(), self.audio_speed);
        }
        self.audio_samples.clear();
    }

    fn video_refresh(&mut self, data: *const c_void, width: u32, height: u32, pitch: usize) {
        if data == ffi::RETRO_HW_FRAME_BUFFER_VALID {
            self.last_frame_hw = true;
            self.frame_size = (width, height);
            self.show_hw_frame(width, height);
            return;
        }
        if data.is_null() {
            // The core repeats its last frame.
            if self.last_frame_hw {
                let (w, h) = self.frame_size;
                self.show_hw_frame(w, h);
            } else {
                self.show_sw_frame();
            }
            return;
        }
        self.last_frame_hw = false;
        let format = self.pixel_format;
        let (w, h) = (width as usize, height as usize);
        let len = if h == 0 {
            0
        } else {
            pitch * (h - 1) + w * format.bytes_per_pixel()
        };
        if pitch < w * format.bytes_per_pixel() {
            log::error!("core sent a frame with pitch {pitch} for width {width}");
            return;
        }
        // SAFETY: libretro guarantees `height` rows `pitch` bytes apart.
        let src = unsafe { std::slice::from_raw_parts(data.cast::<u8>(), len) };
        video::convert_frame(format, src, w, h, pitch, &mut self.frame);
        self.frame_size = (width, height);
        self.show_sw_frame();
    }

    /// The renderer, when it presents to windows.
    pub(crate) fn window_renderer(&mut self) -> Option<&mut Renderer> {
        match self.output {
            Output::Window { .. } => self.renderer.as_mut(),
            Output::Sink(_) => None,
        }
    }

    /// Presents the last frame again, as when a window is attached.
    pub(crate) fn redraw(&mut self) {
        if !matches!(self.output, Output::Window { .. }) {
            return;
        }
        if self.last_frame_hw {
            let (w, h) = self.frame_size;
            self.show_hw_frame(w, h);
        } else {
            self.show_sw_frame();
        }
    }

    /// A copy of the last frame, reading it back from the GPU if the core
    /// rendered it there.
    pub(crate) fn capture(&mut self) -> Option<CapturedFrame> {
        let (width, height) = self.frame_size;
        if width == 0 || height == 0 {
            return None;
        }
        if !self.last_frame_hw {
            return Some(CapturedFrame {
                width,
                height,
                pixels: self.frame.clone(),
            });
        }
        let mut pixels = Vec::new();
        let (width, height) = self
            .renderer
            .as_mut()?
            .read_hw_frame(width, height, &mut pixels)?;
        Some(CapturedFrame {
            width,
            height,
            pixels,
        })
    }

    fn show_sw_frame(&mut self) {
        let (width, height) = self.frame_size;
        if width == 0 || height == 0 {
            return;
        }
        let frame = Frame {
            width,
            height,
            pixels: &self.frame,
        };
        match &mut self.output {
            Output::Sink(sink) => sink.frame(frame),
            Output::Window { lower } => {
                if let Some(renderer) = &mut self.renderer {
                    renderer.present_sw_frame(frame, self.av_info.aspect_ratio, *lower);
                }
            }
        }
    }

    fn show_hw_frame(&mut self, width: u32, height: u32) {
        let Some(renderer) = &mut self.renderer else {
            return;
        };
        match &mut self.output {
            Output::Sink(sink) => {
                if let Some((width, height)) =
                    renderer.read_hw_frame(width, height, &mut self.frame)
                {
                    sink.frame(Frame {
                        width,
                        height,
                        pixels: &self.frame,
                    });
                }
            }
            Output::Window { lower } => {
                renderer.present_hw_frame(width, height, self.av_info.aspect_ratio, *lower);
            }
        }
    }

    fn environment(&mut self, cmd: c_uint, data: *mut c_void) -> bool {
        // libretro.h's constants for experimental commands carry the
        // experimental bit, so commands match whole.
        // SAFETY (whole match): each command's `data` has the type libretro.h
        // documents for it, and is checked for null before use.
        unsafe {
            match cmd {
                ffi::RETRO_ENVIRONMENT_GET_CAN_DUPE => write(data, true),
                ffi::RETRO_ENVIRONMENT_GET_OVERSCAN => write(data, false),
                ffi::RETRO_ENVIRONMENT_SET_MESSAGE => {
                    if let Some(msg) = data.cast::<ffi::retro_message>().as_ref() {
                        log::info!("core message: {}", opt_string(msg.msg).unwrap_or_default());
                    }
                    true
                }
                ffi::RETRO_ENVIRONMENT_SET_MESSAGE_EXT => {
                    if let Some(msg) = data.cast::<ffi::retro_message_ext>().as_ref() {
                        log::info!("core message: {}", opt_string(msg.msg).unwrap_or_default());
                    }
                    true
                }
                ffi::RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION => write::<c_uint>(data, 1),
                ffi::RETRO_ENVIRONMENT_SHUTDOWN => {
                    self.shutdown_requested = true;
                    true
                }
                ffi::RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL => true,
                ffi::RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY
                | ffi::RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY => {
                    write(data, self.system_dir.as_ptr())
                }
                ffi::RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY => write(data, self.save_dir.as_ptr()),
                ffi::RETRO_ENVIRONMENT_SET_PIXEL_FORMAT => {
                    let Some(&format) = data.cast::<ffi::retro_pixel_format>().as_ref() else {
                        return false;
                    };
                    match PixelFormat::from_retro(format) {
                        Some(format) => {
                            log::debug!("core pixel format: {format:?}");
                            self.pixel_format = format;
                            true
                        }
                        None => {
                            log::warn!("core asked for unsupported pixel format {format}");
                            false
                        }
                    }
                }
                ffi::RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS => true,
                ffi::RETRO_ENVIRONMENT_SET_HW_RENDER => {
                    self.set_hw_render(data.cast::<ffi::retro_hw_render_callback>())
                }
                ffi::RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER => {
                    let preferred = if cfg!(target_os = "android") {
                        ffi::RETRO_HW_CONTEXT_OPENGLES3
                    } else {
                        ffi::RETRO_HW_CONTEXT_OPENGL
                    };
                    write::<c_uint>(data, preferred)
                }
                ffi::RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT => true,
                ffi::RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE => match &self.renderer {
                    Some(Renderer::Vulkan(vulkan)) => write(data, vulkan.interface()),
                    _ => false,
                },
                ffi::RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT => {
                    let Some(query) = data
                        .cast::<ffi::retro_hw_render_context_negotiation_interface>()
                        .as_mut()
                    else {
                        return false;
                    };
                    query.interface_version = if query.interface_type
                        == ffi::RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN
                    {
                        vulkan::NEGOTIATION_VERSION
                    } else {
                        0
                    };
                    true
                }
                ffi::RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE => {
                    self.negotiation = Negotiation::read(data);
                    self.negotiation.is_some()
                }
                ffi::RETRO_ENVIRONMENT_GET_VARIABLE => {
                    let Some(var) = data.cast::<ffi::retro_variable>().as_mut() else {
                        return false;
                    };
                    if var.key.is_null() {
                        return false;
                    }
                    let key = CStr::from_ptr(var.key).to_string_lossy();
                    match self.options.get(&key) {
                        Some(value) => {
                            var.value = value;
                            true
                        }
                        None => {
                            var.value = std::ptr::null();
                            false
                        }
                    }
                }
                ffi::RETRO_ENVIRONMENT_SET_VARIABLE => {
                    let Some(var) = data.cast::<ffi::retro_variable>().as_ref() else {
                        // A null `data` asks whether the call is supported.
                        return true;
                    };
                    match (opt_string(var.key), opt_string(var.value)) {
                        (Some(key), Some(value)) => self.options.set(&key, &value),
                        _ => false,
                    }
                }
                ffi::RETRO_ENVIRONMENT_SET_VARIABLES => {
                    self.options.declare_v0(data.cast());
                    true
                }
                ffi::RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE => {
                    let updated = self.options.take_updated();
                    write(data, updated)
                }
                ffi::RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION => write::<c_uint>(data, 2),
                ffi::RETRO_ENVIRONMENT_SET_CORE_OPTIONS => {
                    self.options.declare_v1(data.cast());
                    true
                }
                ffi::RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL => {
                    if let Some(intl) = data.cast::<ffi::retro_core_options_intl>().as_ref() {
                        self.options.declare_v1(intl.us);
                    }
                    true
                }
                ffi::RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2 => {
                    self.options.declare_v2(data.cast());
                    // The session registered the options, but doesn't show
                    // their categories.
                    false
                }
                ffi::RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL => {
                    if let Some(intl) = data.cast::<ffi::retro_core_options_v2_intl>().as_ref() {
                        self.options.declare_v2(intl.us);
                    }
                    false
                }
                ffi::RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY
                | ffi::RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK => true,
                ffi::RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME => {
                    if let Some(&supported) = data.cast::<bool>().as_ref() {
                        self.support_no_game = supported;
                    }
                    true
                }
                ffi::RETRO_ENVIRONMENT_GET_LOG_INTERFACE => write(
                    data,
                    ffi::retro_log_callback {
                        log: Some(log_printf),
                    },
                ),
                ffi::RETRO_ENVIRONMENT_GET_PERF_INTERFACE => write(
                    data,
                    ffi::retro_perf_callback {
                        get_time_usec: Some(perf_get_time_usec),
                        get_cpu_features: Some(perf_get_cpu_features),
                        get_perf_counter: Some(perf_get_counter),
                        perf_register: Some(perf_noop),
                        perf_start: Some(perf_noop),
                        perf_stop: Some(perf_noop),
                        perf_log: Some(perf_log),
                    },
                ),
                ffi::RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK => {
                    match data.cast::<ffi::retro_frame_time_callback>().as_ref() {
                        Some(cb) => {
                            self.frame_time = Some(*cb);
                            true
                        }
                        None => false,
                    }
                }
                ffi::RETRO_ENVIRONMENT_SET_GEOMETRY => {
                    let Some(g) = data.cast::<ffi::retro_game_geometry>().as_ref() else {
                        return false;
                    };
                    // Only the base size and aspect may change here.
                    self.av_info.base_width = g.base_width;
                    self.av_info.base_height = g.base_height;
                    self.av_info.aspect_ratio = g.aspect_ratio;
                    log::debug!("core geometry: {:?}", self.av_info);
                    true
                }
                ffi::RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO => {
                    let Some(info) = data.cast::<ffi::retro_system_av_info>().as_ref() else {
                        return false;
                    };
                    self.apply_av_info(AvInfo::from_retro(info));
                    log::debug!("core AV info: {:?}", self.av_info);
                    true
                }
                ffi::RETRO_ENVIRONMENT_GET_LANGUAGE => {
                    write::<ffi::retro_language>(data, ffi::RETRO_LANGUAGE_ENGLISH)
                }
                ffi::RETRO_ENVIRONMENT_GET_INPUT_BITMASKS => true,
                ffi::RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES => write::<u64>(
                    data,
                    (1 << ffi::RETRO_DEVICE_JOYPAD)
                        | (1 << ffi::RETRO_DEVICE_ANALOG)
                        | (1 << ffi::RETRO_DEVICE_POINTER),
                ),
                ffi::RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS => {
                    write::<c_uint>(data, MAX_PORTS as c_uint)
                }
                ffi::RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE => write::<c_int>(data, 0b11),
                ffi::RETRO_ENVIRONMENT_GET_FASTFORWARDING => write(data, false),
                ffi::RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS => {
                    let Some(&quirks) = data.cast::<u64>().as_ref() else {
                        return false;
                    };
                    self.serialization_quirks = quirks;
                    true
                }
                ffi::RETRO_ENVIRONMENT_GET_SAVESTATE_CONTEXT => {
                    write::<c_int>(data, ffi::RETRO_SAVESTATE_CONTEXT_NORMAL as c_int)
                }
                ffi::RETRO_ENVIRONMENT_SET_CONTROLLER_INFO => {
                    let mut info = data.cast::<ffi::retro_controller_info>().cast_const();
                    let mut ports = 0;
                    while !info.is_null() && !(*info).types.is_null() {
                        ports += 1;
                        info = info.add(1);
                    }
                    log::debug!("core controller ports: {ports}");
                    self.controller_ports = Some(ports);
                    true
                }
                ffi::RETRO_ENVIRONMENT_SET_MEMORY_MAPS => {
                    let Some(map) = data.cast::<ffi::retro_memory_map>().as_ref() else {
                        return false;
                    };
                    let descriptors = if map.descriptors.is_null() {
                        Vec::new()
                    } else {
                        // The descriptors point into the core's memory, which
                        // lives while the game is loaded; the array itself
                        // may not.
                        std::slice::from_raw_parts(map.descriptors, map.num_descriptors as usize)
                            .to_vec()
                    };
                    log::debug!("core memory map: {} descriptors", descriptors.len());
                    self.memory_map = Some(descriptors);
                    true
                }
                // Information the session doesn't use yet, accepted so cores
                // don't take a fallback path.
                ffi::RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO
                | ffi::RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS
                | ffi::RETRO_ENVIRONMENT_SET_PROC_ADDRESS_CALLBACK => true,
                _ => {
                    if self.unhandled.insert(cmd) {
                        log::debug!("core used unsupported environment command {cmd:#x}");
                    }
                    false
                }
            }
        }
    }

    /// Whether the core's states may outlive the session that made them. A
    /// core with the single-session quirk can only restore its states in the
    /// same run.
    pub(crate) fn states_persist(&self) -> bool {
        self.serialization_quirks & u64::from(ffi::RETRO_SERIALIZATION_QUIRK_SINGLE_SESSION) == 0
    }

    /// Accepts a hardware render request the session can serve, and fills in
    /// the frontend's callbacks.
    ///
    /// # Safety
    ///
    /// `cb` is null or points to a valid `retro_hw_render_callback`.
    unsafe fn set_hw_render(&mut self, cb: *mut ffi::retro_hw_render_callback) -> bool {
        // SAFETY: forwarded from the caller.
        let Some(cb) = (unsafe { cb.as_mut() }) else {
            return false;
        };
        if cb.context_type == ffi::RETRO_HW_CONTEXT_VULKAN {
            if let Err(e) = vulkan::entry() {
                log::warn!("core asked for Vulkan, but {e}");
                self.hw_refused = Some(VulkanError::Unavailable(e).into());
                return false;
            }
        } else {
            if hw_api(cb).is_none() {
                log::warn!(
                    "core asked for unsupported hardware context {}",
                    cb.context_type
                );
                return false;
            }
            if let Err(e) = gl::Egl::get() {
                log::warn!("core asked for hardware rendering, but {e}");
                self.hw_refused = Some(GlError::Unavailable(e).into());
                return false;
            }
            cb.get_current_framebuffer = Some(get_current_framebuffer);
            cb.get_proc_address = Some(get_proc_address);
        }
        log::debug!(
            "core hardware context: type {} version {}.{}, depth {}, stencil {}, bottom-left origin {}",
            cb.context_type,
            cb.version_major,
            cb.version_minor,
            cb.depth,
            cb.stencil,
            cb.bottom_left_origin
        );
        self.hw_render = Some(*cb);
        true
    }
}

/// The GL API a hardware render request asks for, if the session serves it.
pub(crate) fn hw_api(cb: &ffi::retro_hw_render_callback) -> Option<GlApi> {
    match cb.context_type {
        ffi::RETRO_HW_CONTEXT_OPENGL => Some(GlApi::Gl { core: None }),
        // Core profiles start at 3.2.
        ffi::RETRO_HW_CONTEXT_OPENGL_CORE => Some(GlApi::Gl {
            core: Some((cb.version_major, cb.version_minor).max((3, 2))),
        }),
        ffi::RETRO_HW_CONTEXT_OPENGLES2 => Some(GlApi::Gles { major: 2, minor: 0 }),
        ffi::RETRO_HW_CONTEXT_OPENGLES3 => Some(GlApi::Gles { major: 3, minor: 0 }),
        ffi::RETRO_HW_CONTEXT_OPENGLES_VERSION => Some(GlApi::Gles {
            major: cb.version_major,
            minor: cb.version_minor,
        }),
        _ => None,
    }
}

/// Writes a value through a command's out-pointer, which may be null when the
/// core only asks whether the command is supported.
///
/// # Safety
///
/// `data` is null or valid for writing a `T`.
unsafe fn write<T>(data: *mut c_void, value: T) -> bool {
    if !data.is_null() {
        // SAFETY: forwarded from the caller.
        unsafe { data.cast::<T>().write_unaligned(value) };
    }
    true
}

/// Registers `host` for this thread's callbacks.
///
/// # Safety
///
/// `host` stays valid until [`unregister`], and no reference to it is live
/// while the core runs a callback.
pub(crate) unsafe fn register(host: *mut Host) {
    HOST.with(|h| h.set(host));
}

pub(crate) fn unregister() {
    HOST.with(|h| h.set(std::ptr::null_mut()));
}

fn with_host<R>(f: impl FnOnce(&mut Host) -> R) -> Option<R> {
    let host = HOST.with(Cell::get);
    // SAFETY: `register`'s contract keeps the pointer valid and unaliased
    // while callbacks run.
    unsafe { host.as_mut() }.map(f)
}

pub(crate) unsafe extern "C" fn environment(cmd: c_uint, data: *mut c_void) -> bool {
    with_host(|host| host.environment(cmd, data)).unwrap_or(false)
}

pub(crate) unsafe extern "C" fn video_refresh(
    data: *const c_void,
    width: c_uint,
    height: c_uint,
    pitch: usize,
) {
    with_host(|host| host.video_refresh(data, width, height, pitch));
}

pub(crate) unsafe extern "C" fn audio_sample(left: i16, right: i16) {
    with_host(|host| host.audio_samples.extend([left, right]));
}

pub(crate) unsafe extern "C" fn audio_sample_batch(data: *const i16, frames: usize) -> usize {
    if data.is_null() {
        return 0;
    }
    // SAFETY: the core passes `frames` interleaved stereo frames.
    let samples = unsafe { std::slice::from_raw_parts(data, frames * 2) };
    with_host(|host| host.audio_samples.extend_from_slice(samples));
    frames
}

pub(crate) unsafe extern "C" fn input_poll() {
    with_host(Host::poll_input);
}

pub(crate) unsafe extern "C" fn input_state(
    port: c_uint,
    device: c_uint,
    index: c_uint,
    id: c_uint,
) -> i16 {
    with_host(|host| input::query(&host.input_snapshot, port, device, index, id)).unwrap_or(0)
}

unsafe extern "C" fn get_current_framebuffer() -> usize {
    with_host(|host| match &host.renderer {
        Some(Renderer::Gl(gpu)) => gpu.hw.as_ref().map_or(0, |fb| fb.fbo.0.get() as usize),
        _ => 0,
    })
    .unwrap_or(0)
}

unsafe extern "C" fn get_proc_address(sym: *const c_char) -> ffi::retro_proc_address_t {
    if sym.is_null() {
        return None;
    }
    let egl = gl::Egl::get().ok()?;
    // SAFETY: the core passes a C string.
    let address = egl.proc_address(unsafe { CStr::from_ptr(sym) });
    // SAFETY: a non-null address is a function; the core casts it to the
    // signature it asked for.
    (!address.is_null())
        .then(|| unsafe { std::mem::transmute::<*const c_void, unsafe extern "C" fn()>(address) })
}

unsafe extern "C" {
    fn vsnprintf(buf: *mut c_char, size: usize, format: *const c_char, args: VaList) -> c_int;
}

unsafe extern "C" fn log_printf(level: ffi::retro_log_level, fmt: *const c_char, args: ...) {
    if fmt.is_null() {
        return;
    }
    let mut buf = [0 as c_char; 1024];
    // SAFETY: the core passes a printf format and matching arguments, and
    // vsnprintf truncates to the buffer.
    unsafe { vsnprintf(buf.as_mut_ptr(), buf.len(), fmt, args) };
    // SAFETY: vsnprintf null-terminates within the buffer.
    let message = unsafe { CStr::from_ptr(buf.as_ptr()) }.to_string_lossy();
    let message = message.trim_end();
    let level = match level {
        ffi::RETRO_LOG_DEBUG => log::Level::Debug,
        ffi::RETRO_LOG_INFO => log::Level::Info,
        ffi::RETRO_LOG_WARN => log::Level::Warn,
        _ => log::Level::Error,
    };
    log::log!(target: "core", level, "{message}");
}

fn perf_epoch() -> Instant {
    static EPOCH: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    *EPOCH.get_or_init(Instant::now)
}

unsafe extern "C" fn perf_get_time_usec() -> ffi::retro_time_t {
    perf_epoch().elapsed().as_micros() as ffi::retro_time_t
}

unsafe extern "C" fn perf_get_counter() -> ffi::retro_perf_tick_t {
    perf_epoch().elapsed().as_nanos() as ffi::retro_perf_tick_t
}

unsafe extern "C" fn perf_get_cpu_features() -> u64 {
    0
}

unsafe extern "C" fn perf_noop(_counter: *mut ffi::retro_perf_counter) {}

unsafe extern "C" fn perf_log() {}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use super::*;
    use crate::audio::NullAudioSink;
    use crate::gl::Framebuffer;
    use crate::renderer::Gpu;
    use crate::video::NullVideoSink;

    fn host() -> Host {
        Host::new(
            CString::default(),
            CString::default(),
            Options::new(HashMap::new()),
            Arc::new(InputState::new()),
            Box::new(NullAudioSink),
            Output::Sink(Box::new(NullVideoSink)),
        )
    }

    #[test]
    fn single_session_states_dont_persist() {
        let mut host = host();
        assert!(host.states_persist());
        let mut quirks = u64::from(ffi::RETRO_SERIALIZATION_QUIRK_SINGLE_SESSION);
        assert!(host.environment(
            ffi::RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS,
            (&raw mut quirks).cast(),
        ));
        assert!(!host.states_persist());
    }

    #[test]
    fn new_av_info_takes_effect_within_the_frame() {
        let Some(context) = gl::test_context() else {
            return;
        };
        let mut host = host();
        let fb = Framebuffer::new(&context.gl, 320, 240, false, false).unwrap();
        host.renderer = Some(Renderer::Gl(Gpu::new(context, Some(fb), false)));
        let mut info = ffi::retro_system_av_info::default();
        info.geometry.max_width = 640;
        info.geometry.max_height = 480;
        assert!(host.environment(
            ffi::RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO,
            (&raw mut info).cast(),
        ));
        let Some(Renderer::Gl(gpu)) = &host.renderer else {
            panic!("no GL renderer");
        };
        let fb = gpu.hw.as_ref().unwrap();
        assert_eq!((fb.width, fb.height), (640, 480));
    }
}
