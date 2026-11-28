//! The Vulkan side of a session, for cores that render with Vulkan: the
//! instance and device they render on, the libretro interface they reach
//! them through, and showing the images they hand over, presented through a
//! window's swapchain or read back for a sink.
//!
//! The Vulkan loader is loaded at run time, as EGL is, so the library still
//! loads where Vulkan is missing.
//!
//! Frames rotate through [`FRAMES`] slots, the sync indices cores see. Each
//! has a fence on the session's last submission in it, which the session
//! waits for before the core runs the slot's next frame, so a core may reuse
//! what it used in that slot then.
//!
//! A context presents to up to two windows: the main one and, for a
//! two-screen system whose core draws its screens stacked, a lower one. With
//! both, each shows its half of the one frame, as [`crate::gl`] does.
//!
//! Cores may call the interface from threads of their own, as PPSSPP hands
//! over its images from its submission thread, so what the interface reaches
//! is behind a lock, in [`Shared`].

mod interface;

use std::ffi::c_char;
use std::sync::{Mutex, MutexGuard, OnceLock, PoisonError};

use ash::{khr, vk};

use interface::{Handles, QueueLock, RenderInterface, RetroVulkanContext, RetroVulkanImage};
pub(crate) use interface::{NEGOTIATION_VERSION, Negotiation};

use crate::gl::{NativeWindow, SizedWindow, Target};
use crate::video::{Region, Screen};

/// Why the session couldn't set up or use Vulkan.
#[derive(Debug, thiserror::Error)]
pub enum VulkanError {
    #[error("Vulkan isn't available: {0}")]
    Unavailable(String),
    #[error("{0}")]
    Vulkan(String),
}

/// How many frames may be in flight at once.
const FRAMES: usize = 2;

/// The sync indices a core may see, as `get_sync_index_mask` reports them.
const SYNC_INDEX_MASK: u32 = (1 << FRAMES) - 1;

/// The process's Vulkan loader, loaded on first use.
pub(crate) fn entry() -> Result<&'static ash::Entry, String> {
    static ENTRY: OnceLock<Result<ash::Entry, String>> = OnceLock::new();
    ENTRY
        .get_or_init(|| {
            // SAFETY: the system's Vulkan loader is sound to load.
            unsafe { ash::Entry::load() }.map_err(|e| format!("can't load the Vulkan loader: {e}"))
        })
        .as_ref()
        .map_err(Clone::clone)
}

/// The Vulkan instance and device a core renders on, and what the session
/// shows its frames with. It lives on the session's thread.
pub(crate) struct VulkanContext {
    instance: ash::Instance,
    gpu: vk::PhysicalDevice,
    device: ash::Device,
    queue: vk::Queue,
    queue_family: u32,
    memory: vk::PhysicalDeviceMemoryProperties,
    /// The core's `destroy_device`, when the core made the device.
    destroy_device: Option<unsafe extern "C" fn()>,
    /// Present only when the context can present to windows.
    surface_fn: Option<khr::surface::Instance>,
    swapchain_fn: Option<khr::swapchain::Device>,
    #[cfg(target_os = "android")]
    android_surface_fn: Option<khr::android_surface::Instance>,
    /// Each [`Screen`]'s window.
    windows: [Option<Window>; 2],
    frames: Vec<FrameSlot>,
    readback: Option<Readback>,
    shared: Box<Shared>,
    interface: Box<RenderInterface>,
    warned_format: bool,
}

/// What the interface's callbacks reach, from any thread.
pub(crate) struct Shared {
    device: ash::Device,
    pub(crate) queue_lock: QueueLock,
    frame: Mutex<CoreFrame>,
}

/// The core's side of the next submission, and the frame slots' fences.
#[derive(Default)]
struct CoreFrame {
    /// The image the core last handed over.
    image: Option<CoreImage>,
    /// Semaphores the next submission waits for, from `set_image`.
    wait_semaphores: Vec<vk::Semaphore>,
    /// Command buffers the next submission runs first, from
    /// `set_command_buffers`.
    commands: Vec<vk::CommandBuffer>,
    /// A semaphore the next submission signals, from `set_signal_semaphore`.
    signal_semaphore: vk::Semaphore,
    /// The current frame slot.
    slot: usize,
    /// Each slot's fence, on the session's last submission in it.
    fences: Vec<vk::Fence>,
    /// Whether each slot's fence is on a submission not yet waited for.
    pending: Vec<bool>,
}

impl Shared {
    fn frame(&self) -> MutexGuard<'_, CoreFrame> {
        self.frame.lock().unwrap_or_else(PoisonError::into_inner)
    }

    pub(crate) fn set_image(&self, image: &RetroVulkanImage, semaphores: &[vk::Semaphore]) {
        let range = image.create_info.subresource_range;
        let mut frame = self.frame();
        frame.image = Some(CoreImage {
            image: image.create_info.image,
            slot: frame.slot,
            layout: image.image_layout,
            format: image.create_info.format,
            mip_level: range.base_mip_level,
            array_layer: range.base_array_layer,
        });
        frame.wait_semaphores.clear();
        frame.wait_semaphores.extend_from_slice(semaphores);
    }

    pub(crate) fn set_command_buffers(&self, commands: &[vk::CommandBuffer]) {
        let mut frame = self.frame();
        frame.commands.clear();
        frame.commands.extend_from_slice(commands);
    }

    pub(crate) fn set_signal_semaphore(&self, semaphore: vk::Semaphore) {
        self.frame().signal_semaphore = semaphore;
    }

    pub(crate) fn sync_index(&self) -> u32 {
        self.frame().slot as u32
    }

    /// Waits for the session's last work in the current slot to finish.
    pub(crate) fn wait_sync_index(&self) {
        let mut frame = self.frame();
        let slot = frame.slot;
        self.wait(&mut frame, slot);
    }

    /// Waits for the session's last work in `slot` to finish.
    fn wait(&self, frame: &mut CoreFrame, slot: usize) {
        if frame.pending[slot] {
            // SAFETY: the fence is the slot's, on a submitted batch, and the
            // lock on `frame` keeps it from being reset meanwhile.
            if let Err(e) = unsafe {
                self.device
                    .wait_for_fences(&[frame.fences[slot]], true, u64::MAX)
            } {
                log::error!("can't wait for a frame: {e}");
            }
            frame.pending[slot] = false;
        }
    }
}

