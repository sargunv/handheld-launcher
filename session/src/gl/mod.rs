//! The OpenGL and OpenGL ES side of a session: an EGL context the session
//! owns, either headless or on a window, the framebuffer hardware-rendered
//! cores draw into, and presenting or reading back frames.

pub(crate) mod egl;

use std::ffi::{c_ulong, c_void};
use std::ptr::{self, NonNull};

use glow::HasContext;

use egl::{Egl, WaylandEgl};

use crate::video::{Region, Screen};

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
    display: egl::Display,
    config: egl::Config,
    context: egl::Context,
    /// The attached window's surface.
    window_surface: Option<egl::Surface>,
    /// The lower window's surface: a second screen that shows the bottom
    /// half of a two-screen system's frame.
    lower_surface: Option<egl::Surface>,
    /// The surface the context is current on without a window: a small
    /// pbuffer, or `None` where EGL allows surfaceless contexts.
    idle_surface: Option<egl::Surface>,
    wayland_window: Option<NonNull<c_void>>,
}

impl GlContext {
    /// Creates a context for `api` and makes it current. The session needs
    /// GL 3.0 or GL ES 3.0, for framebuffer blits.
    pub(crate) fn new(api: GlApi, target: Target<'_>, debug: bool) -> Result<Self, GlError> {
        let egl = egl::instance().map_err(GlError::Unavailable)?;
        let display = match &target {
            Target::Headless => headless_display(egl),
            Target::Window(Some((window, ..))) => window_display(egl, window),
            Target::Window(None) => default_display(egl),
        }
        .ok_or_else(|| GlError::Unavailable("no EGL display".into()))?;
        let (major, minor) = egl
            .initialize(display)
            .map_err(|e| GlError::Unavailable(format!("can't initialize the EGL display: {e}")))?;
        log::debug!("EGL {major}.{minor}: {}", query(egl, display, egl::VERSION));

        let surfaceless = egl::has_extension(egl, Some(display), "EGL_KHR_surfaceless_context");
        let (bind, renderable) = match api {
            GlApi::Gl { .. } => (egl::OPENGL_API, egl::OPENGL_BIT),
            GlApi::Gles { .. } => (egl::OPENGL_ES_API, egl::OPENGL_ES3_BIT),
        };
        egl.bind_api(bind)
            .map_err(|e| GlError::Unavailable(format!("EGL can't bind {api:?}: {e}")))?;
        let idle_bit = if surfaceless { 0 } else { egl::PBUFFER_BIT };
        let surface_type = match target {
            Target::Window(_) => egl::WINDOW_BIT | idle_bit,
            Target::Headless => idle_bit,
        };
        let config = choose_config(egl, display, surface_type, renderable)
            .map_err(|e| GlError::Unavailable(format!("can't choose an EGL config: {e}")))?
            .ok_or_else(|| GlError::Unavailable(format!("no EGL config for {api:?}")))?;

        let mut context_attribs = Vec::new();
        match api {
            GlApi::Gl {
                core: Some((major, minor)),
            } => {
                context_attribs.extend([
                    egl::CONTEXT_MAJOR_VERSION,
                    major as egl::Int,
                    egl::CONTEXT_MINOR_VERSION,
                    minor as egl::Int,
                    egl::CONTEXT_OPENGL_PROFILE_MASK,
                    egl::CONTEXT_OPENGL_CORE_PROFILE_BIT,
                ]);
            }
            GlApi::Gl { core: None } => {}
            GlApi::Gles { major, minor } => {
                context_attribs.extend([
                    egl::CONTEXT_MAJOR_VERSION,
                    major.max(3) as egl::Int,
                    egl::CONTEXT_MINOR_VERSION,
                    if major >= 3 { minor as egl::Int } else { 0 },
                ]);
            }
        }
        if debug {
            context_attribs.extend([egl::CONTEXT_OPENGL_DEBUG, egl::TRUE as egl::Int]);
        }
        context_attribs.push(egl::NONE);
        let context = egl
            .create_context(display, config, None, &context_attribs)
            .map_err(|e| GlError::Unavailable(format!("can't create a {api:?} context: {e}")))?;

        let mut handles = Handles {
            egl,
            display,
            config,
            context,
            window_surface: None,
            lower_surface: None,
            idle_surface: None,
            wayland_window: None,
        };
        if !surfaceless {
            let attribs = [egl::WIDTH, 16, egl::HEIGHT, 16, egl::NONE];
            let surface = egl
                .create_pbuffer_surface(display, config, &attribs)
                .map_err(|e| GlError::Egl(format!("can't create a pbuffer: {e}")))?;
            handles.idle_surface = Some(surface);
        }
        if let Target::Window(Some((window, width, height))) = target {
            handles.window_surface = Some(handles.create_window_surface(window, width, height)?);
        }
        handles.make_current()?;
        if handles.window_surface.is_some() {
            handles.swap_without_waiting();
        }
        // SAFETY: the context is current, and EGL resolves its functions.
        let gl = unsafe { glow::Context::from_loader_function(|name| proc_address(egl, name)) };
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
            h.window_surface = Some(h.create_window_surface(window, width, height)?);
            if let Err(e) = h.make_current() {
                h.destroy_window_surface();
                h.make_current()?;
                return Err(e);
            }
            h.swap_without_waiting();
        }
        Ok(())
    }

    /// Replaces the lower window, which shows the bottom half of each frame
    /// while the main window shows the top half, or detaches it with `None`.
    /// On an error, the context has no lower window. As with
    /// [`GlContext::set_window`], the old window's surface is gone when this
    /// returns.
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
        let surface = match h.create_window_surface(window, width, height) {
            Ok(surface) => surface,
            Err(e) => {
                h.make_current()?;
                return Err(e);
            }
        };
        h.lower_surface = Some(surface);
        let result = h
            .make_current_on(Some(surface))
            .map(|()| h.swap_without_waiting());
        if let Err(e) = result {
            h.destroy_lower_surface();
            h.make_current()?;
            return Err(e);
        }
        h.make_current()
    }

    /// Tells the surface the new size of `screen`'s window. Only a Wayland
    /// window, which only the main screen can be, needs it; other platforms
    /// track the size themselves.
    pub(crate) fn resize(&self, screen: Screen, width: u32, height: u32) {
        if screen == Screen::Main
            && let (Some(window), Ok(wl)) = (self.handles.wayland_window, WaylandEgl::get())
        {
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
    fn swap(&self, surface: egl::Surface) {
        if let Err(e) = self.egl.swap_buffers(self.display, surface) {
            log::warn!("can't present: {e}");
        }
    }

    fn size(&self, surface: egl::Surface) -> (u32, u32) {
        let query = |attribute| {
            let value = self.egl.query_surface(self.display, surface, attribute);
            value.unwrap_or(0).max(0) as u32
        };
        (query(egl::WIDTH), query(egl::HEIGHT))
    }

    fn create_window_surface(
        &mut self,
        window: &NativeWindow,
        width: u32,
        height: u32,
    ) -> Result<egl::Surface, GlError> {
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
            self.egl
                .create_window_surface(self.display, self.config, native, None)
        };
        surface.map_err(|e| {
            self.destroy_wayland_window();
            GlError::Egl(format!("can't create a window surface: {e}"))
        })
    }

    /// Makes the context current on no surface, as EGL needs before it
    /// releases a surface's window at once.
    fn release_current(&self) {
        if let Err(e) = self.egl.make_current(self.display, None, None, None) {
            log::warn!("can't release the context: {e}");
        }
    }

    fn destroy_surface(&self, surface: egl::Surface) {
        if let Err(e) = self.egl.destroy_surface(self.display, surface) {
            log::warn!("can't destroy a surface: {e}");
        }
    }

    /// Destroys the window surface, leaving the context current on no
    /// surface until the caller makes it current again.
    fn destroy_window_surface(&mut self) {
        if let Some(surface) = self.window_surface.take() {
            self.release_current();
            self.destroy_surface(surface);
            self.destroy_wayland_window();
        }
    }

    /// Destroys the lower window's surface, leaving the context current on
    /// no surface if it was there.
    fn destroy_lower_surface(&mut self) {
        if let Some(surface) = self.lower_surface.take() {
            self.release_current();
            self.destroy_surface(surface);
        }
    }

    fn destroy_wayland_window(&mut self) {
        if let (Some(window), Ok(wl)) = (self.wayland_window.take(), WaylandEgl::get()) {
            // SAFETY: the window came from wl_egl_window_create, and no EGL
            // surface uses it anymore.
            unsafe { (wl.destroy)(window.as_ptr()) };
        }
    }

    /// Stops swaps on the surface the context is current on from waiting for
    /// the display's refresh: the session paces frames itself.
    fn swap_without_waiting(&self) {
        if let Err(e) = self.egl.swap_interval(self.display, 0) {
            log::warn!("can't set the swap interval: {e}");
        }
    }

    /// Makes the context current on this thread, on the window surface if
    /// there is one.
    fn make_current(&self) -> Result<(), GlError> {
        self.make_current_on(self.window_surface.or(self.idle_surface))
    }

    fn make_current_on(&self, surface: Option<egl::Surface>) -> Result<(), GlError> {
        self.egl
            .make_current(self.display, surface, surface, Some(self.context))
            .map_err(|e| GlError::Egl(format!("can't make the context current: {e}")))
    }
}

