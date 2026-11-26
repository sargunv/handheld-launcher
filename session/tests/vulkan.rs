//! Hardware rendering against libretro-samples' Vulkan test core, headless,
//! with frames read back from the GPU.
//!
//! The core clears to orange and draws a triangle with red, green and blue
//! corners over the top-left half of the middle, spinning slowly. The test
//! skips when the machine has no usable Vulkan, unless
//! `HANDHELD_REQUIRE_VULKAN` is set, and it's ignored on macOS, which has no
//! Vulkan without MoltenVK.

mod common;

use common::{Harness, OwnedFrame, lock};
use handheld_session::{SessionError, VulkanError};

/// Runs the core for `frames` frames and returns the last, or `None` if
/// Vulkan isn't available here.
fn render(frames: usize) -> Option<OwnedFrame> {
    let _lock = lock();
    let mut h = match Harness::start("testvulkan_libretro.so", None) {
        Ok(h) => h,
        Err(SessionError::Vulkan(VulkanError::Unavailable(reason)))
            if std::env::var_os("HANDHELD_REQUIRE_VULKAN").is_none() =>
        {
            eprintln!("skipping: {reason}");
            return None;
        }
        Err(e) => panic!("{e}: {:?}", std::error::Error::source(&e)),
    };
    h.run(frames);
    assert_eq!(h.frames.count(), frames);
    Some(h.frames.last())
}

/// The core's clear color, (0.8, 0.6, 0.2).
const BACKGROUND: [u8; 4] = [204, 153, 51, 255];

fn near(a: [u8; 4], b: [u8; 4]) -> bool {
    a.iter().zip(b).all(|(&a, b)| a.abs_diff(b) <= 1)
}

#[test]
#[cfg_attr(target_os = "macos", ignore = "macOS has no Vulkan")]
fn renders_with_vulkan() {
    let Some(first) = render(1) else { return };
    first.dump("testvulkan");
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