/// The parts of a core's image the session uses.
#[derive(Clone, Copy, Debug)]
struct CoreImage {
    image: vk::Image,
    /// The frame slot the core handed it over in, whose fence the core
    /// waits for before it reuses the image.
    slot: usize,
    layout: vk::ImageLayout,
    format: vk::Format,
    mip_level: u32,
    array_layer: u32,
}

impl CoreImage {
    fn layers(&self) -> vk::ImageSubresourceLayers {
        vk::ImageSubresourceLayers {
            aspect_mask: vk::ImageAspectFlags::COLOR,
            mip_level: self.mip_level,
            base_array_layer: self.array_layer,
            layer_count: 1,
        }
    }

    fn range(&self) -> vk::ImageSubresourceRange {
        vk::ImageSubresourceRange {
            aspect_mask: vk::ImageAspectFlags::COLOR,
            base_mip_level: self.mip_level,
            level_count: 1,
            base_array_layer: self.array_layer,
            layer_count: 1,
        }
    }

    /// The layout the session copies from: the core's own, if it's
    /// `GENERAL`, since libretro then rules out transitions.
    fn source_layout(&self) -> vk::ImageLayout {
        if self.layout == vk::ImageLayout::GENERAL {
            vk::ImageLayout::GENERAL
        } else {
            vk::ImageLayout::TRANSFER_SRC_OPTIMAL
        }
    }
}

/// One frame in flight. Its fence is in [`CoreFrame`].
struct FrameSlot {
    pool: vk::CommandPool,
    commands: vk::CommandBuffer,
    /// Signaled when the swapchain image acquired in this slot is ready, one
    /// for each [`Screen`].
    acquired: [vk::Semaphore; 2],
}

/// An attached window: its surface and swapchain.
struct Window {
    surface: vk::SurfaceKHR,
    /// The window's size in pixels, for surfaces that leave it to the
    /// swapchain.
    size: (u32, u32),
    swapchain: vk::SwapchainKHR,
    images: Vec<vk::Image>,
    /// One per swapchain image, signaled when its frame is drawn.
    drawn: Vec<vk::Semaphore>,
    extent: vk::Extent2D,
    /// Whether the swapchain needs making again before the next frame.
    stale: bool,
}

/// A swapchain image acquired for a frame, and what goes on it.
struct Draw {
    output: Screen,
    swapchain: vk::SwapchainKHR,
    index: u32,
    target: vk::Image,
    /// Signaled when `target` is ready to draw to.
    acquired: vk::Semaphore,
    /// Signaled when `target` is drawn.
    drawn: vk::Semaphore,
    blit: Blit,
}

/// Where a region of the core's image goes on a swapchain image.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Blit {
    /// The region's first row, counted from the image's top, and how many.
    rows: (u32, u32),
    /// The region's first column, counted from the image's left, and how
    /// many.
    columns: (u32, u32),
    /// Where it goes: x, y, width and height.
    rect: (i32, i32, i32, i32),
}

impl Blit {
    /// Shows `region` of the `width` by `height` region at the core image's
    /// origin, scaled to fit an `extent` image at the region's share of
    /// `aspect` and centered.
    fn new(width: u32, height: u32, aspect: f32, region: Region, extent: vk::Extent2D) -> Self {
        Self {
            rows: region.rows(height),
            columns: region.columns(width),
            rect: region.fit(width, height, aspect, extent.width, extent.height),
        }
    }
}

/// A host-visible buffer frames are read back into.
struct Readback {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    size: u64,
    mapped: *const u8,
}

fn failed(what: &'static str) -> impl FnOnce(vk::Result) -> VulkanError {
    move |e| VulkanError::Vulkan(format!("can't {what}: {e}"))
}

impl VulkanContext {
    /// Creates the instance and device, through the core's negotiation
    /// interface if it has one, and attaches the window if `target` has one.
    pub(crate) fn new(
        negotiation: Option<&Negotiation>,
        target: Target,
    ) -> Result<Self, VulkanError> {
        let entry = entry().map_err(VulkanError::Unavailable)?;
        let windowed = matches!(target, Target::Window(_));
        if windowed && !cfg!(target_os = "android") {
            return Err(VulkanError::Vulkan(
                "the session presents Vulkan frames only to Android windows".into(),
            ));
        }
        let mut extensions: Vec<*const c_char> = Vec::new();
        if windowed {
            extensions.push(khr::surface::NAME.as_ptr());
            #[cfg(target_os = "android")]
            extensions.push(khr::android_surface::NAME.as_ptr());
        }
        let mut app = negotiation
            .and_then(Negotiation::application_info)
            .unwrap_or_else(|| {
                vk::ApplicationInfo::default()
                    .application_name(c"Handheld")
                    .engine_name(c"Handheld")
                    .api_version(vk::API_VERSION_1_0)
            });
        // Cores may use 1.1 functions without asking, as they can in
        // RetroArch, which asks for 1.1 where the loader has it.
        // SAFETY: no preconditions.
        let loader_version = unsafe { entry.try_enumerate_instance_version() }
            .ok()
            .flatten()
            .unwrap_or(vk::API_VERSION_1_0);
        if app.api_version < vk::API_VERSION_1_1 && loader_version >= vk::API_VERSION_1_1 {
            app.api_version = vk::API_VERSION_1_1;
        }
        let info = vk::InstanceCreateInfo::default()
            .application_info(&app)
            .enabled_extension_names(&extensions);
        // SAFETY: a valid create info.
        let instance = unsafe { entry.create_instance(&info, None) }.map_err(|e| match e {
            vk::Result::ERROR_INCOMPATIBLE_DRIVER
            | vk::Result::ERROR_INITIALIZATION_FAILED
            | vk::Result::ERROR_EXTENSION_NOT_PRESENT => {
                VulkanError::Unavailable(format!("can't create an instance: {e}"))
            }
            e => VulkanError::Vulkan(format!("can't create an instance: {e}")),
        })?;
        let mut this = Self::with_instance(entry, instance.clone(), negotiation, target)
            // SAFETY: nothing else has the instance.
            .inspect_err(|_| unsafe { instance.destroy_instance(None) })?;
        // From here on, dropping `this` tears everything down.
        this.init(target)?;
        Ok(this)
    }

