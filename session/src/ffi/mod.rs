//! The libretro API, as bindgen generates it from `vendor/libretro/libretro.h`.
//! `mise run session:bindings` regenerates `generated.rs`.

#![allow(
    dead_code,
    non_camel_case_types,
    non_snake_case,
    non_upper_case_globals,
    unreachable_pub,
    clippy::all
)]

mod generated;

pub(crate) use generated::*;

/// The `data` a hardware-rendered core passes to the video callback when it
/// drew the frame into the frontend's framebuffer.
pub(crate) const RETRO_HW_FRAME_BUFFER_VALID: *const core::ffi::c_void =
    usize::MAX as *const core::ffi::c_void;
