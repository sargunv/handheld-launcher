//! The OpenGL and OpenGL ES side of a session: an EGL context the session
//! owns, either headless or on windows, the framebuffer hardware-rendered
//! cores draw into, and presenting or reading back frames.
//!
//! EGL, and libwayland-egl for Wayland windows, load at run time, so the
//! library has no link-time dependency on a GL stack.

use std::ffi::c_void;
use std::ptr::{self, NonNull};
use std::sync::OnceLock;

use glow::HasContext;
use khronos_egl as egl;
use raw_window_handle::{RawDisplayHandle, RawWindowHandle};
use wayland_sys::egl::{WaylandEgl, wayland_egl_option, wl_egl_window};

use crate::video::{Region, Screen};

/// EGL 1.4, which is as far as some Linux GPU drivers go.
pub(crate) type Egl = egl::DynamicInstance<egl::EGL1_4>;

/// The process's EGL, loaded on first use.
pub(crate) fn egl() -> Result<&'static Egl, String> {
    static EGL: OnceLock<Result<Egl, String>> = OnceLock::new();
    EGL.get_or_init(|| {
        // SAFETY: the system EGL library is sound to load.
        unsafe { Egl::load_required() }.map_err(|e| format!("can't load libEGL: {e}"))
    })
    .as_ref()
    .map_err(Clone::clone)
}

fn wayland_egl() -> Result<&'static WaylandEgl, GlError> {
    wayland_egl_option().ok_or_else(|| GlError::Unavailable("can't load libwayland-egl".into()))
}

/// A native window the session presents frames to: a Wayland surface, an
/// Xlib window, or an Android `ANativeWindow`.
#[derive(Clone, Copy, Debug)]
pub struct NativeWindow {
    display: RawDisplayHandle,
    pub(crate) window: RawWindowHandle,
}

impl NativeWindow {
    /// # Safety
    ///
    /// `window` is a window on `display`, and both outlive their attachment
    /// to the session.
    pub unsafe fn new(display: RawDisplayHandle, window: RawWindowHandle) -> Self {
        Self { display, window }
    }
}

/// A window and its size in pixels.
pub(crate) type SizedWindow = (NativeWindow, u32, u32);

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
pub(crate) enum Target {
    /// No window: frames stay in framebuffers the session reads back.
    Headless,
    /// Windows, the first now or later with [`GlContext::set_window`]. A
    /// context that starts without one uses the platform's default display,
    /// so only windows on that display can be attached later, which on
    /// Android is every window.
    Window(Option<SizedWindow>),
}

/// An EGL context, current on the thread that created it, with window or
/// headless surfaces.
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
    /// Each [`Screen`]'s window surface.
    windows: [Option<WindowSurface>; 2],
    /// The surface the context is current on without a main window: a small
    /// pbuffer, or `None` where EGL allows surfaceless contexts.
    idle_surface: Option<egl::Surface>,
}

/// A window's EGL surface, and on Wayland the `wl_egl_window` it draws
/// through.
#[derive(Clone, Copy)]
struct WindowSurface {
    surface: egl::Surface,
    wayland: Option<NonNull<wl_egl_window>>,
}

impl GlContext {
    /// Creates a context for `api` and makes it current. The session needs
    /// GL 3.0 or GL ES 3.0, for framebuffer blits.
    pub(crate) fn new(api: GlApi, target: Target, debug: bool) -> Result<Self, GlError> {
        let egl = egl().map_err(GlError::Unavailable)?;
        let display = match target {
            Target::Headless => headless_display(egl),
            Target::Window(Some((window, ..))) => window_display(egl, window.display),
            Target::Window(None) => default_display(egl),
        }
        .ok_or_else(|| GlError::Unavailable("no EGL display".into()))?;
        let (major, minor) = egl
            .initialize(display)
            .map_err(|e| GlError::Unavailable(format!("can't initialize the EGL display: {e}")))?;
        log::debug!("EGL {major}.{minor}");

        let surfaceless = has_extension(egl, Some(display), "EGL_KHR_surfaceless_context");
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
            windows: [None; 2],
            idle_surface: None,
        };
        if !surfaceless {
            let attribs = [egl::WIDTH, 16, egl::HEIGHT, 16, egl::NONE];
            let surface = egl
                .create_pbuffer_surface(display, config, &attribs)
                .map_err(|e| GlError::Egl(format!("can't create a pbuffer: {e}")))?;
            handles.idle_surface = Some(surface);
        }
        if let Target::Window(Some(window)) = target {
            handles.attach(Screen::Main, window)?;
        }
        handles.make_current()?;
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