    /// Picks the GPU and makes the device on `instance`. On an error, the
    /// caller destroys the instance.
    fn with_instance(
        entry: &ash::Entry,
        instance: ash::Instance,
        negotiation: Option<&Negotiation>,
        target: Target,
    ) -> Result<Self, VulkanError> {
        let windowed = matches!(target, Target::Window(_));
        let (mut gpu, mut queue_family) = choose_gpu(&instance)?;
        let required: Vec<*const c_char> = if windowed {
            vec![khr::swapchain::NAME.as_ptr()]
        } else {
            Vec::new()
        };
        let get_instance_proc_addr = entry.static_fn().get_instance_proc_addr;

        let mut made_by_core = None;
        if let Some(create_device) = negotiation.and_then(|n| n.create_device) {
            let mut context = RetroVulkanContext::default();
            let features = vk::PhysicalDeviceFeatures::default();
            // SAFETY: the core's function, with the arguments libretro
            // documents; the session has no surface yet.
            let made = unsafe {
                create_device(
                    &mut context,
                    instance.handle(),
                    gpu,
                    vk::SurfaceKHR::null(),
                    get_instance_proc_addr,
                    required.as_ptr(),
                    required.len() as u32,
                    std::ptr::null(),
                    0,
                    &features,
                )
            };
            if made && context.device != vk::Device::null() {
                made_by_core = Some(context);
            } else {
                log::warn!("the core couldn't make its Vulkan device, so the session makes one");
            }
        }
        let (device, queue, destroy_device) = match made_by_core {
            Some(context) => {
                if context.gpu != vk::PhysicalDevice::null() {
                    gpu = context.gpu;
                }
                queue_family = context.queue_family_index;
                // SAFETY: the core made the device on this instance.
                let device = unsafe { ash::Device::load(instance.fp_v1_0(), context.device) };
                (
                    device,
                    context.queue,
                    negotiation.and_then(|n| n.destroy_device),
                )
            }
            None => {
                let priorities = [1.0];
                let queues = [vk::DeviceQueueCreateInfo::default()
                    .queue_family_index(queue_family)
                    .queue_priorities(&priorities)];
                let info = vk::DeviceCreateInfo::default()
                    .queue_create_infos(&queues)
                    .enabled_extension_names(&required);
                // SAFETY: a valid create info for this GPU.
                let device = unsafe { instance.create_device(gpu, &info, None) }
                    .map_err(failed("create a device"))?;
                // SAFETY: the device has a queue in this family.
                let queue = unsafe { device.get_device_queue(queue_family, 0) };
                (device, queue, None)
            }
        };

        // SAFETY: a GPU of this instance.
        let (properties, memory) = unsafe {
            (
                instance.get_physical_device_properties(gpu),
                instance.get_physical_device_memory_properties(gpu),
            )
        };
        log::info!(
            "Vulkan device: {} (Vulkan {}.{}.{}), made by {}",
            properties
                .device_name_as_c_str()
                .unwrap_or_default()
                .to_string_lossy(),
            vk::api_version_major(properties.api_version),
            vk::api_version_minor(properties.api_version),
            vk::api_version_patch(properties.api_version),
            if made_by_core.is_some() {
                "the core"
            } else {
                "the session"
            },
        );

        let shared = Box::new(Shared {
            device: device.clone(),
            queue_lock: QueueLock::default(),
            frame: Mutex::default(),
        });
        let interface = Box::new(RenderInterface::new(
            Handles {
                instance: instance.handle(),
                gpu,
                device: device.handle(),
                get_device_proc_addr: instance.fp_v1_0().get_device_proc_addr,
                get_instance_proc_addr,
                queue,
                queue_family,
            },
            &shared,
        ));
        Ok(Self {
            surface_fn: windowed.then(|| khr::surface::Instance::new(entry, &instance)),
            swapchain_fn: windowed.then(|| khr::swapchain::Device::new(&instance, &device)),
            #[cfg(target_os = "android")]
            android_surface_fn: windowed
                .then(|| khr::android_surface::Instance::new(entry, &instance)),
            instance,
            gpu,
            device,
            queue,
            queue_family,
            memory,
            destroy_device,
            windows: [None, None],
            frames: Vec::new(),
            readback: None,
            shared,
            interface,
            warned_format: false,
        })
    }

    /// Creates the frame slots and attaches the window, if any.
    fn init(&mut self, target: Target) -> Result<(), VulkanError> {
        for _ in 0..FRAMES {
            // SAFETY: a valid create info; the fence is destroyed on drop.
            let fence = unsafe {
                self.device
                    .create_fence(&vk::FenceCreateInfo::default(), None)
            }
            .map_err(failed("create a fence"))?;
            let mut frame = self.shared.frame();
            frame.fences.push(fence);
            frame.pending.push(false);
            drop(frame);
            let slot = self.create_slot()?;
            self.frames.push(slot);
        }
        if let Target::Window(Some(window)) = target {
            self.set_window(Screen::Main, Some(window))?;
        }
        Ok(())
    }

