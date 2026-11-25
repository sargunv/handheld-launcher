//! The OpenGL and OpenGL ES side of a session: an EGL context the session
//! owns, either headless or on a window, the framebuffer hardware-rendered
//! cores draw into, and presenting or reading back frames.

mod egl;

use std::ffi::{CStr, c_ulong, c_void};
use std::ptr::{self, NonNull};

use glow::HasContext;

use egl::*;

pub(crate) use egl::Egl;

/// A native window the session presents frames to.
#[derive(Clone, Copy, Debug)]
pub struct NativeWindow {
    kind: WindowKind,
}

#[derive(Clone, Copy, Debug)]
enum WindowKind {
    Wayland {
        display: NonNull<c_void>,
        surface: NonNull<c_void>,
    },
    Xlib {
        display: NonNull<c_void>,
        window: c_ulong,
    },
    #[cfg(target_os = "android")]
    Android { window: NonNull<c_void> },
}

impl NativeWindow {
    /// A Wayland surface.
    ///
    /// # Safety
    ///
    /// `display` is a `wl_display` and `surface` a `wl_surface` on it, and
    /// both outlive the session.
    pub unsafe fn wayland(display: NonNull<c_void>, surface: NonNull<c_void>) -> Self {
        Self {
            kind: WindowKind::Wayland { display, surface },
        }
    }

    /// An X11 window, through Xlib.
    ///
    /// # Safety
    ///
    /// `display` is an Xlib `Display` and `window` a window on it, and both
    /// outlive the session.
    pub unsafe fn xlib(display: NonNull<c_void>, window: c_ulong) -> Self {
        Self {
            kind: WindowKind::Xlib { display, window },
        }
    }

    /// An Android `ANativeWindow`, such as a `Surface`'s.
    ///
    /// # Safety
    ///
    /// `window` is an `ANativeWindow` that outlives the session.
    #[cfg(target_os = "android")]
    pub unsafe fn android(window: NonNull<c_void>) -> Self {
        Self {
            kind: WindowKind::Android { window },
        }
    }
}

/// Why the session couldn't set up or use OpenGL.
#[derive(Debug, thiserror::Error)]
pub enum GlError {
    #[error("EGL isn't available: {0}")]
    Unavailable(String),
    #[error("{0}")]
    Egl(String),
    #[error("{0}")]
    Gl(String),
}

/// The GL flavor and version a context provides.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum GlApi {
    /// Desktop OpenGL. `core` asks for a core profile of that version;
    /// otherwise the context has the compatibility profile.
    Gl { core: Option<(u32, u32)> },
    /// OpenGL ES of at least this version.
    Gles { major: u32, minor: u32 },
}

/// An EGL context, current on the thread that created it, with a window or
/// headless surface.
pub(crate) struct GlContext {
    pub(crate) gl: glow::Context,
    handles: Handles,
}

/// The EGL objects behind a [`GlContext`], destroyed when dropped.
struct Handles {
    egl: &'static Egl,
    display: EGLDisplay,
    context: EGLContext,
    surface: EGLSurface,
    wayland_window: Option<NonNull<c_void>>,
}

