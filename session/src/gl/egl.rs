//! EGL, loaded at run time so that the library has no link-time dependency
//! on a GL stack, and libwayland-egl, which wraps a Wayland surface for it.

use std::ffi::c_void;
use std::sync::OnceLock;

pub(crate) use khronos_egl::*;
use libloading::Library;

/// EGL 1.4, which is as far as some Linux GPU drivers go.
pub(crate) type Egl = DynamicInstance<EGL1_4>;

pub(crate) const PLATFORM_X11_KHR: Enum = 0x31D5;
pub(crate) const PLATFORM_WAYLAND_KHR: Enum = 0x31D8;
pub(crate) const PLATFORM_SURFACELESS_MESA: Enum = 0x31DD;

/// The process's EGL, loaded on first use.
pub(crate) fn instance() -> Result<&'static Egl, String> {
    static EGL: OnceLock<Result<Egl, String>> = OnceLock::new();
    EGL.get_or_init(|| {
        // SAFETY: the system EGL library is sound to load.
        unsafe { Egl::load_required() }.map_err(|e| format!("can't load libEGL: {e}"))
    })
    .as_ref()
    .map_err(Clone::clone)
}

/// Whether `display`'s extension string, or the client extensions for no
/// display, lists `name`.
pub(crate) fn has_extension(egl: &Egl, display: Option<Display>, name: &str) -> bool {
    // A display that predates client extensions has no client extension
    // string.
    egl.query_string(display, EXTENSIONS)
        .is_ok_and(|extensions| {
            extensions
                .to_bytes()
                .split(u8::is_ascii_whitespace)
                .any(|e| e == name.as_bytes())
        })
}

/// The display for `platform` and its native display, or `None` if this EGL
/// can't provide one.
pub(crate) fn platform_display(egl: &Egl, platform: Enum, native: *mut c_void) -> Option<Display> {
    if !has_extension(egl, None, "EGL_EXT_platform_base") {
        let egl = egl.upcast::<EGL1_5>()?;
        // SAFETY: the caller passes a native display for the platform.
        return unsafe { egl.get_platform_display(platform, native, &[ATTRIB_NONE]) }.ok();
    }
    type GetPlatformDisplayExt = unsafe extern "C" fn(Enum, *mut c_void, *const Int) -> EGLDisplay;
    let f = egl.get_proc_address("eglGetPlatformDisplayEXT")?;
    // SAFETY: the extension's documented signature.
    let f: GetPlatformDisplayExt = unsafe { std::mem::transmute(f) };
    // SAFETY: as above.
    let display = unsafe { f(platform, native, std::ptr::null()) };
    // SAFETY: a display EGL returned.
    (!display.is_null()).then(|| unsafe { Display::from_ptr(display) })
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
