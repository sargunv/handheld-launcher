//! The parts of EGL the session uses, loaded at run time so that the library
//! has no link-time dependency on a GL stack.

use std::ffi::{CStr, c_char, c_void};
use std::sync::OnceLock;

use libloading::Library;

pub(crate) type EGLDisplay = *mut c_void;
pub(crate) type EGLConfig = *mut c_void;
pub(crate) type EGLContext = *mut c_void;
pub(crate) type EGLSurface = *mut c_void;
pub(crate) type EGLint = i32;
pub(crate) type EGLenum = u32;
pub(crate) type EGLBoolean = u32;
pub(crate) type EGLAttrib = isize;

pub(crate) const EGL_FALSE: EGLBoolean = 0;
pub(crate) const EGL_NONE: EGLint = 0x3038;
pub(crate) const EGL_ALPHA_SIZE: EGLint = 0x3021;
pub(crate) const EGL_BLUE_SIZE: EGLint = 0x3022;
pub(crate) const EGL_GREEN_SIZE: EGLint = 0x3023;
pub(crate) const EGL_RED_SIZE: EGLint = 0x3024;
pub(crate) const EGL_SURFACE_TYPE: EGLint = 0x3033;
pub(crate) const EGL_RENDERABLE_TYPE: EGLint = 0x3040;
pub(crate) const EGL_PBUFFER_BIT: EGLint = 0x0001;
pub(crate) const EGL_WINDOW_BIT: EGLint = 0x0004;
pub(crate) const EGL_OPENGL_BIT: EGLint = 0x0008;
pub(crate) const EGL_OPENGL_ES3_BIT: EGLint = 0x0040;
pub(crate) const EGL_HEIGHT: EGLint = 0x3056;
pub(crate) const EGL_WIDTH: EGLint = 0x3057;
pub(crate) const EGL_VERSION: EGLint = 0x3054;
pub(crate) const EGL_EXTENSIONS: EGLint = 0x3055;
pub(crate) const EGL_OPENGL_ES_API: EGLenum = 0x30A0;
pub(crate) const EGL_OPENGL_API: EGLenum = 0x30A2;
pub(crate) const EGL_CONTEXT_MAJOR_VERSION: EGLint = 0x3098;
pub(crate) const EGL_CONTEXT_MINOR_VERSION: EGLint = 0x30FB;
pub(crate) const EGL_CONTEXT_OPENGL_PROFILE_MASK: EGLint = 0x30FD;
pub(crate) const EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT: EGLint = 0x1;
pub(crate) const EGL_CONTEXT_OPENGL_DEBUG: EGLint = 0x31B0;
pub(crate) const EGL_TRUE_ATTRIB: EGLint = 1;
pub(crate) const EGL_PLATFORM_X11_KHR: EGLenum = 0x31D5;
pub(crate) const EGL_PLATFORM_WAYLAND_KHR: EGLenum = 0x31D8;
pub(crate) const EGL_PLATFORM_SURFACELESS_MESA: EGLenum = 0x31DD;

type GetPlatformDisplay =
    unsafe extern "C" fn(EGLenum, *mut c_void, *const EGLAttrib) -> EGLDisplay;
type GetPlatformDisplayExt =
    unsafe extern "C" fn(EGLenum, *mut c_void, *const EGLint) -> EGLDisplay;