impl GlContext {
    /// Creates a context for `api` and makes it current. With a window, the
    /// context draws to it; otherwise it has no default framebuffer to speak
    /// of. The session needs GL 3.0 or GL ES 3.0, for framebuffer blits.
    pub(crate) fn new(
        api: GlApi,
        window: Option<(&NativeWindow, u32, u32)>,
        debug: bool,
    ) -> Result<Self, GlError> {
        let egl = Egl::get().map_err(GlError::Unavailable)?;
        let display = match window {
            None => headless_display(egl),
            Some((window, ..)) => window_display(egl, window),
        }
        .ok_or_else(|| GlError::Unavailable("no EGL display".into()))?;
        let (mut major, mut minor) = (0, 0);
        // SAFETY: `display` came from EGL.
        if unsafe { (egl.initialize)(display, &mut major, &mut minor) } == EGL_FALSE {
            return Err(GlError::Unavailable(format!(
                "can't initialize the EGL display: {}",
                egl.error()
            )));
        }
        log::debug!("EGL {major}.{minor}: {}", query(egl, display, EGL_VERSION));

        let surfaceless =
            window.is_none() && egl.has_extension(display, "EGL_KHR_surfaceless_context");
        let (bind, renderable) = match api {
            GlApi::Gl { .. } => (EGL_OPENGL_API, EGL_OPENGL_BIT),
            GlApi::Gles { .. } => (EGL_OPENGL_ES_API, EGL_OPENGL_ES3_BIT),
        };
        // SAFETY: no preconditions.
        if unsafe { (egl.bind_api)(bind) } == EGL_FALSE {
            return Err(GlError::Unavailable(format!(
                "EGL can't bind {api:?}: {}",
                egl.error()
            )));
        }
        let surface_type = match (window, surfaceless) {
            (Some(_), _) => EGL_WINDOW_BIT,
            (None, true) => 0,
            (None, false) => EGL_PBUFFER_BIT,
        };
        #[rustfmt::skip]
        let config_attribs = [
            EGL_SURFACE_TYPE, surface_type,
            EGL_RENDERABLE_TYPE, renderable,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 0,
            EGL_NONE,
        ];
        let mut config = ptr::null_mut();
        let mut count = 0;
        // SAFETY: a terminated attribute list and room for one config.
        let ok = unsafe {
            (egl.choose_config)(display, config_attribs.as_ptr(), &mut config, 1, &mut count)
        };
        if ok == EGL_FALSE || count == 0 {
            return Err(GlError::Unavailable(format!(
                "no EGL config for {api:?}: {}",
                egl.error()
            )));
        }

        let mut context_attribs = Vec::new();
        match api {
            GlApi::Gl {
                core: Some((major, minor)),
            } => {
                context_attribs.extend([
                    EGL_CONTEXT_MAJOR_VERSION,
                    major as EGLint,
                    EGL_CONTEXT_MINOR_VERSION,
                    minor as EGLint,
                    EGL_CONTEXT_OPENGL_PROFILE_MASK,
                    EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                ]);
            }
            GlApi::Gl { core: None } => {}
            GlApi::Gles { major, minor } => {
                context_attribs.extend([
                    EGL_CONTEXT_MAJOR_VERSION,
                    major.max(3) as EGLint,
                    EGL_CONTEXT_MINOR_VERSION,
                    if major >= 3 { minor as EGLint } else { 0 },
                ]);
            }
        }
        if debug {
            context_attribs.extend([EGL_CONTEXT_OPENGL_DEBUG, EGL_TRUE_ATTRIB]);
        }
        context_attribs.push(EGL_NONE);
        // SAFETY: a valid config and a terminated attribute list.
        let context = unsafe {
            (egl.create_context)(display, config, ptr::null_mut(), context_attribs.as_ptr())
        };
        if context.is_null() {
            return Err(GlError::Unavailable(format!(
                "can't create a {api:?} context: {}",
                egl.error()
            )));
        }

        let mut handles = Handles {
            egl,
            display,
            context,
            surface: ptr::null_mut(),
            wayland_window: None,
        };
        handles.surface = match window {
            Some((window, width, height)) => {
                handles.create_window_surface(config, window, width, height)?
            }
            None if surfaceless => ptr::null_mut(),
            None => {
                let attribs = [EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE];
                // SAFETY: a valid config and a terminated attribute list.
                let surface =
                    unsafe { (egl.create_pbuffer_surface)(display, config, attribs.as_ptr()) };
                if surface.is_null() {
                    return Err(GlError::Egl(format!(
                        "can't create a pbuffer: {}",
                        egl.error()
                    )));
                }
                surface
            }
        };
        handles.make_current()?;
        // SAFETY: the context is current, and EGL resolves its functions.
        let gl = unsafe { glow::Context::from_loader_function_cstr(|name| egl.proc_address(name)) };
        let this = Self { gl, handles };
        let version = this.gl.version();
        log::info!(
            "GL context: {} {}.{} ({})",
            if version.is_embedded {
                "OpenGL ES"
            } else {
                "OpenGL"
            },
            version.major,
            version.minor,
            version.vendor_info
        );
        if version.major < 3 {
            return Err(GlError::Unavailable(format!(
                "GL {}.{} is too old; the session needs 3.0",
                version.major, version.minor
            )));
        }
        Ok(this)
    }

