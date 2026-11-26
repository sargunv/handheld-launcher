//! The OpenGL and OpenGL ES side of a session: an EGL context the session
//! owns, either headless or on a window, the framebuffer hardware-rendered
//! cores draw into, and presenting or reading back frames.

mod egl;

use std::ffi::{CStr, c_ulong, c_void};
use std::ptr::{self, NonNull};

use glow::HasContext;

use egl::*;

use crate::video::{Region, fit};

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

    /// The `ANativeWindow`, if this is one.
    #[cfg(target_os = "android")]
    pub(crate) fn android_window(&self) -> Option<NonNull<c_void>> {
        match self.kind {
            WindowKind::Android { window } => Some(window),
            _ => None,
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

/// What a [`GlContext`] or [`crate::vulkan::VulkanContext`] draws to.
#[derive(Clone, Copy)]
pub(crate) enum Target<'a> {
    /// No window: frames stay in framebuffers the session reads back.
    Headless,
    /// A window, attached now or later with [`GlContext::set_window`]. A
    /// context that starts without one uses the platform's default display,
    /// so only windows on that display can be attached later, which on
    /// Android is every window.
    Window(Option<WindowRef<'a>>),
}

/// A window and its size in pixels.
pub(crate) type WindowRef<'a> = (&'a NativeWindow, u32, u32);

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
    config: EGLConfig,
    context: EGLContext,
    /// The attached window's surface, or [`EGL_NO_SURFACE`].
    window_surface: EGLSurface,
    /// The lower window's surface, or [`EGL_NO_SURFACE`]: a second screen
    /// that shows the bottom half of a two-screen system's frame.
    lower_surface: EGLSurface,
    /// The surface the context is current on without a window: a small
    /// pbuffer, or [`EGL_NO_SURFACE`] where EGL allows surfaceless contexts.
    idle_surface: EGLSurface,
    wayland_window: Option<NonNull<c_void>>,
    vsync: bool,
}