impl Drop for Handles {
    fn drop(&mut self) {
        self.destroy_lower_surface();
        self.destroy_window_surface();
        self.release_current();
        if let Some(surface) = self.idle_surface {
            self.destroy_surface(surface);
        }
        if let Err(e) = self.egl.destroy_context(self.display, self.context) {
            log::warn!("can't destroy the context: {e}");
        }
    }
}

/// The config with 8-bit red, green and blue that best matches, preferring
/// one without alpha. EGL sorts deeper colors first, which would pick 10-bit
/// configs where the platform offers them.
fn choose_config(
    egl: &Egl,
    display: egl::Display,
    surface_type: egl::Int,
    renderable: egl::Int,
) -> Result<Option<egl::Config>, egl::Error> {
    #[rustfmt::skip]
    let attribs = [
        egl::SURFACE_TYPE, surface_type,
        egl::RENDERABLE_TYPE, renderable,
        egl::RED_SIZE, 8,
        egl::GREEN_SIZE, 8,
        egl::BLUE_SIZE, 8,
        egl::ALPHA_SIZE, 0,
        egl::NONE,
    ];
    let mut configs = Vec::with_capacity(64);
    egl.choose_config(display, &attribs, &mut configs)?;
    let attrib =
        |config: egl::Config, name| egl.get_config_attrib(display, config, name).unwrap_or(0);
    let rgb888 = |config: &&egl::Config| {
        [egl::RED_SIZE, egl::GREEN_SIZE, egl::BLUE_SIZE]
            .iter()
            .all(|&name| attrib(**config, name) == 8)
    };
    Ok(configs
        .iter()
        .filter(rgb888)
        .min_by_key(|config| attrib(**config, egl::ALPHA_SIZE))
        .or(configs.first())
        .copied())
}