    /// Makes the context current on this thread.
    pub(crate) fn make_current(&self) -> Result<(), GlError> {
        self.handles.make_current()
    }

    /// Sets whether swapping buffers waits for the display's next refresh.
    pub(crate) fn set_vsync(&self, vsync: bool) {
        let h = &self.handles;
        // SAFETY: the display is initialized and this context is current.
        if unsafe { (h.egl.swap_interval)(h.display, i32::from(vsync)) } == EGL_FALSE {
            log::warn!("can't set the swap interval: {}", h.egl.error());
        }
    }

    /// Shows the window surface's back buffer.
    pub(crate) fn swap_buffers(&self) {
        let h = &self.handles;
        // SAFETY: the display and surface belong together.
        if unsafe { (h.egl.swap_buffers)(h.display, h.surface) } == EGL_FALSE {
            log::warn!("can't present: {}", h.egl.error());
        }
    }

    /// The window surface's size in pixels.
    pub(crate) fn surface_size(&self) -> (u32, u32) {
        let h = &self.handles;
        let (mut width, mut height) = (0, 0);
        // SAFETY: the display and surface belong together.
        unsafe {
            (h.egl.query_surface)(h.display, h.surface, EGL_WIDTH, &mut width);
            (h.egl.query_surface)(h.display, h.surface, EGL_HEIGHT, &mut height);
        }
        (width.max(0) as u32, height.max(0) as u32)
    }

    /// Tells the surface its window's new size. Only Wayland needs it; other
    /// platforms track the size themselves.
    pub(crate) fn resize(&self, width: u32, height: u32) {
        if let (Some(window), Ok(wl)) = (self.handles.wayland_window, WaylandEgl::get()) {
            // SAFETY: `window` is the live wl_egl_window this context made.
            unsafe {
                (wl.resize)(
                    window.as_ptr(),
                    width.max(1) as i32,
                    height.max(1) as i32,
                    0,
                    0,
                )
            };
        }
    }
}

impl Handles {
    fn create_window_surface(
        &mut self,
        config: EGLConfig,
        window: &NativeWindow,
        width: u32,
        height: u32,
    ) -> Result<EGLSurface, GlError> {
        let native = match window.kind {
            WindowKind::Wayland { surface, .. } => {
                let wl = WaylandEgl::get().map_err(GlError::Unavailable)?;
                // SAFETY: the caller of `NativeWindow::wayland` vouched for
                // the surface.
                let native = unsafe {
                    (wl.create)(surface.as_ptr(), width.max(1) as i32, height.max(1) as i32)
                };
                let native = NonNull::new(native)
                    .ok_or_else(|| GlError::Egl("can't wrap the Wayland surface".into()))?;
                self.wayland_window = Some(native);
                native.as_ptr()
            }
            WindowKind::Xlib { window, .. } => window as *mut c_void,
            #[cfg(target_os = "android")]
            WindowKind::Android { window } => window.as_ptr(),
        };
        // SAFETY: a valid config and a native window of the display's platform.
        let surface =
            unsafe { (self.egl.create_window_surface)(self.display, config, native, ptr::null()) };
        if surface.is_null() {
            return Err(GlError::Egl(format!(
                "can't create a window surface: {}",
                self.egl.error()
            )));
        }
        Ok(surface)
    }

    /// Makes the context current on this thread.
    pub(crate) fn make_current(&self) -> Result<(), GlError> {
        // SAFETY: the display, surface and context belong together.
        let ok = unsafe {
            (self.egl.make_current)(self.display, self.surface, self.surface, self.context)
        };
        if ok == EGL_FALSE {
            return Err(GlError::Egl(format!(
                "can't make the context current: {}",
                self.egl.error()
            )));
        }
        Ok(())
    }
}