impl GlContext {
    /// Creates a context for `api` and makes it current. The session needs
    /// GL 3.0 or GL ES 3.0, for framebuffer blits.
    pub(crate) fn new(api: GlApi, target: Target<'_>, debug: bool) -> Result<Self, GlError> {
        let egl = Egl::get().map_err(GlError::Unavailable)?;
        let display = match &target {
            Target::Headless => headless_display(egl),
            Target::Window(Some((window, ..))) => window_display(egl, window),
            Target::Window(None) => default_display(egl),
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

        let surfaceless = egl.has_extension(display, "EGL_KHR_surfaceless_context");
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
        let idle_bit = if surfaceless { 0 } else { EGL_PBUFFER_BIT };
        let surface_type = match target {
            Target::Window(_) => EGL_WINDOW_BIT | idle_bit,
            Target::Headless => idle_bit,
        };
        let config = choose_config(egl, display, surface_type, renderable).ok_or_else(|| {
            GlError::Unavailable(format!("no EGL config for {api:?}: {}", egl.error()))
        })?;

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
            config,
            context,
            window_surface: EGL_NO_SURFACE,
            lower_surface: EGL_NO_SURFACE,
            idle_surface: EGL_NO_SURFACE,
            wayland_window: None,
            vsync: true,
        };
        if !surfaceless {
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
            handles.idle_surface = surface;
        }
        if let Target::Window(Some((window, width, height))) = target {
            handles.window_surface = handles.create_window_surface(window, width, height)?;
        }
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

    /// Replaces the window the context draws to, or detaches it with `None`.
    /// The context, and every GL object in it, stays. On an error, the
    /// context has no window.
    ///
    /// The old window's surface is gone when this returns, so a platform that
    /// requires it, as Android does before a `Surface` is destroyed, can let
    /// the window go.
    pub(crate) fn set_window(&mut self, window: Option<WindowRef<'_>>) -> Result<(), GlError> {
        let h = &mut self.handles;
        h.destroy_window_surface();
        h.make_current()?;
        if let Some((window, width, height)) = window {
            h.window_surface = h.create_window_surface(window, width, height)?;
            if let Err(e) = h.make_current() {
                h.destroy_window_surface();
                h.make_current()?;
                return Err(e);
            }
            h.apply_vsync();
        }
        Ok(())
    }

    /// Whether the context has a window to present to.
    pub(crate) fn has_window(&self) -> bool {
        !self.handles.window_surface.is_null()
    }

    /// Replaces the lower window, which shows the bottom half of each frame
    /// while the main window shows the top half, or detaches it with `None`.
    /// On an error, the context has no lower window. As with
    /// [`GlContext::set_window`], the old window's surface is gone when this
    /// returns.
    ///
    /// Presenting to the lower window doesn't wait for its display's refresh,
    /// so the main window alone paces the session.
    pub(crate) fn set_lower_window(
        &mut self,
        window: Option<WindowRef<'_>>,
    ) -> Result<(), GlError> {
        let h = &mut self.handles;
        h.destroy_lower_surface();
        let Some((window, width, height)) = window else {
            return h.make_current();
        };
        if matches!(window.kind, WindowKind::Wayland { .. }) {
            // A second wl_egl_window would need tracking of its own.
            return Err(GlError::Egl(
                "a lower window can't be a Wayland surface".into(),
            ));
        }
        h.lower_surface = match h.create_window_surface(window, width, height) {
            Ok(surface) => surface,
            Err(e) => {
                h.make_current()?;
                return Err(e);
            }
        };
        let result = h.make_current_on(h.lower_surface).map(|()| {
            // SAFETY: the context is current on the lower surface.
            if unsafe { (h.egl.swap_interval)(h.display, 0) } == EGL_FALSE {
                log::warn!(
                    "can't stop the lower window waiting for vsync: {}",
                    h.egl.error()
                );
            }
        });
        if let Err(e) = result {
            h.destroy_lower_surface();
            h.make_current()?;
            return Err(e);
        }
        h.make_current()
    }

    /// Whether the context has a lower window.
    pub(crate) fn has_lower_window(&self) -> bool {
        !self.handles.lower_surface.is_null()
    }

    /// Sets whether swapping buffers waits for the display's next refresh,
    /// now and for windows attached later.
    pub(crate) fn set_vsync(&mut self, vsync: bool) {
        self.handles.vsync = vsync;
        if self.has_window() {
            self.handles.apply_vsync();
        }
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
    fn swap(&self, surface: EGLSurface) {
        // SAFETY: the display and surface belong together.
        if unsafe { (self.egl.swap_buffers)(self.display, surface) } == EGL_FALSE {
            log::warn!("can't present: {}", self.egl.error());
        }
    }

    fn size(&self, surface: EGLSurface) -> (u32, u32) {
        let (mut width, mut height) = (0, 0);
        // SAFETY: the display and surface belong together.
        unsafe {
            (self.egl.query_surface)(self.display, surface, EGL_WIDTH, &mut width);
            (self.egl.query_surface)(self.display, surface, EGL_HEIGHT, &mut height);
        }
        (width.max(0) as u32, height.max(0) as u32)
    }

    fn create_window_surface(
        &mut self,
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
        let surface = unsafe {
            (self.egl.create_window_surface)(self.display, self.config, native, ptr::null())
        };
        if surface.is_null() {
            let error = self.egl.error();
            self.destroy_wayland_window();
            return Err(GlError::Egl(format!(
                "can't create a window surface: {error}"
            )));
        }
        Ok(surface)
    }

    /// Destroys the window surface, after making the context current without
    /// it, as EGL needs to release the window at once.
    fn destroy_window_surface(&mut self) {
        if self.window_surface.is_null() {
            return;
        }
        // SAFETY: the handles belong to this display; the context is current
        // on no surface until the caller makes it current again.
        unsafe {
            (self.egl.make_current)(
                self.display,
                EGL_NO_SURFACE,
                EGL_NO_SURFACE,
                ptr::null_mut(),
            );
            (self.egl.destroy_surface)(self.display, self.window_surface);
        }
        self.window_surface = EGL_NO_SURFACE;
        self.destroy_wayland_window();
    }

    /// Destroys the lower window's surface, leaving the context current on
    /// no surface if it was there.
    fn destroy_lower_surface(&mut self) {
        if self.lower_surface.is_null() {
            return;
        }
        // SAFETY: as in `destroy_window_surface`.
        unsafe {
            (self.egl.make_current)(
                self.display,
                EGL_NO_SURFACE,
                EGL_NO_SURFACE,
                ptr::null_mut(),
            );
            (self.egl.destroy_surface)(self.display, self.lower_surface);
        }
        self.lower_surface = EGL_NO_SURFACE;
    }

    fn destroy_wayland_window(&mut self) {
        if let (Some(window), Ok(wl)) = (self.wayland_window.take(), WaylandEgl::get()) {
            // SAFETY: the window came from wl_egl_window_create, and no EGL
            // surface uses it anymore.
            unsafe { (wl.destroy)(window.as_ptr()) };
        }
    }

    fn apply_vsync(&self) {
        // SAFETY: the display is initialized and the context is current on
        // the window surface.
        if unsafe { (self.egl.swap_interval)(self.display, i32::from(self.vsync)) } == EGL_FALSE {
            log::warn!("can't set the swap interval: {}", self.egl.error());
        }
    }

    /// Makes the context current on this thread, on the window surface if
    /// there is one.
    fn make_current(&self) -> Result<(), GlError> {
        let surface = if self.window_surface.is_null() {
            self.idle_surface
        } else {
            self.window_surface
        };
        self.make_current_on(surface)
    }

    fn make_current_on(&self, surface: EGLSurface) -> Result<(), GlError> {
        // SAFETY: the display, surface and context belong together.
        let ok = unsafe { (self.egl.make_current)(self.display, surface, surface, self.context) };
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
        self.destroy_lower_surface();
        self.destroy_window_surface();
        // SAFETY: the handles came from this display, and nothing uses them
        // after this.
        unsafe {
            (self.egl.make_current)(
                self.display,
                EGL_NO_SURFACE,
                EGL_NO_SURFACE,
                ptr::null_mut(),
            );
            if !self.idle_surface.is_null() {
                (self.egl.destroy_surface)(self.display, self.idle_surface);
            }
            (self.egl.destroy_context)(self.display, self.context);
        }
    }
}

/// The config with 8-bit red, green and blue that best matches, preferring
/// one without alpha. EGL sorts deeper colors first, which would pick 10-bit
/// configs where the platform offers them.
fn choose_config(
    egl: &Egl,
    display: EGLDisplay,
    surface_type: EGLint,
    renderable: EGLint,
) -> Option<EGLConfig> {
    #[rustfmt::skip]
    let attribs = [
        EGL_SURFACE_TYPE, surface_type,
        EGL_RENDERABLE_TYPE, renderable,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 0,
        EGL_NONE,
    ];
    let mut configs = [ptr::null_mut(); 64];
    let mut count = 0;
    // SAFETY: a terminated attribute list and room for `configs.len()`.
    let ok = unsafe {
        (egl.choose_config)(
            display,
            attribs.as_ptr(),
            configs.as_mut_ptr(),
            configs.len() as EGLint,
            &mut count,
        )
    };
    if ok == EGL_FALSE || count <= 0 {
        return None;
    }
    let configs = &configs[..count as usize];
    let attrib = |config: EGLConfig, name: EGLint| {
        let mut value = 0;
        // SAFETY: a config of this display.
        unsafe { (egl.get_config_attrib)(display, config, name, &mut value) };
        value
    };
    let rgb888 = |config: &&EGLConfig| {
        [EGL_RED_SIZE, EGL_GREEN_SIZE, EGL_BLUE_SIZE]
            .iter()
            .all(|&name| attrib(**config, name) == 8)
    };
    configs
        .iter()
        .filter(rgb888)
        .min_by_key(|config| attrib(**config, EGL_ALPHA_SIZE))
        .or(configs.first())
        .copied()
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
    default_display(egl)
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
        WindowKind::Android { .. } => default_display(egl),
    }
}

fn default_display(egl: &Egl) -> Option<EGLDisplay> {
    // SAFETY: EGL_DEFAULT_DISPLAY is null.
    let display = unsafe { (egl.get_display)(ptr::null_mut()) };
    (!display.is_null()).then_some(display)
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
    /// bindings, and unbinds the pixel unpack buffer.
    unsafe fn allocate(&mut self, gl: &glow::Context, width: u32, height: u32) {
        let (w, h) = (width.max(1) as i32, height.max(1) as i32);
        // SAFETY: forwarded from the caller.
        unsafe {
            // With an unpack buffer bound, the null data below would be an
            // offset into it rather than no data.
            gl.bind_buffer(glow::PIXEL_UNPACK_BUFFER, None);
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
            gl.pixel_store_i32(glow::UNPACK_SKIP_ROWS, 0);
            gl.pixel_store_i32(glow::UNPACK_SKIP_PIXELS, 0);
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
            // Anything else a core left set would make GL write outside `out`.
            gl.pixel_store_i32(glow::PACK_ALIGNMENT, 4);
            gl.pixel_store_i32(glow::PACK_ROW_LENGTH, 0);
            gl.pixel_store_i32(glow::PACK_SKIP_ROWS, 0);
            gl.pixel_store_i32(glow::PACK_SKIP_PIXELS, 0);
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
/// presents it. With a lower window, the main window shows the region's top
/// half and the lower window its bottom half, both from the one frame.
/// Without a window, it does nothing. `top_row_first` says the region's first
/// row (GL's lowest) is the image's top, as with uploaded frames and cores
/// that render with a top-left origin.
pub(crate) fn present(
    context: &GlContext,
    source: &Framebuffer,
    width: u32,
    height: u32,
    aspect: f32,
    top_row_first: bool,
) {
    let h = &context.handles;
    let draw = |region: Region, surface: EGLSurface| {
        let (win_w, win_h) = h.size(surface);
        log::trace!("presenting {region:?} of {width}x{height} to a {win_w}x{win_h} surface");
        blit(
            &context.gl,
            source,
            width,
            height,
            region,
            aspect,
            top_row_first,
            (win_w, win_h),
        );
        h.swap(surface);
    };
    if context.has_lower_window() {
        // The lower window first: it doesn't wait for vsync, so both show the
        // frame by the main window's next refresh.
        if h.make_current_on(h.lower_surface).is_ok() {
            draw(Region::Bottom, h.lower_surface);
        }
        if let Err(e) = h.make_current() {
            log::warn!("can't go back to the main window: {e}");
            return;
        }
        if context.has_window() {
            draw(Region::Top, h.window_surface);
        }
    } else if context.has_window() {
        draw(Region::Whole, h.window_surface);
    }
    drain_errors(&context.gl, "presenting");
}

/// Draws `region` of the `width` by `height` image at `source`'s origin to
/// the current surface, `window` pixels in size, scaled to fit at the
/// region's share of `aspect` and centered on black.
#[allow(clippy::too_many_arguments)]
fn blit(
    gl: &glow::Context,
    source: &Framebuffer,
    width: u32,
    height: u32,
    region: Region,
    aspect: f32,
    top_row_first: bool,
    (win_w, win_h): (u32, u32),
) {
    let (first, rows) = region.rows(height);
    let (x, y, w, h) = fit(
        width,
        rows,
        region.aspect(width, height, aspect),
        win_w,
        win_h,
    );
    // GL counts rows from the bottom of the framebuffer.
    let (src_y0, src_y1, dst_y0, dst_y1) = if top_row_first {
        (first, first + rows, y + h, y)
    } else {
        (height - first - rows, height - first, y, y + h)
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
            src_y0 as i32,
            width as i32,
            src_y1 as i32,
            x,
            dst_y0,
            x + w,
            dst_y1,
            glow::COLOR_BUFFER_BIT,
            glow::NEAREST,
        );
        saved.restore(gl);
    }
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
    pack_skip_rows: i32,
    pack_skip_pixels: i32,
    unpack_alignment: i32,
    unpack_row_length: i32,
    unpack_skip_rows: i32,
    unpack_skip_pixels: i32,
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
                pack_skip_rows: gl.get_parameter_i32(glow::PACK_SKIP_ROWS),
                pack_skip_pixels: gl.get_parameter_i32(glow::PACK_SKIP_PIXELS),
                unpack_alignment: gl.get_parameter_i32(glow::UNPACK_ALIGNMENT),
                unpack_row_length: gl.get_parameter_i32(glow::UNPACK_ROW_LENGTH),
                unpack_skip_rows: gl.get_parameter_i32(glow::UNPACK_SKIP_ROWS),
                unpack_skip_pixels: gl.get_parameter_i32(glow::UNPACK_SKIP_PIXELS),
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
            gl.pixel_store_i32(glow::PACK_SKIP_ROWS, self.pack_skip_rows);
            gl.pixel_store_i32(glow::PACK_SKIP_PIXELS, self.pack_skip_pixels);
            gl.pixel_store_i32(glow::UNPACK_ALIGNMENT, self.unpack_alignment);
            gl.pixel_store_i32(glow::UNPACK_ROW_LENGTH, self.unpack_row_length);
            gl.pixel_store_i32(glow::UNPACK_SKIP_ROWS, self.unpack_skip_rows);
            gl.pixel_store_i32(glow::UNPACK_SKIP_PIXELS, self.unpack_skip_pixels);
        }
    }
}

/// A headless GL ES context for tests, or `None` where EGL isn't available
/// and `HANDHELD_REQUIRE_EGL` isn't set.
#[cfg(test)]
pub(crate) fn test_context() -> Option<GlContext> {
    match GlContext::new(GlApi::Gles { major: 3, minor: 0 }, Target::Headless, false) {
        Ok(context) => Some(context),
        Err(GlError::Unavailable(reason)) if std::env::var_os("HANDHELD_REQUIRE_EGL").is_none() => {
            eprintln!("skipping: {reason}");
            None
        }
        Err(e) => panic!("{e}"),
    }
}

#[cfg(test)]
mod tests {
    use glow::HasContext;

