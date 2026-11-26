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
use crate::ffi;
use crate::gl::{self, Framebuffer, GlApi, GlContext, NativeWindow};
use crate::input::{self, InputState, MAX_PORTS, PortSnapshot};
use crate::options::{Options, opt_string};
use crate::session::SessionError;
use crate::video::{self, CapturedFrame, Frame, PixelFormat, VideoSink};
use crate::vulkan::{self, Negotiation, VulkanContext, VulkanError};
use crate::{AvInfo, GlError, InputDescriptor};

thread_local! {
    static HOST: Cell<*mut Host> = const { Cell::new(std::ptr::null_mut()) };
}

/// Where frames go.
pub(crate) enum VideoTarget {
    Sink(Box<dyn VideoSink>),
    Window {
        window: Option<NativeWindow>,
        width: u32,
        height: u32,
        vsync: bool,
    },
}

/// The GL context and the framebuffers the session draws with.
pub(crate) struct Gpu {
    pub(crate) context: GlContext,
    /// The framebuffer a hardware-rendered core draws into.
    pub(crate) hw: Option<Framebuffer>,
    /// A texture for presenting software frames to a window.
    pub(crate) upload: Option<Framebuffer>,
    readback: Vec<u8>,
}

impl Gpu {
    pub(crate) fn new(context: GlContext) -> Self {
        Self {
            context,
            hw: None,
            upload: None,
            readback: Vec::new(),
        }
    }
}

impl Drop for Gpu {
    fn drop(&mut self) {
        for fb in self.hw.iter().chain(&self.upload) {
            fb.delete(&self.context.gl);
        }
    }
}

/// Everything the core's callbacks read and write.
pub(crate) struct Host {
    pub(crate) system_dir: CString,
    pub(crate) save_dir: CString,
    pub(crate) pixel_format: PixelFormat,
    pub(crate) av_info: AvInfo,
    pub(crate) options: Options,
    pub(crate) descriptors: Vec<InputDescriptor>,
    pub(crate) input: Arc<InputState>,
    input_snapshot: [PortSnapshot; MAX_PORTS],
    /// The buttons pressed since the previous frame, held for this one.
    frame_presses: [u16; MAX_PORTS],
    pub(crate) audio_sink: Box<dyn AudioSink>,
    pub(crate) audio: Option<AudioPipeline>,
    /// The core rate `audio` resamples from.
    audio_rate: f64,
    audio_samples: Vec<i16>,
    /// The rate the session runs frames at, over the core's own.
    pub(crate) audio_speed: f64,
    pub(crate) video: VideoTarget,
    pub(crate) gpu: Option<Gpu>,
    /// The Vulkan context, for a core that renders with Vulkan.
    pub(crate) vulkan: Option<VulkanContext>,
    /// The core's hardware render request, with our callbacks filled in.
    pub(crate) hw_render: Option<ffi::retro_hw_render_callback>,
    /// The Vulkan context negotiation interface the core set.
    pub(crate) negotiation: Option<Negotiation>,
    /// Why the session turned down the core's hardware render request, when
    /// it had to.
    pub(crate) hw_refused: Option<SessionError>,
    /// The last software frame, converted, for repeats and presenting.
    frame: Vec<u8>,
    frame_size: (u32, u32),
    /// Whether the last frame was hardware-rendered.
    last_frame_hw: bool,
    pub(crate) support_no_game: bool,
    pub(crate) frame_time: Option<ffi::retro_frame_time_callback>,
    pub(crate) shutdown_requested: bool,
    /// The `RETRO_SERIALIZATION_QUIRK_*` flags the core declared.
    serialization_quirks: u64,
    unhandled: HashSet<c_uint>,
}