    fn create_slot(&self) -> Result<FrameSlot, VulkanError> {
        let d = &self.device;
        // SAFETY: valid create infos; each object is destroyed on drop.
        unsafe {
            let pool = d
                .create_command_pool(
                    &vk::CommandPoolCreateInfo::default()
                        .queue_family_index(self.queue_family)
                        .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER),
                    None,
                )
                .map_err(failed("create a command pool"))?;
            let result = (|| {
                let commands = d.allocate_command_buffers(
                    &vk::CommandBufferAllocateInfo::default()
                        .command_pool(pool)
                        .level(vk::CommandBufferLevel::PRIMARY)
                        .command_buffer_count(1),
                )?[0];
                let mut acquired = [vk::Semaphore::null(); 2];
                for semaphore in &mut acquired {
                    match d.create_semaphore(&vk::SemaphoreCreateInfo::default(), None) {
                        Ok(s) => *semaphore = s,
                        Err(e) => {
                            for &s in &acquired {
                                d.destroy_semaphore(s, None);
                            }
                            return Err(e);
                        }
                    }
                }
                Ok(FrameSlot {
                    pool,
                    commands,
                    acquired,
                })
            })();
            result.map_err(|e| {
                d.destroy_command_pool(pool, None);
                failed("create a frame's objects")(e)
            })
        }
    }

    /// The interface the core renders through.
    pub(crate) fn interface(&self) -> *const RenderInterface {
        &raw const *self.interface
    }

    /// Moves to the next frame slot, waiting until the session's last work
    /// in it is done, before the core runs a frame.
    pub(crate) fn begin_frame(&mut self) {
        let mut frame = self.shared.frame();
        frame.slot = (frame.slot + 1) % FRAMES;
        let slot = frame.slot;
        self.shared.wait(&mut frame, slot);
    }

    /// Waits for the device to finish all work, and forgets the core's
    /// image, before the core may destroy it, as when it resets.
    pub(crate) fn release_image(&mut self) {
        self.wait_idle();
        self.shared.frame().image = None;
    }

    /// Waits for the device to finish all work.
    fn wait_idle(&mut self) {
        // SAFETY: the queue lock keeps others off the queue meanwhile.
        if let Err(e) = self
            .shared
            .queue_lock
            .with(|| unsafe { self.device.device_wait_idle() })
        {
            log::error!("can't wait for the device: {e}");
        }
        self.shared.frame().pending.fill(false);
    }

    /// Submits the core's pending work, then the commands `record` records,
    /// waiting for the `acquired` semaphores and signaling the `drawn` ones,
    /// on the current slot's fence.
    fn submit(
        &mut self,
        acquired: &[vk::Semaphore],
        drawn: &[vk::Semaphore],
        record: impl FnOnce(&ash::Device, vk::CommandBuffer),
    ) -> Result<(), vk::Result> {
        let shared = &*self.shared;
        // The queue lock goes first, as a core may hold it while it calls the
        // interface, which takes the frame's lock.
        shared.queue_lock.with(|| {
            let mut frame = shared.frame();
            let slot = frame.slot;
            shared.wait(&mut frame, slot);
            let (cmd, fence) = (self.frames[slot].commands, frame.fences[slot]);
            let d = &self.device;
            // SAFETY: the slot's last submission is done, so its command
            // buffer is free to record.
            unsafe {
                d.reset_command_buffer(cmd, vk::CommandBufferResetFlags::empty())?;
                d.begin_command_buffer(
                    cmd,
                    &vk::CommandBufferBeginInfo::default()
                        .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT),
                )?;
                record(d, cmd);
                d.end_command_buffer(cmd)?;
            }
            let mut commands = std::mem::take(&mut frame.commands);
            // libretro: with command buffers, the core synchronizes by
            // barriers, and the session doesn't wait for its semaphores.
            let mut waits = std::mem::take(&mut frame.wait_semaphores);
            if !commands.is_empty() {
                waits.clear();
            }
            let mut stages = vec![vk::PipelineStageFlags::ALL_COMMANDS; waits.len()];
            waits.extend_from_slice(acquired);
            stages.resize(waits.len(), vk::PipelineStageFlags::TRANSFER);
            commands.push(cmd);
            let core_signal = std::mem::replace(&mut frame.signal_semaphore, vk::Semaphore::null());
            let signals: Vec<_> = drawn
                .iter()
                .copied()
                .chain((core_signal != vk::Semaphore::null()).then_some(core_signal))
                .collect();
            let info = vk::SubmitInfo::default()
                .wait_semaphores(&waits)
                .wait_dst_stage_mask(&stages)
                .command_buffers(&commands)
                .signal_semaphores(&signals);
            // SAFETY: the fence is unused since the wait above, and the queue
            // lock keeps others off the queue.
            unsafe {
                d.reset_fences(&[fence])?;
                d.queue_submit(self.queue, &[info], fence)?;
            }
            frame.pending[slot] = true;
            Ok(())
        })
    }

    /// Submits the core's pending work alone, when the session shows
    /// nothing, so that it still runs and its semaphores are consumed.
    fn flush(&mut self) {
        let frame = self.shared.frame();
        if frame.commands.is_empty()
            && frame.wait_semaphores.is_empty()
            && frame.signal_semaphore == vk::Semaphore::null()
        {
            return;
        }
        drop(frame);
        if let Err(e) = self.submit(&[], &[], |_, _| {}) {
            log::error!("can't submit the core's work: {e}");
        }
    }

    /// Draws the `width` by `height` region at the core image's origin to the
    /// window, scaled to fit at `aspect` (width over height) and centered on
    /// black, and presents it. With a lower window, the main window shows the
    /// region's top half and the lower window its bottom half, `lower`, both
    /// from the one image. Without a window, it only runs the core's pending
    /// work.
    pub(crate) fn present(&mut self, width: u32, height: u32, aspect: f32, lower: Region) {
        let image = self.shared.frame().image;
        let Some(image) = image.filter(|_| width > 0 && height > 0) else {
            self.flush();
            return;
        };
        let outputs: &[(Screen, Region)] = if self.windows[Screen::Lower as usize].is_some() {
            &[(Screen::Lower, lower), (Screen::Main, Region::Top)]
        } else {
            &[(Screen::Main, Region::Whole)]
        };
        // The slot's semaphores are free once its last submission is done.
        let slot = {
            let mut frame = self.shared.frame();
            let slot = frame.slot;
            self.shared.wait(&mut frame, slot);
            slot
        };
        let swapchain_fn = self.swapchain_fn.clone().expect("a windowed context");
        let mut draws = Vec::with_capacity(outputs.len());
        for &(output, region) in outputs {
            if !self.ensure_swapchain(output) {
                continue;
            }
            let acquired = self.frames[slot].acquired[output as usize];
            let window = self.windows[output as usize]
                .as_mut()
                .expect("ensured above");
            // SAFETY: the swapchain is live and the semaphore unsignaled.
            match unsafe {
                swapchain_fn.acquire_next_image(
                    window.swapchain,
                    u64::MAX,
                    acquired,
                    vk::Fence::null(),
                )
            } {
                // A suboptimal swapchain still presents correctly. Android
                // reports it whenever the display is rotated against the
                // swapchain, which would otherwise remake it every frame.
                Ok((index, _suboptimal)) => draws.push(Draw {
                    output,
                    index,
                    acquired,
                    target: window.images[index as usize],
                    drawn: window.drawn[index as usize],
                    swapchain: window.swapchain,
                    blit: Blit::new(width, height, aspect, region, window.extent),
                }),
                Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => window.stale = true,
                Err(e) => log::warn!("can't acquire a swapchain image: {e}"),
            }
        }
        if draws.is_empty() {
            self.flush();
            return;
        }
        let acquired: Vec<_> = draws.iter().map(|d| d.acquired).collect();
        let drawn: Vec<_> = draws.iter().map(|d| d.drawn).collect();
        let submitted = self.submit(&acquired, &drawn, |d, cmd| {
            for draw in &draws {
                // SAFETY: recording into the slot's command buffer.
                unsafe { record_blit(d, cmd, &image, draw.target, draw.blit) }
            }
        });
        if let Err(e) = submitted {
            log::error!("can't draw a frame: {e}");
            return;
        }
        // A repeated frame reads an image from another slot, whose fence the
        // core waits for before it reuses the image, so wait for it here.
        if image.slot != slot {
            self.shared.wait_sync_index();
        }
        for draw in draws {
            let waits = [draw.drawn];
            let swapchains = [draw.swapchain];
            let indices = [draw.index];
            let info = vk::PresentInfoKHR::default()
                .wait_semaphores(&waits)
                .swapchains(&swapchains)
                .image_indices(&indices);
            // SAFETY: the image was acquired and drawn; the queue lock keeps
            // others off the queue.
            let presented = self
                .shared
                .queue_lock
                .with(|| unsafe { swapchain_fn.queue_present(self.queue, &info) });
            match presented {
                Ok(_) => {}
                Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
                    if let Some(window) = &mut self.windows[draw.output as usize] {
                        window.stale = true;
                    }
                }
                Err(e) => log::warn!("can't present: {e}"),
            }
        }
    }

    /// Reads the `width` by `height` region at the core image's origin into
    /// `out` as tightly packed RGBA8, top row first. It returns false, and
    /// only runs the core's pending work, if there's no image or the session
    /// can't read its format.
    pub(crate) fn read(&mut self, width: u32, height: u32, out: &mut Vec<u8>) -> bool {
        let image = self.shared.frame().image;
        let Some(image) = image.filter(|_| width > 0 && height > 0) else {
            self.flush();
            return false;
        };
        let Some(order) = ChannelOrder::of(image.format) else {
            if !self.warned_format {
                self.warned_format = true;
                log::warn!("can't read back frames in {:?}", image.format);
            }
            self.flush();
            return false;
        };
        let size = u64::from(width) * u64::from(height) * 4;
        if let Err(e) = self.ensure_readback(size) {
            log::error!("{e}");
            self.flush();
            return false;
        }
        let readback = self.readback.as_ref().expect("ensured above");
        let (buffer, mapped) = (readback.buffer, readback.mapped);
        let submitted = self.submit(&[], &[], |d, cmd| {
            // SAFETY: recording into the slot's command buffer.
            unsafe { record_copy(d, cmd, &image, buffer, [width, height]) }
        });
        if let Err(e) = submitted {
            log::error!("can't read back a frame: {e}");
            return false;
        }
        self.shared.wait_sync_index();
        // SAFETY: the copy is done, and the mapping holds `size` bytes of
        // coherent memory.
        let pixels = unsafe { std::slice::from_raw_parts(mapped, size as usize) };
        to_rgba(order, pixels, out);
        true
    }

    fn ensure_readback(&mut self, size: u64) -> Result<(), VulkanError> {
        if self.readback.as_ref().is_some_and(|r| r.size >= size) {
            return Ok(());
        }
        self.wait_idle();
        self.destroy_readback();
        let d = &self.device;
        // SAFETY: valid create infos; the objects are destroyed with the
        // context or when replaced.
        unsafe {
            let buffer = d
                .create_buffer(
                    &vk::BufferCreateInfo::default()
                        .size(size)
                        .usage(vk::BufferUsageFlags::TRANSFER_DST),
                    None,
                )
                .map_err(failed("create a readback buffer"))?;
            let requirements = d.get_buffer_memory_requirements(buffer);
            let wanted =
                vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT;
            let Some(memory_type) = (0..self.memory.memory_type_count).find(|&i| {
                requirements.memory_type_bits & (1 << i) != 0
                    && self.memory.memory_types[i as usize]
                        .property_flags
                        .contains(wanted)
            }) else {
                d.destroy_buffer(buffer, None);
                return Err(VulkanError::Vulkan(
                    "no host-visible memory for reading back frames".into(),
                ));
            };
            let memory = d
                .allocate_memory(
                    &vk::MemoryAllocateInfo::default()
                        .allocation_size(requirements.size)
                        .memory_type_index(memory_type),
                    None,
                )
                .inspect_err(|_| d.destroy_buffer(buffer, None))
                .map_err(failed("allocate readback memory"))?;
            let mapped = d
                .bind_buffer_memory(buffer, memory, 0)
                .and_then(|()| d.map_memory(memory, 0, size, vk::MemoryMapFlags::empty()))
                .inspect_err(|_| {
                    d.destroy_buffer(buffer, None);
                    d.free_memory(memory, None);
                })
                .map_err(failed("map readback memory"))?;
            self.readback = Some(Readback {
                buffer,
                memory,
                size,
                mapped: mapped.cast(),
            });
        }
        Ok(())
    }

    fn destroy_readback(&mut self) {
        if let Some(r) = self.readback.take() {
            // SAFETY: the device is idle, so nothing uses them.
            unsafe {
                self.device.destroy_buffer(r.buffer, None);
                self.device.free_memory(r.memory, None);
            }
        }
    }

    /// Replaces `screen`'s window, or detaches it with `None`. The old
    /// window's surface is gone when this returns. On an error, the screen
    /// has no window.
    pub(crate) fn set_window(
        &mut self,
        screen: Screen,
        window: Option<SizedWindow>,
    ) -> Result<(), VulkanError> {
        self.destroy_window(screen);
        let Some((window, width, height)) = window else {
            return Ok(());
        };
        let surface = self.create_surface(window)?;
        let surface_fn = self.surface_fn.as_ref().expect("made with the surface");
        // SAFETY: a surface of this instance and a family of this GPU.
        let supported = unsafe {
            surface_fn.get_physical_device_surface_support(self.gpu, self.queue_family, surface)
        }
        .unwrap_or(false);
        self.windows[screen as usize] = Some(Window {
            surface,
            size: (width, height),
            swapchain: vk::SwapchainKHR::null(),
            images: Vec::new(),
            drawn: Vec::new(),
            extent: vk::Extent2D::default(),
            stale: true,
        });
        if !supported {
            self.destroy_window(screen);
            return Err(VulkanError::Vulkan(
                "the device's queue can't present to the window".into(),
            ));
        }
        self.create_swapchain(screen)
            .inspect_err(|_| self.destroy_window(screen))
    }

    fn create_surface(&self, window: NativeWindow) -> Result<vk::SurfaceKHR, VulkanError> {
        #[cfg(target_os = "android")]
        if let (raw_window_handle::RawWindowHandle::AndroidNdk(native), Some(android)) =
            (window.window, &self.android_surface_fn)
        {
            let info = vk::AndroidSurfaceCreateInfoKHR::default()
                .window(native.a_native_window.as_ptr().cast());
            // SAFETY: the caller of `NativeWindow::new` vouched for the
            // window.
            return unsafe { android.create_android_surface(&info, None) }
                .map_err(failed("create a surface"));
        }
        let _ = window;
        Err(VulkanError::Vulkan(
            "the session presents Vulkan frames only to Android windows".into(),
        ))
    }

    /// Tells the context the new size of `screen`'s window, for the next
    /// frame.
    pub(crate) fn resize(&mut self, screen: Screen, width: u32, height: u32) {
        if let Some(window) = &mut self.windows[screen as usize] {
            window.size = (width, height);
            window.stale = true;
        }
    }

    /// Makes `output`'s swapchain again if it's stale, and returns whether
    /// there's one to present to.
    fn ensure_swapchain(&mut self, output: Screen) -> bool {
        let Some(window) = &self.windows[output as usize] else {
            return false;
        };
        if window.stale
            && let Err(e) = self.create_swapchain(output)
        {
            log::warn!("{e}");
        }
        self.windows[output as usize].as_ref().is_some_and(|w| {
            w.swapchain != vk::SwapchainKHR::null()
                && !w.images.is_empty()
                && w.drawn.len() == w.images.len()
        })
    }

    /// Makes `output`'s swapchain for its window's current size, replacing
    /// any old one. A window with no area gets none.
    fn create_swapchain(&mut self, output: Screen) -> Result<(), VulkanError> {
        self.wait_idle();
        let (Some(surface_fn), Some(swapchain_fn), Some(window)) = (
            &self.surface_fn,
            &self.swapchain_fn,
            &mut self.windows[output as usize],
        ) else {
            return Ok(());
        };
        // Until the next resize, a failure leaves the window without a
        // swapchain rather than trying again every frame.
        window.stale = false;
        let old = std::mem::replace(&mut window.swapchain, vk::SwapchainKHR::null());
        let old_drawn = std::mem::take(&mut window.drawn);
        window.images.clear();
        let d = &self.device;
        // SAFETY: the device is idle, so the old swapchain and semaphores
        // are unused; it's retired by the new one before it's destroyed.
        unsafe {
            let created = (|| {
                let caps = surface_fn
                    .get_physical_device_surface_capabilities(self.gpu, window.surface)
                    .map_err(failed("query the surface"))?;
                let extent = if caps.current_extent.width == u32::MAX {
                    vk::Extent2D {
                        width: window
                            .size
                            .0
                            .clamp(caps.min_image_extent.width, caps.max_image_extent.width),
                        height: window
                            .size
                            .1
                            .clamp(caps.min_image_extent.height, caps.max_image_extent.height),
                    }
                } else {
                    caps.current_extent
                };
                if extent.width == 0 || extent.height == 0 {
                    return Ok(None);
                }
                if !caps
                    .supported_usage_flags
                    .contains(vk::ImageUsageFlags::TRANSFER_DST)
                {
                    return Err(VulkanError::Vulkan(
                        "the window's swapchain images can't be drawn to by copying".into(),
                    ));
                }
                let formats = surface_fn
                    .get_physical_device_surface_formats(self.gpu, window.surface)
                    .map_err(failed("query the surface's formats"))?;
                let format = formats
                    .iter()
                    .find(|f| {
                        [vk::Format::R8G8B8A8_UNORM, vk::Format::B8G8R8A8_UNORM].contains(&f.format)
                            && self
                                .instance
                                .get_physical_device_format_properties(self.gpu, f.format)
                                .optimal_tiling_features
                                .contains(vk::FormatFeatureFlags::BLIT_DST)
                    })
                    .copied()
                    .ok_or_else(|| {
                        VulkanError::Vulkan(format!(
                            "no swapchain format to draw to in {formats:?}"
                        ))
                    })?;
                let modes = surface_fn
                    .get_physical_device_surface_present_modes(self.gpu, window.surface)
                    .map_err(failed("query the surface's present modes"))?;
                // The session paces frames itself, so presenting needn't wait
                // for the display's refresh.
                let mode = [vk::PresentModeKHR::MAILBOX, vk::PresentModeKHR::IMMEDIATE]
                    .into_iter()
                    .find(|m| modes.contains(m))
                    .unwrap_or(vk::PresentModeKHR::FIFO);
                let mut count = caps.min_image_count + 1;
                if caps.max_image_count > 0 {
                    count = count.min(caps.max_image_count);
                }
                let transform = if caps
                    .supported_transforms
                    .contains(vk::SurfaceTransformFlagsKHR::IDENTITY)
                {
                    vk::SurfaceTransformFlagsKHR::IDENTITY
                } else {
                    caps.current_transform
                };
                let alpha = [
                    vk::CompositeAlphaFlagsKHR::OPAQUE,
                    vk::CompositeAlphaFlagsKHR::INHERIT,
                    vk::CompositeAlphaFlagsKHR::PRE_MULTIPLIED,
                    vk::CompositeAlphaFlagsKHR::POST_MULTIPLIED,
                ]
                .into_iter()
                .find(|&a| caps.supported_composite_alpha.contains(a))
                .unwrap_or(vk::CompositeAlphaFlagsKHR::OPAQUE);
                let info = vk::SwapchainCreateInfoKHR::default()
                    .surface(window.surface)
                    .min_image_count(count)
                    .image_format(format.format)
                    .image_color_space(format.color_space)
                    .image_extent(extent)
                    .image_array_layers(1)
                    .image_usage(vk::ImageUsageFlags::TRANSFER_DST)
                    .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
                    .pre_transform(transform)
                    .composite_alpha(alpha)
                    .present_mode(mode)
                    .clipped(true)
                    .old_swapchain(old);
                let swapchain = swapchain_fn
                    .create_swapchain(&info, None)
                    .map_err(failed("create a swapchain"))?;
                log::info!(
                    "Vulkan swapchain for the {output:?} window: {}x{} {:?}, {mode:?}, transform {transform:?} on a display at {:?}",
                    extent.width,
                    extent.height,
                    format.format,
                    caps.current_transform,
                );
                Ok(Some((swapchain, extent)))
            })();
            for semaphore in old_drawn {
                d.destroy_semaphore(semaphore, None);
            }
            swapchain_fn.destroy_swapchain(old, None);
            let Some((swapchain, extent)) = created? else {
                return Ok(());
            };
            window.swapchain = swapchain;
            window.extent = extent;
            window.images = swapchain_fn
                .get_swapchain_images(swapchain)
                .map_err(failed("get the swapchain's images"))?;
            for _ in 0..window.images.len() {
                let semaphore = d
                    .create_semaphore(&vk::SemaphoreCreateInfo::default(), None)
                    .map_err(failed("create a semaphore"))?;
                window.drawn.push(semaphore);
            }
        }
        Ok(())
    }

    fn destroy_window(&mut self, output: Screen) {
        let Some(window) = self.windows[output as usize].take() else {
            return;
        };
        self.wait_idle();
        // SAFETY: the device is idle, so nothing uses the window's objects.
        unsafe {
            for semaphore in window.drawn {
                self.device.destroy_semaphore(semaphore, None);
            }
            if let Some(swapchain_fn) = &self.swapchain_fn {
                swapchain_fn.destroy_swapchain(window.swapchain, None);
            }
            if let Some(surface_fn) = &self.surface_fn {
                surface_fn.destroy_surface(window.surface, None);
            }
        }
    }
}