    use super::{Framebuffer, test_context};

    /// Fills `fb` with one color.
    fn clear(gl: &glow::Context, fb: &Framebuffer, color: [f32; 4]) {
        // SAFETY: the test's context is current.
        unsafe {
            gl.bind_framebuffer(glow::FRAMEBUFFER, Some(fb.fbo));
            gl.clear_color(color[0], color[1], color[2], color[3]);
            gl.clear(glow::COLOR_BUFFER_BIT);
            gl.bind_framebuffer(glow::FRAMEBUFFER, None);
        }
    }

    #[test]
    fn reads_back_tightly_packed_whatever_the_core_left_set() {
        let Some(context) = test_context() else {
            return;
        };
        let gl = &context.gl;
        let fb = Framebuffer::new(gl, 4, 4, false, false).unwrap();
        clear(gl, &fb, [1.0, 0.0, 0.0, 1.0]);
        // SAFETY: the context is current.
        unsafe {
            gl.pixel_store_i32(glow::PACK_SKIP_ROWS, 2);
            gl.pixel_store_i32(glow::PACK_SKIP_PIXELS, 1);
        }
        // Spare capacity keeps a broken readback from overrunning the
        // allocation while the test catches it.
        let mut out = Vec::with_capacity(4096);
        fb.read(gl, 4, 4, &mut out);
        assert_eq!(out.len(), 4 * 4 * 4);
        assert!(
            out.as_chunks::<4>()
                .0
                .iter()
                .all(|p| *p == [255, 0, 0, 255]),
            "{out:?}"
        );
        // SAFETY: the context is current.
        unsafe {
            assert_eq!(gl.get_parameter_i32(glow::PACK_SKIP_ROWS), 2);
            assert_eq!(gl.get_parameter_i32(glow::PACK_SKIP_PIXELS), 1);
            assert_eq!(gl.get_error(), glow::NO_ERROR);
        }
        fb.delete(gl);
    }

