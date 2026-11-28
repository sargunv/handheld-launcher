//! What draws a session's frames: an OpenGL context, or a Vulkan device for
//! cores that render with Vulkan.

use crate::ffi;
use crate::gl::{self, Framebuffer, GlApi, GlContext, SizedWindow, Target};
use crate::host::hw_api;
use crate::session::{AvInfo, SessionError};
use crate::video::{self, Frame, Region, Screen};
use crate::vulkan::{Negotiation, VulkanContext};

#[expect(
    clippy::large_enum_variant,
    reason = "a session has one, in its boxed host"
)]
pub(crate) enum Renderer {
    Gl(Gpu),
    Vulkan(VulkanContext),
}

/// The GL context and the framebuffers the session draws with.
pub(crate) struct Gpu {
    context: GlContext,
    /// The framebuffer a hardware-rendered core draws into.
    pub(crate) hw: Option<Framebuffer>,
    /// Whether the core draws into `hw` with its first row at the bottom.
    bottom_left_origin: bool,
    /// A texture for presenting software frames to a window.
    upload: Option<Framebuffer>,
}

impl Gpu {
    pub(crate) fn new(
        context: GlContext,
        hw: Option<Framebuffer>,
        bottom_left_origin: bool,
    ) -> Self {
        Self {
            context,
            hw,
            bottom_left_origin,
            upload: None,
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

impl Renderer {
    /// The renderer for a core's hardware render request `hw` and the core's
    /// `av` info, drawing to `target`: Vulkan for a Vulkan core, and GL for
    /// other hardware-rendered cores and for windows. A software core
    /// drawing to no window needs none.
    pub(crate) fn new(
        hw: Option<&ffi::retro_hw_render_callback>,
        negotiation: Option<&Negotiation>,
        av: AvInfo,
        target: Target,
    ) -> Result<Option<Self>, SessionError> {
        if hw.is_some_and(|cb| cb.context_type == ffi::RETRO_HW_CONTEXT_VULKAN) {
            return Ok(Some(Self::Vulkan(VulkanContext::new(negotiation, target)?)));
        }
        let context = match (hw, target) {
            (Some(cb), target) => {
                let api = hw_api(cb).expect("accepted in SET_HW_RENDER");
                GlContext::new(api, target, cb.debug_context)?
            }
            (None, target @ Target::Window(_)) => {
                GlContext::new(GlApi::Gles { major: 3, minor: 0 }, target, false)?
            }
            (None, Target::Headless) => return Ok(None),
        };
        let fb = hw
            .map(|cb| {
                Framebuffer::new(
                    &context.gl,
                    av.max_width.max(av.base_width),
                    av.max_height.max(av.base_height),
                    cb.depth,
                    cb.stencil,
                )
            })
            .transpose()?;
        let bottom_left_origin = hw.is_some_and(|cb| cb.bottom_left_origin);
        Ok(Some(Self::Gl(Gpu::new(context, fb, bottom_left_origin))))
    }

    /// Replaces `screen`'s window, or detaches it with `None`. On an error,
    /// the screen has no window.
    pub(crate) fn set_window(
        &mut self,
        screen: Screen,
        window: Option<SizedWindow>,
    ) -> Result<(), SessionError> {
        match self {
            Self::Gl(gpu) => gpu.context.set_window(screen, window)?,
            Self::Vulkan(vulkan) => vulkan.set_window(screen, window)?,
        }
        Ok(())
    }

    /// Tells the renderer the new size of `screen`'s window.
    pub(crate) fn resize(&mut self, screen: Screen, width: u32, height: u32) {
        match self {
            Self::Gl(gpu) => gpu.context.resize(screen, width, height),
            Self::Vulkan(vulkan) => vulkan.resize(screen, width, height),
        }
    }

    /// Grows the hardware framebuffer to hold frames of at least `width` by
    /// `height`.
    pub(crate) fn grow(&mut self, width: u32, height: u32) {
        if let Self::Gl(gpu) = self
            && let Some(fb) = &mut gpu.hw
            && (width > fb.width || height > fb.height)
        {
            fb.resize(&gpu.context.gl, width.max(fb.width), height.max(fb.height));
        }
    }

    /// Gets ready for the core to run a frame.
    pub(crate) fn begin_frame(&mut self) {
        if let Self::Vulkan(vulkan) = self {
            vulkan.begin_frame();
        }
    }

    /// Gets ready for the core to destroy its context, and returns whether
    /// it may: the GL context is current, or the Vulkan device no longer uses
    /// what the core frees.
    pub(crate) fn prepare_context_destroy(&mut self) -> bool {
        match self {
            Self::Gl(gpu) => gpu.context.make_current().is_ok(),
            Self::Vulkan(vulkan) => {
                vulkan.release_image();
                true
            }
        }
    }

    /// Reads the core's last hardware frame, `width` by `height` at most, into
    /// `out` as tightly packed RGBA8, top row first, and returns its size.
    pub(crate) fn read_hw_frame(
        &mut self,
        width: u32,
        height: u32,
        out: &mut Vec<u8>,
    ) -> Option<(u32, u32)> {
        match self {
            Self::Vulkan(vulkan) => vulkan.read(width, height, out).then_some((width, height)),
            Self::Gl(gpu) => {
                let fb = gpu.hw.as_ref()?;
                let (width, height) = (width.min(fb.width), height.min(fb.height));
                if width == 0 || height == 0 {
                    return None;
                }
                fb.read(&gpu.context.gl, width, height, out);
                gl::drain_errors(&gpu.context.gl, "reading back a frame");
                if gpu.bottom_left_origin {
                    video::flip_rows(out, width as usize, height as usize);
                }
                Some((width, height))
            }
        }
    }

    /// Presents the core's last hardware frame, `width` by `height`, at
    /// `aspect`, with `lower` of it on the lower window.
    pub(crate) fn present_hw_frame(&mut self, width: u32, height: u32, aspect: f32, lower: Region) {
        match self {
            Self::Vulkan(vulkan) => vulkan.present(width, height, aspect, lower),
            Self::Gl(gpu) => {
                let Some(fb) = &gpu.hw else { return };
                let (width, height) = (width.min(fb.width), height.min(fb.height));
                if width > 0 && height > 0 {
                    let top_row_first = !gpu.bottom_left_origin;
                    gl::present(
                        &gpu.context,
                        fb,
                        width,
                        height,
                        aspect,
                        top_row_first,
                        lower,
                    );
                }
            }
        }
    }

    /// Presents a software frame at `aspect`, with `lower` of it on the lower
    /// window.
    pub(crate) fn present_sw_frame(&mut self, frame: Frame<'_>, aspect: f32, lower: Region) {
        let Self::Gl(gpu) = self else { return };
        let gl = &gpu.context.gl;
        if gpu.upload.is_none() {
            match Framebuffer::new(gl, frame.width, frame.height, false, false) {
                Ok(fb) => gpu.upload = Some(fb),
                Err(e) => {
                    log::error!("can't create the upload texture: {e}");
                    return;
                }
            }
        }
        let upload = gpu.upload.as_mut().expect("created above");
        upload.upload(gl, frame.width, frame.height, frame.pixels);
        gl::present(
            &gpu.context,
            upload,
            frame.width,
            frame.height,
            aspect,
            true,
            lower,
        );
    }
}