impl Drop for VulkanContext {
    fn drop(&mut self) {
        self.destroy_window(Screen::Lower);
        self.destroy_window(Screen::Main);
        self.wait_idle();
        self.destroy_readback();
        // SAFETY: the device is idle and nothing uses these objects after
        // this. libretro has the core's `destroy_device` run before the
        // device and instance go.
        unsafe {
            for frame in &self.frames {
                for &semaphore in &frame.acquired {
                    self.device.destroy_semaphore(semaphore, None);
                }
                self.device.destroy_command_pool(frame.pool, None);
            }
            for &fence in &self.shared.frame().fences {
                self.device.destroy_fence(fence, None);
            }
            if let Some(destroy_device) = self.destroy_device {
                destroy_device();
            }
            self.device.destroy_device(None);
            self.instance.destroy_instance(None);
        }
    }
}

/// The GPU to render on and its queue family for graphics, preferring
/// discrete GPUs, then integrated ones, then any other, such as a software
/// renderer.
fn choose_gpu(instance: &ash::Instance) -> Result<(vk::PhysicalDevice, u32), VulkanError> {
    // SAFETY: a live instance.
    let gpus = unsafe { instance.enumerate_physical_devices() }
        .map_err(|e| VulkanError::Unavailable(format!("can't list the GPUs: {e}")))?;
    gpus.into_iter()
        .filter_map(|gpu| {
            // SAFETY: a GPU of this instance.
            let (properties, families) = unsafe {
                (
                    instance.get_physical_device_properties(gpu),
                    instance.get_physical_device_queue_family_properties(gpu),
                )
            };
            let find = |flags| families.iter().position(|f| f.queue_flags.contains(flags));
            let family = find(vk::QueueFlags::GRAPHICS | vk::QueueFlags::COMPUTE)
                .or_else(|| find(vk::QueueFlags::GRAPHICS))?;
            let rank = match properties.device_type {
                vk::PhysicalDeviceType::DISCRETE_GPU => 0,
                vk::PhysicalDeviceType::INTEGRATED_GPU => 1,
                _ => 2,
            };
            Some((rank, gpu, family as u32))
        })
        .min_by_key(|(rank, ..)| *rank)
        .map(|(_, gpu, family)| (gpu, family))
        .ok_or_else(|| VulkanError::Unavailable("no GPU can render".into()))
}