/// EGL's entry points.
pub(crate) struct Egl {
    pub(crate) get_proc_address: unsafe extern "C" fn(*const c_char) -> *const c_void,
    pub(crate) get_error: unsafe extern "C" fn() -> EGLint,
    pub(crate) get_display: unsafe extern "C" fn(*mut c_void) -> EGLDisplay,
    pub(crate) initialize: unsafe extern "C" fn(EGLDisplay, *mut EGLint, *mut EGLint) -> EGLBoolean,
    pub(crate) query_string: unsafe extern "C" fn(EGLDisplay, EGLint) -> *const c_char,
    pub(crate) bind_api: unsafe extern "C" fn(EGLenum) -> EGLBoolean,
    pub(crate) choose_config: unsafe extern "C" fn(
        EGLDisplay,
        *const EGLint,
        *mut EGLConfig,
        EGLint,
        *mut EGLint,
    ) -> EGLBoolean,
    pub(crate) create_context:
        unsafe extern "C" fn(EGLDisplay, EGLConfig, EGLContext, *const EGLint) -> EGLContext,
    pub(crate) destroy_context: unsafe extern "C" fn(EGLDisplay, EGLContext) -> EGLBoolean,
    pub(crate) create_pbuffer_surface:
        unsafe extern "C" fn(EGLDisplay, EGLConfig, *const EGLint) -> EGLSurface,
    pub(crate) create_window_surface:
        unsafe extern "C" fn(EGLDisplay, EGLConfig, *mut c_void, *const EGLint) -> EGLSurface,
    pub(crate) destroy_surface: unsafe extern "C" fn(EGLDisplay, EGLSurface) -> EGLBoolean,
    pub(crate) make_current:
        unsafe extern "C" fn(EGLDisplay, EGLSurface, EGLSurface, EGLContext) -> EGLBoolean,
    pub(crate) swap_buffers: unsafe extern "C" fn(EGLDisplay, EGLSurface) -> EGLBoolean,
    pub(crate) swap_interval: unsafe extern "C" fn(EGLDisplay, EGLint) -> EGLBoolean,
    pub(crate) query_surface:
        unsafe extern "C" fn(EGLDisplay, EGLSurface, EGLint, *mut EGLint) -> EGLBoolean,
    _library: Library,
}

// SAFETY: EGL's entry points may be called from any thread.
unsafe impl Send for Egl {}
// SAFETY: as above.
unsafe impl Sync for Egl {}

#[cfg(target_os = "android")]
const LIBRARY: &str = "libEGL.so";
#[cfg(not(target_os = "android"))]
const LIBRARY: &str = "libEGL.so.1";