impl Drop for Handles {
    fn drop(&mut self) {
        // SAFETY: the handles came from this display, and nothing uses them
        // after this.
        unsafe {
            (self.egl.make_current)(
                self.display,
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
            );
            if !self.surface.is_null() {
                (self.egl.destroy_surface)(self.display, self.surface);
            }
            (self.egl.destroy_context)(self.display, self.context);
            if let (Some(window), Ok(wl)) = (self.wayland_window, WaylandEgl::get()) {
                (wl.destroy)(window.as_ptr());
            }
        }
    }
}

fn query(egl: &Egl, display: EGLDisplay, name: EGLint) -> String {
    // SAFETY: an initialized display.
    let s = unsafe { (egl.query_string)(display, name) };
    if s.is_null() {
        return String::new();
    }
    // SAFETY: EGL returns a static C string.
    unsafe { CStr::from_ptr(s) }.to_string_lossy().into_owned()
}

fn headless_display(egl: &Egl) -> Option<EGLDisplay> {
    if egl.has_extension(ptr::null_mut(), "EGL_MESA_platform_surfaceless")
        && let Some(display) = egl.platform_display(EGL_PLATFORM_SURFACELESS_MESA, ptr::null_mut())
    {
        return Some(display);
    }
    // SAFETY: EGL_DEFAULT_DISPLAY is null.
    let display = unsafe { (egl.get_display)(ptr::null_mut()) };
    (!display.is_null()).then_some(display)
}

fn window_display(egl: &Egl, window: &NativeWindow) -> Option<EGLDisplay> {
    match window.kind {
        WindowKind::Wayland { display, .. } => {
            egl.platform_display(EGL_PLATFORM_WAYLAND_KHR, display.as_ptr())
        }
        WindowKind::Xlib { display, .. } => {
            egl.platform_display(EGL_PLATFORM_X11_KHR, display.as_ptr())
        }
        #[cfg(target_os = "android")]
        WindowKind::Android { .. } => {
            // SAFETY: EGL_DEFAULT_DISPLAY is null.
            let display = unsafe { (egl.get_display)(ptr::null_mut()) };
            (!display.is_null()).then_some(display)
        }
    }
}

/// An offscreen framebuffer with a color texture and optional depth and
/// stencil.
pub(crate) struct Framebuffer {
    pub(crate) fbo: glow::Framebuffer,
    color: glow::Texture,
    depth_stencil: Option<glow::Renderbuffer>,
    depth: bool,
    stencil: bool,
    pub(crate) width: u32,
    pub(crate) height: u32,
}

impl Framebuffer {
    pub(crate) fn new(
        gl: &glow::Context,
        width: u32,
        height: u32,
        depth: bool,
        stencil: bool,
    ) -> Result<Self, GlError> {
        // SAFETY: the context is current; every object is created here.
        unsafe {
            let saved = SavedBindings::save(gl);
            let fbo = gl.create_framebuffer().map_err(GlError::Gl)?;
            let color = gl.create_texture().map_err(GlError::Gl)?;
            let depth_stencil = if depth || stencil {
                Some(gl.create_renderbuffer().map_err(GlError::Gl)?)
            } else {
                None
            };
            let mut this = Self {
                fbo,
                color,
                depth_stencil,
                depth,
                stencil,
                width: 0,
                height: 0,
            };
            gl.bind_framebuffer(glow::FRAMEBUFFER, Some(fbo));
            this.allocate(gl, width, height);
            gl.framebuffer_texture_2d(
                glow::FRAMEBUFFER,
                glow::COLOR_ATTACHMENT0,
                glow::TEXTURE_2D,
                Some(color),
                0,
            );
            if let Some(rb) = depth_stencil {
                let attachment = match (depth, stencil) {
                    (true, true) => glow::DEPTH_STENCIL_ATTACHMENT,
                    (true, false) => glow::DEPTH_ATTACHMENT,
                    _ => glow::STENCIL_ATTACHMENT,
                };
                gl.framebuffer_renderbuffer(
                    glow::FRAMEBUFFER,
                    attachment,
                    glow::RENDERBUFFER,
                    Some(rb),
                );
            }
            let status = gl.check_framebuffer_status(glow::FRAMEBUFFER);
            saved.restore(gl);
            if status != glow::FRAMEBUFFER_COMPLETE {
                this.delete(gl);
                return Err(GlError::Gl(format!("framebuffer incomplete: {status:#x}")));
            }
            Ok(this)
        }
    }