fn color_range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange {
        aspect_mask: vk::ImageAspectFlags::COLOR,
        base_mip_level: 0,
        level_count: 1,
        base_array_layer: 0,
        layer_count: 1,
    }
}

/// A barrier on `image` that also changes its layout.
fn barrier(
    image: vk::Image,
    range: vk::ImageSubresourceRange,
    (old, new): (vk::ImageLayout, vk::ImageLayout),
    (src, dst): (vk::AccessFlags, vk::AccessFlags),
) -> vk::ImageMemoryBarrier<'static> {
    vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(range)
        .old_layout(old)
        .new_layout(new)
        .src_access_mask(src)
        .dst_access_mask(dst)
        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
}

/// Records moving the core's image into a layout to copy from, after
/// whatever the core did to it, and `copy`, and moving it back.
///
/// # Safety
///
/// `cmd` is recording.
unsafe fn with_source(
    d: &ash::Device,
    cmd: vk::CommandBuffer,
    image: &CoreImage,
    before: &[vk::ImageMemoryBarrier<'_>],
    copy: impl FnOnce(vk::ImageLayout),
) {
    let src = image.source_layout();
    let mut barriers = vec![barrier(
        image.image,
        image.range(),
        (image.layout, src),
        (
            vk::AccessFlags::MEMORY_WRITE,
            vk::AccessFlags::TRANSFER_READ,
        ),
    )];
    barriers.extend_from_slice(before);
    // SAFETY: forwarded from the caller.
    unsafe {
        d.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::ALL_COMMANDS,
            vk::PipelineStageFlags::TRANSFER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &barriers,
        );
        copy(src);
        d.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::TRANSFER,
            vk::PipelineStageFlags::ALL_COMMANDS,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &[barrier(
                image.image,
                image.range(),
                (src, image.layout),
                (vk::AccessFlags::empty(), vk::AccessFlags::MEMORY_READ),
            )],
        );
    }
}