    /// Replaces `screen`'s window, or detaches it with `None`. The context,
    /// and every GL object in it, stays. On an error, the screen has no
    /// window.
    ///
    /// The old window's surface is gone when this returns, so a platform that
    /// requires it, as Android does before a `Surface` is destroyed, can let
    /// the window go.
    pub(crate) fn set_window(
        &mut self,
        screen: Screen,
        window: Option<SizedWindow>,
    ) -> Result<(), GlError> {
        let h = &mut self.handles;
        h.destroy_window(screen);
        let attached = window.map_or(Ok(()), |window| h.attach(screen, window));
        h.make_current()?;
        attached
    }

    /// Tells the surface the new size of `screen`'s window. Only a Wayland
    /// window needs it; other platforms track the size themselves.
    pub(crate) fn resize(&self, screen: Screen, width: u32, height: u32) {
        if let Some(WindowSurface {
            wayland: Some(window),
            ..
        }) = self.handles.windows[screen as usize]
            && let Ok(wl) = wayland_egl()
        {
            let (width, height) = (width.max(1) as i32, height.max(1) as i32);
            // SAFETY: `window` is the live wl_egl_window this context made.
            unsafe { (wl.wl_egl_window_resize)(window.as_ptr(), width, height, 0, 0) };
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

    /// Creates `screen`'s window surface, and stops swaps on it from waiting
    /// for the display's refresh: the session paces frames itself. It leaves
    /// the context current on the new surface.
    fn attach(
        &mut self,
        screen: Screen,
        (window, width, height): SizedWindow,
    ) -> Result<(), GlError> {
        let (width, height) = (width.max(1) as i32, height.max(1) as i32);
        let (native, wayland) = match window.window {
            RawWindowHandle::Wayland(w) => {
                let wl = wayland_egl()?;
                // SAFETY: the caller of `NativeWindow::new` vouched for the
                // surface.
                let native =
                    unsafe { (wl.wl_egl_window_create)(w.surface.as_ptr().cast(), width, height) };
                let native = NonNull::new(native)
                    .ok_or_else(|| GlError::Egl("can't wrap the Wayland surface".into()))?;
                (native.as_ptr().cast(), Some(native))
            }
            RawWindowHandle::Xlib(w) => (w.window as *mut c_void, None),
            RawWindowHandle::AndroidNdk(w) => (w.a_native_window.as_ptr(), None),
            other => return Err(GlError::Egl(format!("can't present to a {other:?}"))),
        };
        // SAFETY: a valid config and a native window of the display's platform.
        let surface = unsafe {
            self.egl
                .create_window_surface(self.display, self.config, native, None)
        };
        let window = match surface {
            Ok(surface) => WindowSurface { surface, wayland },
            Err(e) => {
                destroy_wayland_window(wayland);
                return Err(GlError::Egl(format!("can't create a window surface: {e}")));
            }
        };
        self.windows[screen as usize] = Some(window);
        let current = self.make_current_on(Some(window.surface));
        if let Err(e) = current {
            self.destroy_window(screen);
            return Err(e);
        }
        if let Err(e) = self.egl.swap_interval(self.display, 0) {
            log::warn!("can't set the swap interval: {e}");
        }
        Ok(())
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

    /// Destroys `screen`'s window surface, leaving the context current on no
    /// surface until the caller makes it current again.
    fn destroy_window(&mut self, screen: Screen) {
        if let Some(window) = self.windows[screen as usize].take() {
            self.release_current();
            self.destroy_surface(window.surface);
            destroy_wayland_window(window.wayland);
        }
    }

    /// Makes the context current on this thread, on the main window if there
    /// is one.
    fn make_current(&self) -> Result<(), GlError> {
        let main = self.windows[Screen::Main as usize].map(|w| w.surface);
        self.make_current_on(main.or(self.idle_surface))
    }

    fn make_current_on(&self, surface: Option<egl::Surface>) -> Result<(), GlError> {
        self.egl
            .make_current(self.display, surface, surface, Some(self.context))
            .map_err(|e| GlError::Egl(format!("can't make the context current: {e}")))
    }
}

impl Drop for Handles {
    fn drop(&mut self) {
        self.destroy_window(Screen::Lower);
        self.destroy_window(Screen::Main);
        self.release_current();
        if let Some(surface) = self.idle_surface {
            self.destroy_surface(surface);
        }
        if let Err(e) = self.egl.destroy_context(self.display, self.context) {
            log::warn!("can't destroy the context: {e}");
        }
    }
}

fn destroy_wayland_window(window: Option<NonNull<wl_egl_window>>) {
    if let (Some(window), Ok(wl)) = (window, wayland_egl()) {
        // SAFETY: the window came from wl_egl_window_create, and no EGL
        // surface uses it anymore.
        unsafe { (wl.wl_egl_window_destroy)(window.as_ptr()) };
    }
}

/// Whether `display`'s extension string, or the client extensions for no
/// display, lists `name`.
fn has_extension(egl: &Egl, display: Option<egl::Display>, name: &str) -> bool {
    // A display that predates client extensions has no client extension
    // string.
    egl.query_string(display, egl::EXTENSIONS)
        .is_ok_and(|extensions| {
            extensions
                .to_bytes()
                .split(u8::is_ascii_whitespace)
                .any(|e| e == name.as_bytes())
        })
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

/// Resolves a GL or EGL function by name.
pub(crate) fn proc_address(egl: &Egl, name: &str) -> *const c_void {
    egl.get_proc_address(name)
        .map_or(ptr::null(), |f| f as *const c_void)
}

const PLATFORM_X11_KHR: egl::Enum = 0x31D5;
const PLATFORM_WAYLAND_KHR: egl::Enum = 0x31D8;
const PLATFORM_SURFACELESS_MESA: egl::Enum = 0x31DD;

/// The display for `platform` and its `native` display: through EGL 1.5's
/// platform displays, or before 1.5, through `eglGetDisplay`, which guesses
/// the platform.
fn platform_display(egl: &Egl, platform: egl::Enum, native: *mut c_void) -> Option<egl::Display> {
    // SAFETY: the caller passes a native display for the platform.
    unsafe {
        match egl.upcast::<egl::EGL1_5>() {
            Some(egl) => egl
                .get_platform_display(platform, native, &[egl::ATTRIB_NONE])
                .ok(),
            None => egl.get_display(native),
        }
    }
}

fn headless_display(egl: &Egl) -> Option<egl::Display> {
    if has_extension(egl, None, "EGL_MESA_platform_surfaceless")
        && let Some(display) = platform_display(egl, PLATFORM_SURFACELESS_MESA, ptr::null_mut())
    {
        return Some(display);
    }
    default_display(egl)
}

fn window_display(egl: &Egl, display: RawDisplayHandle) -> Option<egl::Display> {
    match display {
        RawDisplayHandle::Wayland(d) => {
            platform_display(egl, PLATFORM_WAYLAND_KHR, d.display.as_ptr())
        }
        RawDisplayHandle::Xlib(d) => platform_display(
            egl,
            PLATFORM_X11_KHR,
            d.display.map_or(ptr::null_mut(), NonNull::as_ptr),
        ),
        _ => default_display(egl),
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
    let [main, lower_window] = h.windows.map(|w| w.map(|w| w.surface));
    if let Some(surface) = lower_window {
        if h.make_current_on(Some(surface)).is_ok() {
            draw(lower, surface);
        }
        if let Err(e) = h.make_current() {
            log::warn!("can't go back to the main window: {e}");
            return;
        }
    }
    let region = if lower_window.is_some() {
        Region::Top
    } else {
        Region::Whole
    };
    if let Some(surface) = main {
        draw(region, surface);
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
