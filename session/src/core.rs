//! Loading a libretro core and resolving its entry points.

use std::ffi::{CStr, c_uint, c_void};
use std::path::Path;

use libloading::Library;

use crate::ffi;

/// A loaded core's entry points. The library stays loaded while this lives.
pub(crate) struct Core {
    pub(crate) init: unsafe extern "C" fn(),
    pub(crate) deinit: unsafe extern "C" fn(),
    pub(crate) get_system_info: unsafe extern "C" fn(*mut ffi::retro_system_info),
    pub(crate) get_system_av_info: unsafe extern "C" fn(*mut ffi::retro_system_av_info),
    pub(crate) set_environment: unsafe extern "C" fn(ffi::retro_environment_t),
    pub(crate) set_video_refresh: unsafe extern "C" fn(ffi::retro_video_refresh_t),
    pub(crate) set_audio_sample: unsafe extern "C" fn(ffi::retro_audio_sample_t),
    pub(crate) set_audio_sample_batch: unsafe extern "C" fn(ffi::retro_audio_sample_batch_t),
    pub(crate) set_input_poll: unsafe extern "C" fn(ffi::retro_input_poll_t),
    pub(crate) set_input_state: unsafe extern "C" fn(ffi::retro_input_state_t),
    pub(crate) set_controller_port_device: unsafe extern "C" fn(c_uint, c_uint),
    pub(crate) run: unsafe extern "C" fn(),
    pub(crate) serialize_size: unsafe extern "C" fn() -> usize,
    pub(crate) serialize: unsafe extern "C" fn(*mut c_void, usize) -> bool,
    pub(crate) unserialize: unsafe extern "C" fn(*const c_void, usize) -> bool,
    pub(crate) load_game: unsafe extern "C" fn(*const ffi::retro_game_info) -> bool,
    pub(crate) unload_game: unsafe extern "C" fn(),
    pub(crate) get_memory_data: unsafe extern "C" fn(c_uint) -> *mut c_void,
    pub(crate) get_memory_size: unsafe extern "C" fn(c_uint) -> usize,
    _library: Library,
}

/// Why a core couldn't load.
#[derive(Debug, thiserror::Error)]
pub enum CoreLoadError {
    #[error("can't open the core library")]
    Open(#[source] libloading::Error),
    #[error("the core has no {0} function")]
    MissingSymbol(&'static str, #[source] libloading::Error),
    #[error("the core implements libretro API version {0}, not {expected}", expected = ffi::RETRO_API_VERSION)]
    ApiVersion(c_uint),
}

impl Core {
    /// Loads the core at `path`.
    ///
    /// # Safety
    ///
    /// Loading a library runs its initializers, and the library must be a
    /// libretro core: its `retro_*` symbols must have libretro's signatures.
    pub(crate) unsafe fn load(path: &Path) -> Result<Self, CoreLoadError> {
        // SAFETY: the caller vouches for the library.
        let library = unsafe { Library::new(path) }.map_err(CoreLoadError::Open)?;

        /// Resolves a function, with the signature of the field it fills.
        ///
        /// # Safety
        ///
        /// `T` is the symbol's real signature.
        unsafe fn get<T: Copy>(library: &Library, name: &'static CStr) -> Result<T, CoreLoadError> {
            // SAFETY: forwarded from the caller.
            unsafe { library.get::<T>(name) }
                .map(|symbol| *symbol)
                .map_err(|e| CoreLoadError::MissingSymbol(name.to_str().expect("ASCII symbol"), e))
        }

        // SAFETY: the caller vouches that these are libretro's signatures.
        let core = unsafe {
            Core {
                init: get(&library, c"retro_init")?,
                deinit: get(&library, c"retro_deinit")?,
                get_system_info: get(&library, c"retro_get_system_info")?,
                get_system_av_info: get(&library, c"retro_get_system_av_info")?,
                set_environment: get(&library, c"retro_set_environment")?,
                set_video_refresh: get(&library, c"retro_set_video_refresh")?,
                set_audio_sample: get(&library, c"retro_set_audio_sample")?,
                set_audio_sample_batch: get(&library, c"retro_set_audio_sample_batch")?,
                set_input_poll: get(&library, c"retro_set_input_poll")?,
                set_input_state: get(&library, c"retro_set_input_state")?,
                set_controller_port_device: get(&library, c"retro_set_controller_port_device")?,
                run: get(&library, c"retro_run")?,
                serialize_size: get(&library, c"retro_serialize_size")?,
                serialize: get(&library, c"retro_serialize")?,
                unserialize: get(&library, c"retro_unserialize")?,
                load_game: get(&library, c"retro_load_game")?,
                unload_game: get(&library, c"retro_unload_game")?,
                get_memory_data: get(&library, c"retro_get_memory_data")?,
                get_memory_size: get(&library, c"retro_get_memory_size")?,
                _library: library,
            }
        };

        // SAFETY: resolved with libretro's signature.
        let api_version: unsafe extern "C" fn() -> c_uint =
            unsafe { get(&core._library, c"retro_api_version")? };
        // SAFETY: retro_api_version may be called before retro_init.
        let version = unsafe { api_version() };
        if version != ffi::RETRO_API_VERSION {
            return Err(CoreLoadError::ApiVersion(version));
        }
        Ok(core)
    }
}