/// Records clearing `target`, a swapchain image, to black and scaling
/// `blit`'s region of the core's image onto it, leaving it ready to present.
///
/// # Safety
///
/// `cmd` is recording.
unsafe fn record_blit(
    d: &ash::Device,
    cmd: vk::CommandBuffer,
    image: &CoreImage,
    target: vk::Image,
    blit: Blit,
) {
    let (first, rows) = blit.rows;
    let (first_column, columns) = blit.columns;
    let (x, y, w, h) = blit.rect;
    let to_dst = barrier(
        target,
        color_range(),
        (
            vk::ImageLayout::UNDEFINED,
            vk::ImageLayout::TRANSFER_DST_OPTIMAL,
        ),
        (vk::AccessFlags::empty(), vk::AccessFlags::TRANSFER_WRITE),
    );
    // SAFETY: forwarded from the caller.
    unsafe {
        with_source(d, cmd, image, &[to_dst], |src| {
            d.cmd_clear_color_image(
                cmd,
                target,
                vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                &vk::ClearColorValue {
                    float32: [0.0, 0.0, 0.0, 1.0],
                },
                &[color_range()],
            );
            d.cmd_pipeline_barrier(
                cmd,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &[barrier(
                    target,
                    color_range(),
                    (
                        vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                        vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                    ),
                    (
                        vk::AccessFlags::TRANSFER_WRITE,
                        vk::AccessFlags::TRANSFER_WRITE,
                    ),
                )],
            );
            if w > 0 && h > 0 && rows > 0 {
                let region = vk::ImageBlit {
                    src_subresource: image.layers(),
                    src_offsets: [
                        vk::Offset3D {
                            x: first_column as i32,
                            y: first as i32,
                            z: 0,
                        },
                        vk::Offset3D {
                            x: (first_column + columns) as i32,
                            y: (first + rows) as i32,
                            z: 1,
                        },
                    ],
                    dst_subresource: vk::ImageSubresourceLayers {
                        aspect_mask: vk::ImageAspectFlags::COLOR,
                        mip_level: 0,
                        base_array_layer: 0,
                        layer_count: 1,
                    },
                    dst_offsets: [
                        vk::Offset3D { x, y, z: 0 },
                        vk::Offset3D {
                            x: x + w,
                            y: y + h,
                            z: 1,
                        },
                    ],
                };
                d.cmd_blit_image(
                    cmd,
                    image.image,
                    src,
                    target,
                    vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                    &[region],
                    vk::Filter::NEAREST,
                );
            }
        });
        d.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::TRANSFER,
            vk::PipelineStageFlags::BOTTOM_OF_PIPE,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &[barrier(
                target,
                color_range(),
                (
                    vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                    vk::ImageLayout::PRESENT_SRC_KHR,
                ),
                (vk::AccessFlags::TRANSFER_WRITE, vk::AccessFlags::empty()),
            )],
        );
    }
}

