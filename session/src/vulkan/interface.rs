//! What `libretro_vulkan.h` defines: the structures a Vulkan core and the
//! session share, and the callbacks of the interface the core renders
//! through.

use std::ffi::{c_char, c_uint, c_void};
use std::sync::{Condvar, Mutex, PoisonError};

use ash::vk;

use super::{SYNC_INDEX_MASK, Shared};
use crate::ffi;

/// The version of `retro_hw_render_interface_vulkan` the session provides.
pub(crate) const INTERFACE_VERSION: c_uint = 5;

/// The newest context negotiation interface the session uses: version 1,
/// whose `create_device` lets a core make the device.
pub(crate) const NEGOTIATION_VERSION: c_uint = 1;

/// `retro_vulkan_image`: an image the core hands over with `set_image`.
#[repr(C)]
pub(crate) struct RetroVulkanImage {
    pub(crate) image_view: vk::ImageView,
    pub(crate) image_layout: vk::ImageLayout,
    pub(crate) create_info: vk::ImageViewCreateInfo<'static>,
}

/// `retro_vulkan_context`: the device a core made in `create_device`.
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub(crate) struct RetroVulkanContext {
    pub(crate) gpu: vk::PhysicalDevice,
    pub(crate) device: vk::Device,
    pub(crate) queue: vk::Queue,
    pub(crate) queue_family_index: u32,
    pub(crate) presentation_queue: vk::Queue,
    pub(crate) presentation_queue_family_index: u32,
}

/// `retro_vulkan_create_device_t`.
pub(crate) type CreateDevice = unsafe extern "C" fn(
    context: *mut RetroVulkanContext,
    instance: vk::Instance,
    gpu: vk::PhysicalDevice,
    surface: vk::SurfaceKHR,
    get_instance_proc_addr: vk::PFN_vkGetInstanceProcAddr,
    required_device_extensions: *const *const c_char,
    num_required_device_extensions: c_uint,
    required_device_layers: *const *const c_char,
    num_required_device_layers: c_uint,
    required_features: *const vk::PhysicalDeviceFeatures,
) -> bool;

/// The fields of `retro_hw_render_context_negotiation_interface_vulkan` that
/// version 1 defines. Later versions only add fields after them.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct Negotiation {
    interface_type: ffi::retro_hw_render_context_negotiation_interface_type,
    interface_version: c_uint,
    get_application_info: Option<unsafe extern "C" fn() -> *const vk::ApplicationInfo<'static>>,
    pub(crate) create_device: Option<CreateDevice>,
    pub(crate) destroy_device: Option<unsafe extern "C" fn()>,
}

impl Negotiation {
    /// Copies the interface a core passed to
    /// `SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE`, if it's for Vulkan.
    ///
    /// # Safety
    ///
    /// `data` is null or points to a negotiation interface.
    pub(crate) unsafe fn read(data: *const c_void) -> Option<Self> {
        // SAFETY: every negotiation interface starts with its type.
        let header = unsafe {
            data.cast::<ffi::retro_hw_render_context_negotiation_interface>()
                .as_ref()?
        };
        if header.interface_type != ffi::RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN
            || header.interface_version == 0
        {
            return None;
        }
        // SAFETY: a Vulkan interface of version 1 or later has these fields.
        Some(unsafe { *data.cast::<Self>() })
    }

    /// The application info the core wants the instance created with.
    pub(crate) fn application_info(&self) -> Option<vk::ApplicationInfo<'static>> {
        // SAFETY: the core's function returns null or a pointer to info that
        // stays valid while the core is loaded.
        self.get_application_info
            .and_then(|f| unsafe { f().as_ref() }.copied())
    }
}

/// `retro_hw_render_interface_vulkan`.
#[repr(C)]
pub(crate) struct RenderInterface {
    interface_type: ffi::retro_hw_render_interface_type,
    interface_version: c_uint,
    handle: *mut c_void,
    instance: vk::Instance,
    gpu: vk::PhysicalDevice,
    device: vk::Device,
    get_device_proc_addr: vk::PFN_vkGetDeviceProcAddr,
    get_instance_proc_addr: vk::PFN_vkGetInstanceProcAddr,
    queue: vk::Queue,
    queue_index: c_uint,
    set_image:
        unsafe extern "C" fn(*mut c_void, *const RetroVulkanImage, u32, *const vk::Semaphore, u32),
    get_sync_index: unsafe extern "C" fn(*mut c_void) -> u32,
    get_sync_index_mask: unsafe extern "C" fn(*mut c_void) -> u32,
    set_command_buffers: unsafe extern "C" fn(*mut c_void, u32, *const vk::CommandBuffer),
    wait_sync_index: unsafe extern "C" fn(*mut c_void),
    lock_queue: unsafe extern "C" fn(*mut c_void),
    unlock_queue: unsafe extern "C" fn(*mut c_void),
    set_signal_semaphore: unsafe extern "C" fn(*mut c_void, vk::Semaphore),
}

/// The handles a [`RenderInterface`] hands the core.
pub(crate) struct Handles {
    pub(crate) instance: vk::Instance,
    pub(crate) gpu: vk::PhysicalDevice,
    pub(crate) device: vk::Device,
    pub(crate) get_device_proc_addr: vk::PFN_vkGetDeviceProcAddr,
    pub(crate) get_instance_proc_addr: vk::PFN_vkGetInstanceProcAddr,
    pub(crate) queue: vk::Queue,
    pub(crate) queue_family: u32,
}