    /// Gives the attachments storage of `width` by `height`, keeping the
    /// framebuffer's name, so cores that cached it keep working.
    pub(crate) fn resize(&mut self, gl: &glow::Context, width: u32, height: u32) {
        // SAFETY: the context is current.
        unsafe {
            let saved = SavedBindings::save(gl);
            self.allocate(gl, width, height);
            saved.restore(gl);
        }
    }

    /// # Safety
    ///
    /// The context is current. It changes the texture and renderbuffer
    /// bindings.
    unsafe fn allocate(&mut self, gl: &glow::Context, width: u32, height: u32) {
        let (w, h) = (width.max(1) as i32, height.max(1) as i32);
        // SAFETY: forwarded from the caller.
        unsafe {
            gl.bind_texture(glow::TEXTURE_2D, Some(self.color));
            gl.tex_image_2d(
                glow::TEXTURE_2D,
                0,
                glow::RGBA8 as i32,
                w,
                h,
                0,
                glow::RGBA,
                glow::UNSIGNED_BYTE,
                glow::PixelUnpackData::Slice(None),
            );
            gl.tex_parameter_i32(
                glow::TEXTURE_2D,
                glow::TEXTURE_MIN_FILTER,
                glow::NEAREST as i32,
            );
            gl.tex_parameter_i32(
                glow::TEXTURE_2D,
                glow::TEXTURE_MAG_FILTER,
                glow::NEAREST as i32,
            );
            if let Some(rb) = self.depth_stencil {
                let format = match (self.depth, self.stencil) {
                    (true, false) => glow::DEPTH_COMPONENT24,
                    (false, true) => glow::STENCIL_INDEX8,
                    _ => glow::DEPTH24_STENCIL8,
                };
                gl.bind_renderbuffer(glow::RENDERBUFFER, Some(rb));
                gl.renderbuffer_storage(glow::RENDERBUFFER, format, w, h);
            }
        }
        self.width = width;
        self.height = height;
    }

    /// Uploads tightly packed RGBA8 rows to the color texture's top-left
    /// corner, growing the texture if they don't fit.
    pub(crate) fn upload(&mut self, gl: &glow::Context, width: u32, height: u32, pixels: &[u8]) {
        if width > self.width || height > self.height {
            self.resize(gl, width.max(self.width), height.max(self.height));
        }
        // SAFETY: the context is current and `pixels` holds the rows.
        unsafe {
            let saved = SavedBindings::save(gl);
            gl.bind_texture(glow::TEXTURE_2D, Some(self.color));
            gl.pixel_store_i32(glow::UNPACK_ALIGNMENT, 4);
            gl.pixel_store_i32(glow::UNPACK_ROW_LENGTH, 0);
            gl.bind_buffer(glow::PIXEL_UNPACK_BUFFER, None);
            gl.tex_sub_image_2d(
                glow::TEXTURE_2D,
                0,
                0,
                0,
                width as i32,
                height as i32,
                glow::RGBA,
                glow::UNSIGNED_BYTE,
                glow::PixelUnpackData::Slice(Some(pixels)),
            );
            saved.restore(gl);
        }
    }

    /// Reads the `width` by `height` region at the framebuffer's origin into
    /// `out` as tightly packed RGBA8, in GL's bottom-to-top row order.
    pub(crate) fn read(&self, gl: &glow::Context, width: u32, height: u32, out: &mut Vec<u8>) {
        out.resize(width as usize * height as usize * 4, 0);
        // SAFETY: the context is current and `out` holds the region.
        unsafe {
            let saved = SavedBindings::save(gl);
            gl.bind_framebuffer(glow::READ_FRAMEBUFFER, Some(self.fbo));
            gl.bind_buffer(glow::PIXEL_PACK_BUFFER, None);
            gl.pixel_store_i32(glow::PACK_ALIGNMENT, 4);
            gl.pixel_store_i32(glow::PACK_ROW_LENGTH, 0);
            gl.read_pixels(
                0,
                0,
                width as i32,
                height as i32,
                glow::RGBA,
                glow::UNSIGNED_BYTE,
                glow::PixelPackData::Slice(Some(out)),
            );
            saved.restore(gl);
        }
    }