/// Records copying the `size` region of the core's image into `buffer`,
/// tightly packed, for the host to read.
///
/// # Safety
///
/// `cmd` is recording and `buffer` holds the region.
unsafe fn record_copy(
    d: &ash::Device,
    cmd: vk::CommandBuffer,
    image: &CoreImage,
    buffer: vk::Buffer,
    [width, height]: [u32; 2],
) {
    // SAFETY: forwarded from the caller.
    unsafe {
        with_source(d, cmd, image, &[], |src| {
            let region = vk::BufferImageCopy {
                buffer_offset: 0,
                buffer_row_length: 0,
                buffer_image_height: 0,
                image_subresource: image.layers(),
                image_offset: vk::Offset3D::default(),
                image_extent: vk::Extent3D {
                    width,
                    height,
                    depth: 1,
                },
            };
            d.cmd_copy_image_to_buffer(cmd, image.image, src, buffer, &[region]);
        });
        d.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::TRANSFER,
            vk::PipelineStageFlags::HOST,
            vk::DependencyFlags::empty(),
            &[vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)],
            &[],
            &[],
        );
    }
}

/// The order of the channels in a 32-bit format the session reads back.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ChannelOrder {
    Rgba,
    Bgra,
}

impl ChannelOrder {
    fn of(format: vk::Format) -> Option<Self> {
        match format {
            vk::Format::R8G8B8A8_UNORM
            | vk::Format::R8G8B8A8_SRGB
            | vk::Format::A8B8G8R8_UNORM_PACK32
            | vk::Format::A8B8G8R8_SRGB_PACK32 => Some(Self::Rgba),
            vk::Format::B8G8R8A8_UNORM | vk::Format::B8G8R8A8_SRGB => Some(Self::Bgra),
            _ => None,
        }
    }
}

/// Converts read-back pixels into RGBA8 with A always 255.
fn to_rgba(order: ChannelOrder, pixels: &[u8], out: &mut Vec<u8>) {
    out.clear();
    out.extend(
        pixels
            .as_chunks::<4>()
            .0
            .iter()
            .flat_map(|&[a, b, c, _]| match order {
                ChannelOrder::Rgba => [a, b, c, 255],
                ChannelOrder::Bgra => [c, b, a, 255],
            }),
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_read_back_pixels_to_opaque_rgba() {
        let pixels = [1, 2, 3, 0, 4, 5, 6, 128];
        let mut out = vec![9; 3];
        to_rgba(ChannelOrder::Rgba, &pixels, &mut out);
        assert_eq!(out, [1, 2, 3, 255, 4, 5, 6, 255]);
        to_rgba(ChannelOrder::Bgra, &pixels, &mut out);
        assert_eq!(out, [3, 2, 1, 255, 6, 5, 4, 255]);
    }
}