impl RenderInterface {
    /// The interface for these handles, whose callbacks reach `shared`, from
    /// any thread the core calls them on.
    pub(crate) fn new(handles: Handles, shared: &Shared) -> Self {
        Self {
            interface_type: ffi::RETRO_HW_RENDER_INTERFACE_VULKAN,
            interface_version: INTERFACE_VERSION,
            handle: std::ptr::from_ref(shared).cast_mut().cast(),
            instance: handles.instance,
            gpu: handles.gpu,
            device: handles.device,
            get_device_proc_addr: handles.get_device_proc_addr,
            get_instance_proc_addr: handles.get_instance_proc_addr,
            queue: handles.queue,
            queue_index: handles.queue_family,
            set_image,
            get_sync_index,
            get_sync_index_mask,
            set_command_buffers,
            wait_sync_index,
            lock_queue,
            unlock_queue,
            set_signal_semaphore,
        }
    }
}

/// Keeps the session and a core's threads from using the queue at once, as
/// Vulkan requires. The core takes it through `lock_queue` and
/// `unlock_queue`, which can't hold a guard between them.
#[derive(Default)]
pub(crate) struct QueueLock {
    locked: Mutex<bool>,
    released: Condvar,
}

impl QueueLock {
    fn lock(&self) {
        let mut locked = self.locked.lock().unwrap_or_else(PoisonError::into_inner);
        while *locked {
            locked = self
                .released
                .wait(locked)
                .unwrap_or_else(PoisonError::into_inner);
        }
        *locked = true;
    }

    fn unlock(&self) {
        *self.locked.lock().unwrap_or_else(PoisonError::into_inner) = false;
        self.released.notify_one();
    }

    /// Runs `f` with the queue locked.
    pub(crate) fn with<R>(&self, f: impl FnOnce() -> R) -> R {
        self.lock();
        let result = f();
        self.unlock();
        result
    }
}

/// A slice from a pointer and a count the core passed, empty for null.
///
/// # Safety
///
/// `data` is null or points to `count` values.
unsafe fn slice<'a, T>(data: *const T, count: u32) -> &'a [T] {
    if data.is_null() || count == 0 {
        return &[];
    }
    // SAFETY: forwarded from the caller.
    unsafe { std::slice::from_raw_parts(data, count as usize) }
}

/// The shared state behind an interface's handle.
///
/// # Safety
///
/// `handle` is the handle of an interface whose context is alive, as it is
/// while the core may call the interface.
unsafe fn shared<'a>(handle: *mut c_void) -> Option<&'a Shared> {
    // SAFETY: forwarded from the caller.
    unsafe { handle.cast::<Shared>().as_ref() }
}

unsafe extern "C" fn set_image(
    handle: *mut c_void,
    image: *const RetroVulkanImage,
    num_semaphores: u32,
    semaphores: *const vk::Semaphore,
    _src_queue_family: u32,
) {
    // SAFETY: the core passes its handle, and a valid image and semaphore
    // array, which last for the call.
    let (shared, image, semaphores) = unsafe {
        (
            shared(handle),
            image.as_ref(),
            slice(semaphores, num_semaphores),
        )
    };
    if let (Some(shared), Some(image)) = (shared, image) {
        shared.set_image(image, semaphores);
    }
}

unsafe extern "C" fn get_sync_index(handle: *mut c_void) -> u32 {
    // SAFETY: the core passes its handle.
    unsafe { shared(handle) }.map_or(0, Shared::sync_index)
}

unsafe extern "C" fn get_sync_index_mask(_handle: *mut c_void) -> u32 {
    SYNC_INDEX_MASK
}

unsafe extern "C" fn set_command_buffers(
    handle: *mut c_void,
    num_cmd: u32,
    cmd: *const vk::CommandBuffer,
) {
    // SAFETY: the core passes its handle and `num_cmd` command buffers.
    let (shared, commands) = unsafe { (shared(handle), slice(cmd, num_cmd)) };
    if let Some(shared) = shared {
        shared.set_command_buffers(commands);
    }
}

unsafe extern "C" fn wait_sync_index(handle: *mut c_void) {
    // SAFETY: the core passes its handle.
    if let Some(shared) = unsafe { shared(handle) } {
        shared.wait_sync_index();
    }
}

unsafe extern "C" fn lock_queue(handle: *mut c_void) {
    // SAFETY: the core passes its handle.
    if let Some(shared) = unsafe { shared(handle) } {
        shared.queue_lock.lock();
    }
}

unsafe extern "C" fn unlock_queue(handle: *mut c_void) {
    // SAFETY: the core passes its handle.
    if let Some(shared) = unsafe { shared(handle) } {
        shared.queue_lock.unlock();
    }
}

unsafe extern "C" fn set_signal_semaphore(handle: *mut c_void, semaphore: vk::Semaphore) {
    // SAFETY: the core passes its handle.
    if let Some(shared) = unsafe { shared(handle) } {
        shared.set_signal_semaphore(semaphore);
    }
}