    pub(crate) fn delete(&self, gl: &glow::Context) {
        // SAFETY: the context is current and the objects are this
        // framebuffer's.
        unsafe {
            gl.delete_framebuffer(self.fbo);
            gl.delete_texture(self.color);
            if let Some(rb) = self.depth_stencil {
                gl.delete_renderbuffer(rb);
            }
        }
    }
}

/// Draws the `width` by `height` region at `source`'s origin to the window,
/// scaled to fit at `aspect` (width over height) and centered on black, then
/// presents it. `top_row_first` says the region's first row (GL's lowest) is
/// the image's top, as with uploaded frames and cores that render with a
/// top-left origin.
pub(crate) fn present(
    context: &GlContext,
    source: &Framebuffer,
    width: u32,
    height: u32,
    aspect: f32,
    top_row_first: bool,
) {
    let gl = &context.gl;
    let (win_w, win_h) = context.surface_size();
    log::trace!("presenting {width}x{height} to a {win_w}x{win_h} surface");
    let (x, y, w, h) = fit(width, height, aspect, win_w, win_h);
    let (dst_y0, dst_y1) = if top_row_first {
        (y + h, y)
    } else {
        (y, y + h)
    };
    // SAFETY: the context is current, and every binding it changes is
    // restored.
    unsafe {
        let saved = SavedBindings::save(gl);
        gl.disable(glow::SCISSOR_TEST);
        gl.bind_framebuffer(glow::DRAW_FRAMEBUFFER, None);
        gl.color_mask(true, true, true, true);
        gl.clear_color(0.0, 0.0, 0.0, 1.0);
        gl.clear(glow::COLOR_BUFFER_BIT);
        gl.bind_framebuffer(glow::READ_FRAMEBUFFER, Some(source.fbo));
        gl.blit_framebuffer(
            0,
            0,
            width as i32,
            height as i32,
            x,
            dst_y0,
            x + w,
            dst_y1,
            glow::COLOR_BUFFER_BIT,
            glow::NEAREST,
        );
        saved.restore(gl);
    }
    context.swap_buffers();
    drain_errors(gl, "presenting");
}

/// The largest rectangle with `aspect` that fits the window, centered, as
/// (x, y, width, height). A non-positive `aspect` uses the frame's own.
fn fit(width: u32, height: u32, aspect: f32, win_w: u32, win_h: u32) -> (i32, i32, i32, i32) {
    let aspect = if aspect > 0.0 {
        f64::from(aspect)
    } else {
        f64::from(width) / f64::from(height.max(1))
    };
    let (win_w, win_h) = (f64::from(win_w), f64::from(win_h));
    let (w, h) = if win_w / win_h.max(1.0) > aspect {
        (win_h * aspect, win_h)
    } else {
        (win_w, win_w / aspect)
    };
    let (w, h) = (w.round(), h.round());
    (
        ((win_w - w) / 2.0).round() as i32,
        ((win_h - h) / 2.0).round() as i32,
        w as i32,
        h as i32,
    )
}

/// Logs and clears pending GL errors.
pub(crate) fn drain_errors(gl: &glow::Context, during: &str) {
    for _ in 0..16 {
        // SAFETY: the context is current.
        let error = unsafe { gl.get_error() };
        if error == glow::NO_ERROR {
            break;
        }
        log::warn!("GL error {error:#x} while {during}");
    }
}

/// The GL state the host's own drawing changes, restored afterwards so that
/// cores find the state they left.
struct SavedBindings {
    read_fbo: i32,
    draw_fbo: i32,
    texture: i32,
    renderbuffer: i32,
    pack_buffer: i32,
    unpack_buffer: i32,
    scissor: bool,
    clear_color: [f32; 4],
    color_mask: [bool; 4],
    pack_alignment: i32,
    pack_row_length: i32,
    unpack_alignment: i32,
    unpack_row_length: i32,
}