impl Host {
    pub(crate) fn new(
        system_dir: CString,
        save_dir: CString,
        options: Options,
        input: Arc<InputState>,
        audio_sink: Box<dyn AudioSink>,
        video: VideoTarget,
    ) -> Self {
        Self {
            system_dir,
            save_dir,
            pixel_format: PixelFormat::default(),
            av_info: AvInfo::default(),
            options,
            descriptors: Vec::new(),
            input,
            input_snapshot: Default::default(),
            frame_presses: [0; MAX_PORTS],
            audio_sink,
            audio: None,
            audio_rate: 0.0,
            audio_samples: Vec::new(),
            audio_speed: 1.0,
            video,
            gpu: None,
            vulkan: None,
            hw_render: None,
            negotiation: None,
            hw_refused: None,
            frame: Vec::new(),
            frame_size: (0, 0),
            last_frame_hw: false,
            support_no_game: false,
            frame_time: None,
            shutdown_requested: false,
            serialization_quirks: 0,
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
        if let Some(gpu) = &mut self.gpu
            && let Some(fb) = &mut gpu.hw
        {
            let (w, h) = (
                av.max_width.max(av.base_width),
                av.max_height.max(av.base_height),
            );
            if w > fb.width || h > fb.height {
                fb.resize(&gpu.context.gl, w.max(fb.width), h.max(fb.height));
            }
        }
    }

    /// Resamples this frame's audio into the sink.
    pub(crate) fn flush_audio(&mut self) {
        if let Some(audio) = &mut self.audio {
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

    /// Presents the last frame again, as when a window is attached.
    pub(crate) fn redraw(&mut self) {
        if !matches!(self.video, VideoTarget::Window { .. }) {
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
        if let Some(vulkan) = &mut self.vulkan {
            let mut pixels = Vec::new();
            return vulkan
                .read(width, height, &mut pixels)
                .then_some(CapturedFrame {
                    width,
                    height,
                    pixels,
                });
        }
        let bottom_left_origin = self.hw_render.is_some_and(|hw| hw.bottom_left_origin);
        let gpu = self.gpu.as_ref()?;
        let fb = gpu.hw.as_ref()?;
        let (width, height) = (width.min(fb.width), height.min(fb.height));
        let mut pixels = Vec::new();
        fb.read(&gpu.context.gl, width, height, &mut pixels);
        gl::drain_errors(&gpu.context.gl, "capturing a frame");
        if bottom_left_origin {
            video::flip_rows(&mut pixels, width as usize, height as usize);
        }
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
        match &mut self.video {
            VideoTarget::Sink(sink) => sink.frame(Frame {
                width,
                height,
                pixels: &self.frame,
            }),
            VideoTarget::Window { .. } => {
                let Some(gpu) = &mut self.gpu else { return };
                let gl = &gpu.context.gl;
                if gpu.upload.is_none() {
                    match Framebuffer::new(gl, width, height, false, false) {
                        Ok(fb) => gpu.upload = Some(fb),
                        Err(e) => {
                            log::error!("can't create the upload texture: {e}");
                            return;
                        }
                    }
                }
                let upload = gpu.upload.as_mut().expect("created above");
                upload.upload(gl, width, height, &self.frame);
                gl::present(
                    &gpu.context,
                    upload,
                    width,
                    height,
                    self.av_info.aspect_ratio,
                    true,
                );
            }
        }
    }

    fn show_hw_frame(&mut self, width: u32, height: u32) {
        if let Some(vulkan) = &mut self.vulkan {
            match &mut self.video {
                VideoTarget::Sink(sink) => {
                    if vulkan.read(width, height, &mut self.frame) {
                        sink.frame(Frame {
                            width,
                            height,
                            pixels: &self.frame,
                        });
                    }
                }
                VideoTarget::Window { .. } => {
                    vulkan.present(width, height, self.av_info.aspect_ratio);
                }
            }
            return;
        }
        let bottom_left_origin = self.hw_render.is_some_and(|hw| hw.bottom_left_origin);
        let Some(gpu) = &mut self.gpu else { return };
        let Some(fb) = &gpu.hw else { return };
        let (width, height) = (width.min(fb.width), height.min(fb.height));
        if width == 0 || height == 0 {
            return;
        }
        match &mut self.video {
            VideoTarget::Sink(sink) => {
                fb.read(&gpu.context.gl, width, height, &mut gpu.readback);
                gl::drain_errors(&gpu.context.gl, "reading back a frame");
                if bottom_left_origin {
                    video::flip_rows(&mut gpu.readback, width as usize, height as usize);
                }
                sink.frame(Frame {
                    width,
                    height,
                    pixels: &gpu.readback,
                });
            }
            VideoTarget::Window { .. } => {
                gl::present(
                    &gpu.context,
                    fb,
                    width,
                    height,
                    self.av_info.aspect_ratio,
                    !bottom_left_origin,
                );
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
                ffi::RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS => {
                    self.descriptors.clear();
                    let mut d = data.cast::<ffi::retro_input_descriptor>().cast_const();
                    while !d.is_null() && !(*d).description.is_null() {
                        let desc = &*d;
                        self.descriptors.push(InputDescriptor {
                            port: desc.port,
                            device: desc.device,
                            index: desc.index,
                            id: desc.id,
                            description: opt_string(desc.description).unwrap_or_default(),
                        });
                        d = d.add(1);
                    }
                    log::debug!("core input descriptors: {:?}", self.descriptors);
                    true
                }
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
                ffi::RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE => match &self.vulkan {
                    Some(vulkan) => write(data, vulkan.interface()),
                    None => false,
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
                    (1 << ffi::RETRO_DEVICE_JOYPAD) | (1 << ffi::RETRO_DEVICE_ANALOG),
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
                // Information the session doesn't use yet, accepted so cores
                // don't take a fallback path.
                ffi::RETRO_ENVIRONMENT_SET_CONTROLLER_INFO
                | ffi::RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO
                | ffi::RETRO_ENVIRONMENT_SET_MEMORY_MAPS
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
    with_host(|host| {
        host.gpu
            .as_ref()
            .and_then(|gpu| gpu.hw.as_ref())
            .map_or(0, |fb| fb.fbo.0.get() as usize)
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

    struct Discard;

    impl VideoSink for Discard {
        fn frame(&mut self, _frame: Frame<'_>) {}
    }

    fn host() -> Host {
        Host::new(
            CString::default(),
            CString::default(),
            Options::new(HashMap::new()),
            Arc::new(InputState::new()),
            Box::new(NullAudioSink),
            VideoTarget::Sink(Box::new(Discard)),
        )
    }

    fn av_info(max_width: u32, max_height: u32, sample_rate: f64) -> ffi::retro_system_av_info {
        let mut info = ffi::retro_system_av_info::default();
        info.geometry.base_width = 320;
        info.geometry.base_height = 240;
        info.geometry.max_width = max_width;
        info.geometry.max_height = max_height;
        info.timing.fps = 60.0;
        info.timing.sample_rate = sample_rate;
        info
    }

    #[test]
    fn experimental_commands_reach_their_handlers() {
        let mut host = host();
        let mut enabled: c_int = 0;
        assert!(host.environment(
            ffi::RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE,
            (&raw mut enabled).cast(),
        ));
        assert_eq!(enabled, 0b11);
        assert!(host.environment(
            ffi::RETRO_ENVIRONMENT_GET_INPUT_BITMASKS,
            std::ptr::null_mut(),
        ));
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
    fn turns_down_content_overrides() {
        // The session loads content by the core's global need_fullpath only.
        let mut host = host();
        assert!(!host.environment(
            ffi::RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE,
            std::ptr::null_mut(),
        ));
    }

    #[test]
    fn new_av_info_takes_effect_within_the_frame() {
        let mut host = host();
        host.av_info = AvInfo::from_retro(&av_info(320, 240, 32_000.0));
        host.reset_audio();
        if let Some(context) = gl::test_context() {
            let fb = Framebuffer::new(&context.gl, 320, 240, false, false).unwrap();
            let mut gpu = Gpu::new(context);
            gpu.hw = Some(fb);
            host.gpu = Some(gpu);
        }
        let mut info = av_info(640, 480, 44_100.0);
        assert!(host.environment(
            ffi::RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO,
            (&raw mut info).cast(),
        ));
        assert_eq!(host.audio_rate, 44_100.0);
        if let Some(fb) = host.gpu.as_ref().and_then(|gpu| gpu.hw.as_ref()) {
            assert_eq!((fb.width, fb.height), (640, 480));
        }
    }
}