impl Egl {
    /// The process's EGL, loaded on first use.
    pub(crate) fn get() -> Result<&'static Egl, String> {
        static EGL: OnceLock<Result<Egl, String>> = OnceLock::new();
        EGL.get_or_init(|| {
            // SAFETY: the system EGL library is sound to load.
            unsafe { Egl::load() }.map_err(|e| format!("can't load {LIBRARY}: {e}"))
        })
        .as_ref()
        .map_err(Clone::clone)
    }

    unsafe fn load() -> Result<Self, libloading::Error> {
        // SAFETY: the caller vouches for loading the library.
        let library = unsafe { Library::new(LIBRARY)? };
        macro_rules! get {
            ($name:literal) => {
                // SAFETY: each field's type is the EGL function's signature.
                unsafe { *library.get($name)? }
            };
        }
        Ok(Self {
            get_proc_address: get!(c"eglGetProcAddress"),
            get_error: get!(c"eglGetError"),
            get_display: get!(c"eglGetDisplay"),
            initialize: get!(c"eglInitialize"),
            query_string: get!(c"eglQueryString"),
            bind_api: get!(c"eglBindAPI"),
            choose_config: get!(c"eglChooseConfig"),
            create_context: get!(c"eglCreateContext"),
            destroy_context: get!(c"eglDestroyContext"),
            create_pbuffer_surface: get!(c"eglCreatePbufferSurface"),
            create_window_surface: get!(c"eglCreateWindowSurface"),
            destroy_surface: get!(c"eglDestroySurface"),
            make_current: get!(c"eglMakeCurrent"),
            swap_buffers: get!(c"eglSwapBuffers"),
            swap_interval: get!(c"eglSwapInterval"),
            query_surface: get!(c"eglQuerySurface"),
            _library: library,
        })
    }

    /// Resolves a GL or EGL function by name.
    pub(crate) fn proc_address(&self, name: &CStr) -> *const c_void {
        // SAFETY: `name` is a valid C string.
        unsafe { (self.get_proc_address)(name.as_ptr()) }
    }

    /// The last EGL error, as a hex code.
    pub(crate) fn error(&self) -> String {
        // SAFETY: no preconditions.
        let code = unsafe { (self.get_error)() };
        format!("EGL error {code:#x}")
    }

    /// Whether `display`'s extension string (or the client extensions, for a
    /// null display) lists `name`.
    pub(crate) fn has_extension(&self, display: EGLDisplay, name: &str) -> bool {
        // SAFETY: a null or initialized display is valid here.
        let extensions = unsafe { (self.query_string)(display, EGL_EXTENSIONS) };
        if extensions.is_null() {
            // A display that predates client extensions reports an error here.
            // SAFETY: no preconditions.
            unsafe { (self.get_error)() };
            return false;
        }
        // SAFETY: EGL returns a static C string.
        unsafe { CStr::from_ptr(extensions) }
            .to_str()
            .is_ok_and(|s| s.split_ascii_whitespace().any(|e| e == name))
    }

    /// The display for `platform` and its native display, or `None` if this
    /// EGL can't provide one.
    pub(crate) fn platform_display(
        &self,
        platform: EGLenum,
        native: *mut c_void,
    ) -> Option<EGLDisplay> {
        let display = if self.has_extension(std::ptr::null_mut(), "EGL_EXT_platform_base") {
            let f = self.proc_address(c"eglGetPlatformDisplayEXT");
            if f.is_null() {
                return None;
            }
            // SAFETY: the extension's documented signature.
            let f: GetPlatformDisplayExt = unsafe { std::mem::transmute(f) };
            // SAFETY: the caller passes a native display for the platform.
            unsafe { f(platform, native, std::ptr::null()) }
        } else {
            let f = self.proc_address(c"eglGetPlatformDisplay");
            if f.is_null() {
                return None;
            }
            // SAFETY: EGL 1.5's documented signature.
            let f: GetPlatformDisplay = unsafe { std::mem::transmute(f) };
            // SAFETY: as above.
            unsafe { f(platform, native, std::ptr::null()) }
        };
        (!display.is_null()).then_some(display)
    }
}

/// `libwayland-egl`, which wraps a Wayland surface in the window type EGL takes.
pub(crate) struct WaylandEgl {
    pub(crate) create: unsafe extern "C" fn(*mut c_void, i32, i32) -> *mut c_void,
    pub(crate) destroy: unsafe extern "C" fn(*mut c_void),
    pub(crate) resize: unsafe extern "C" fn(*mut c_void, i32, i32, i32, i32),
    _library: Library,
}

impl WaylandEgl {
    /// The process's libwayland-egl, loaded on first use.
    pub(crate) fn get() -> Result<&'static WaylandEgl, String> {
        static LIB: OnceLock<Result<WaylandEgl, String>> = OnceLock::new();
        LIB.get_or_init(|| {
            // SAFETY: the system library is sound to load.
            unsafe { WaylandEgl::load() }.map_err(|e| format!("can't load libwayland-egl: {e}"))
        })
        .as_ref()
        .map_err(Clone::clone)
    }

    unsafe fn load() -> Result<Self, libloading::Error> {
        // SAFETY: the caller vouches for loading the library.
        let library = unsafe { Library::new("libwayland-egl.so.1")? };
        // SAFETY: each field's type is the function's signature.
        unsafe {
            Ok(Self {
                create: *library.get(c"wl_egl_window_create")?,
                destroy: *library.get(c"wl_egl_window_destroy")?,
                resize: *library.get(c"wl_egl_window_resize")?,
                _library: library,
            })
        }
    }
}

// SAFETY: libwayland-egl's functions may be called from any thread.
unsafe impl Send for WaylandEgl {}
// SAFETY: as above.
unsafe impl Sync for WaylandEgl {}