impl SavedBindings {
    /// # Safety
    ///
    /// The context is current.
    unsafe fn save(gl: &glow::Context) -> Self {
        // SAFETY: forwarded from the caller.
        unsafe {
            let mut clear_color = [0.0; 4];
            gl.get_parameter_f32_slice(glow::COLOR_CLEAR_VALUE, &mut clear_color);
            let mut mask = [0; 4];
            gl.get_parameter_i32_slice(glow::COLOR_WRITEMASK, &mut mask);
            Self {
                read_fbo: gl.get_parameter_i32(glow::READ_FRAMEBUFFER_BINDING),
                draw_fbo: gl.get_parameter_i32(glow::DRAW_FRAMEBUFFER_BINDING),
                texture: gl.get_parameter_i32(glow::TEXTURE_BINDING_2D),
                renderbuffer: gl.get_parameter_i32(glow::RENDERBUFFER_BINDING),
                pack_buffer: gl.get_parameter_i32(glow::PIXEL_PACK_BUFFER_BINDING),
                unpack_buffer: gl.get_parameter_i32(glow::PIXEL_UNPACK_BUFFER_BINDING),
                scissor: gl.is_enabled(glow::SCISSOR_TEST),
                clear_color,
                color_mask: mask.map(|m| m != 0),
                pack_alignment: gl.get_parameter_i32(glow::PACK_ALIGNMENT),
                pack_row_length: gl.get_parameter_i32(glow::PACK_ROW_LENGTH),
                unpack_alignment: gl.get_parameter_i32(glow::UNPACK_ALIGNMENT),
                unpack_row_length: gl.get_parameter_i32(glow::UNPACK_ROW_LENGTH),
            }
        }
    }

    /// # Safety
    ///
    /// The context is current.
    unsafe fn restore(self, gl: &glow::Context) {
        fn name<T>(id: i32, make: impl FnOnce(std::num::NonZeroU32) -> T) -> Option<T> {
            std::num::NonZeroU32::new(id as u32).map(make)
        }
        // SAFETY: forwarded from the caller.
        unsafe {
            gl.bind_framebuffer(
                glow::READ_FRAMEBUFFER,
                name(self.read_fbo, glow::NativeFramebuffer),
            );
            gl.bind_framebuffer(
                glow::DRAW_FRAMEBUFFER,
                name(self.draw_fbo, glow::NativeFramebuffer),
            );
            gl.bind_texture(glow::TEXTURE_2D, name(self.texture, glow::NativeTexture));
            gl.bind_renderbuffer(
                glow::RENDERBUFFER,
                name(self.renderbuffer, glow::NativeRenderbuffer),
            );
            gl.bind_buffer(
                glow::PIXEL_PACK_BUFFER,
                name(self.pack_buffer, glow::NativeBuffer),
            );
            gl.bind_buffer(
                glow::PIXEL_UNPACK_BUFFER,
                name(self.unpack_buffer, glow::NativeBuffer),
            );
            if self.scissor {
                gl.enable(glow::SCISSOR_TEST);
            }
            let [r, g, b, a] = self.clear_color;
            gl.clear_color(r, g, b, a);
            let [r, g, b, a] = self.color_mask;
            gl.color_mask(r, g, b, a);
            gl.pixel_store_i32(glow::PACK_ALIGNMENT, self.pack_alignment);
            gl.pixel_store_i32(glow::PACK_ROW_LENGTH, self.pack_row_length);
            gl.pixel_store_i32(glow::UNPACK_ALIGNMENT, self.unpack_alignment);
            gl.pixel_store_i32(glow::UNPACK_ROW_LENGTH, self.unpack_row_length);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::fit;

    #[test]
    fn fit_letterboxes_and_pillarboxes() {
        assert_eq!(fit(320, 240, 4.0 / 3.0, 1920, 1080), (240, 0, 1440, 1080));
        assert_eq!(fit(320, 240, 4.0 / 3.0, 800, 1000), (0, 200, 800, 600));
        assert_eq!(fit(256, 224, 0.0, 256, 224), (0, 0, 256, 224));
    }
}