fn query(egl: &Egl, display: egl::Display, name: egl::Int) -> String {
    egl.query_string(Some(display), name)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default()
}

/// Resolves a GL or EGL function by name.
pub(crate) fn proc_address(egl: &Egl, name: &str) -> *const c_void {
    egl.get_proc_address(name)
        .map_or(ptr::null(), |f| f as *const c_void)
}

fn headless_display(egl: &Egl) -> Option<egl::Display> {
    if egl::has_extension(egl, None, "EGL_MESA_platform_surfaceless")
        && let Some(display) =
            egl::platform_display(egl, egl::PLATFORM_SURFACELESS_MESA, ptr::null_mut())
    {
        return Some(display);
    }
    default_display(egl)
}

fn window_display(egl: &Egl, window: &NativeWindow) -> Option<egl::Display> {
    match window.kind {
        WindowKind::Wayland { display, .. } => {
            egl::platform_display(egl, egl::PLATFORM_WAYLAND_KHR, display.as_ptr())
        }
        WindowKind::Xlib { display, .. } => {
            egl::platform_display(egl, egl::PLATFORM_X11_KHR, display.as_ptr())
        }
        #[cfg(target_os = "android")]
        WindowKind::Android { .. } => default_display(egl),
    }
}

fn default_display(egl: &Egl) -> Option<egl::Display> {
    // SAFETY: EGL_DEFAULT_DISPLAY is a valid native display.
    unsafe { egl.get_display(egl::DEFAULT_DISPLAY) }
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
/// half and the lower window its bottom half, `lower`, both from the one
/// frame.
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
    lower: Region,
) {
    let h = &context.handles;
    let draw = |region: Region, surface: egl::Surface| {
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
    if let Some(lower_surface) = h.lower_surface {
        if h.make_current_on(Some(lower_surface)).is_ok() {
            draw(lower, lower_surface);
        }
        if let Err(e) = h.make_current() {
            log::warn!("can't go back to the main window: {e}");
            return;
        }
        if let Some(surface) = h.window_surface {
            draw(Region::Top, surface);
        }
    } else if let Some(surface) = h.window_surface {
        draw(Region::Whole, surface);
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
    let (first_column, columns) = region.columns(width);
    let (x, y, w, h) = region.fit(width, height, aspect, win_w, win_h);
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
            first_column as i32,
            src_y0 as i32,
            (first_column + columns) as i32,
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
