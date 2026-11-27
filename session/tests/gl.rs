//! Hardware rendering against libretro-samples' GL test core, through a
//! headless EGL context.
//!
//! The core clears to a slate blue and draws two spinning quads, the second
//! smaller one offset up and to the right. Each test skips when the machine
//! has no usable EGL, unless `HANDHELD_REQUIRE_EGL` is set, and they're ignored
//! on macOS, which has no EGL.

mod common;

use common::{OwnedFrame, near};
use handheld_session::{GlError, SessionError};

const BACKGROUND: [u8; 4] = [77, 102, 128, 255];

/// Checks the first frame: the background in the corners, the large quad in
/// the middle, and the small one in the top right but not the bottom right,
/// which also catches a frame upside down.
fn check_first_frame(name: &str, frame: &OwnedFrame) {
    frame.dump(name);
    assert_eq!((frame.width, frame.height), (320, 240));
    for (x, y) in [(0, 0), (319, 0), (0, 239), (319, 239), (250, 180)] {
        assert!(
            near(frame.pixel(x, y), BACKGROUND),
            "{name}: background at ({x}, {y}) is {:?}",
            frame.pixel(x, y)
        );
    }
    for (x, y) in [(160, 120), (250, 60)] {
        assert!(
            !near(frame.pixel(x, y), BACKGROUND),
            "{name}: no quad at ({x}, {y})"
        );
    }
}

fn check(core: &str, name: &str) {
    let render = |frames| {
        common::render(core, frames, "HANDHELD_REQUIRE_EGL", |e| {
            matches!(e, SessionError::Gl(GlError::Unavailable(_)))
        })
    };
    let Some(first) = render(1) else { return };
    check_first_frame(name, &first);
    // The quads spin, so later frames differ.
    let later = render(30).unwrap();
    assert_ne!(first, later, "{name}: the quads didn't move");
}

#[test]
#[cfg_attr(target_os = "macos", ignore = "macOS has no EGL")]
fn renders_with_opengl() {
    check("testgl_gl_libretro.so", "testgl-gl");
}

#[test]
#[cfg_attr(target_os = "macos", ignore = "macOS has no EGL")]
fn renders_with_opengl_es() {
    check("testgl_gles_libretro.so", "testgl-gles");
}

#[test]
#[cfg_attr(target_os = "macos", ignore = "macOS has no EGL")]
fn renders_with_an_opengl_core_profile() {
    check("testgl_core_libretro.so", "testgl-core");
}
