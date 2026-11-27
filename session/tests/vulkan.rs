//! Hardware rendering against libretro-samples' Vulkan test core, headless,
//! with frames read back from the GPU.
//!
//! The core clears to orange and draws a triangle with red, green and blue
//! corners over the top-left half of the middle, spinning slowly. The test
//! skips when the machine has no usable Vulkan, unless
//! `HANDHELD_REQUIRE_VULKAN` is set, and it's ignored on macOS, which has no
//! Vulkan without MoltenVK.

mod common;

use common::near;
use handheld_session::{SessionError, VulkanError};

/// The core's clear color, (0.8, 0.6, 0.2).
const BACKGROUND: [u8; 4] = [204, 153, 51, 255];

#[test]
#[cfg_attr(target_os = "macos", ignore = "macOS has no Vulkan")]
fn renders_with_vulkan() {
    let render = |frames| {
        common::render(
            "testvulkan_libretro.so",
            frames,
            "HANDHELD_REQUIRE_VULKAN",
            |e| matches!(e, SessionError::Vulkan(VulkanError::Unavailable(_))),
        )
    };
    let Some(first) = render(1) else { return };
    common::dump(&first, "testvulkan");
    assert_eq!((first.width, first.height), (320, 240));
    // The background in the corners and the bottom-right half of the middle,
    // and the triangle in the top-left half, which also catches a frame
    // upside down.
    for (x, y) in [
        (0, 0),
        (319, 0),
        (0, 239),
        (319, 239),
        (200, 150),
        (112, 168),
    ] {
        assert!(
            near(first.pixel(x, y), BACKGROUND),
            "background at ({x}, {y}) is {:?}",
            first.pixel(x, y)
        );
    }
    assert!(
        !near(first.pixel(112, 72), BACKGROUND),
        "no triangle at (112, 72)"
    );
    // The triangle spins, so later frames differ.
    let later = render(30).unwrap();
    assert_ne!(first, later, "the triangle didn't move");
}