    #[test]
    fn grows_while_the_core_has_an_unpack_buffer_bound() {
        let Some(context) = test_context() else {
            return;
        };
        let gl = &context.gl;
        let mut fb = Framebuffer::new(gl, 1, 1, true, true).unwrap();
        // SAFETY: the context is current.
        let buffer = unsafe {
            let buffer = gl.create_buffer().unwrap();
            gl.bind_buffer(glow::PIXEL_UNPACK_BUFFER, Some(buffer));
            gl.buffer_data_size(glow::PIXEL_UNPACK_BUFFER, 4, glow::STREAM_DRAW);
            buffer
        };
        fb.resize(gl, 64, 64);
        // SAFETY: the context is current.
        unsafe {
            assert_eq!(gl.get_error(), glow::NO_ERROR);
            assert_eq!(
                gl.get_parameter_i32(glow::PIXEL_UNPACK_BUFFER_BINDING) as u32,
                buffer.0.get()
            );
            gl.bind_buffer(glow::PIXEL_UNPACK_BUFFER, None);
            gl.delete_buffer(buffer);
        }
        clear(gl, &fb, [0.0, 0.0, 1.0, 1.0]);
        let mut out = Vec::new();
        fb.read(gl, 64, 64, &mut out);
        assert!(
            out.as_chunks::<4>()
                .0
                .iter()
                .all(|p| *p == [0, 0, 255, 255])
        );
        fb.delete(gl);
    }
}
