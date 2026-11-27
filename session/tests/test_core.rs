//! The session against libretro-samples' software test core, which draws a
//! red and green checkerboard with a blue cursor in the middle, and plays a
//! 300 Hz tone at 30 kHz. The D-pad scrolls the checkerboard a pixel a frame
//! for each of the two ports the core reads, even when only port 0 presses
//! it.

mod common;

use common::{Audio, Harness, lock, zero_crossings};
use handheld_session::{Button, SaveRam, SessionError};

const CORE: &str = "test_libretro.so";
const RED: [u8; 4] = [255, 0, 0, 255];
const GREEN: [u8; 4] = [0, 255, 0, 255];
const BLUE: [u8; 4] = [0, 0, 255, 255];

/// The test core's checkerboard color at (`x`, `y`) when scrolled by
/// (`dx`, `dy`), ignoring the cursor.
fn checker(x: u32, y: u32, dx: u32, dy: u32) -> [u8; 4] {
    let cell_x = (x.wrapping_sub(dx) >> 4) & 1;
    let cell_y = (y.wrapping_sub(dy) >> 4) & 1;
    if cell_x ^ cell_y == 1 { RED } else { GREEN }
}

#[test]
fn draws_the_checkerboard() {
    let _lock = lock();
    let mut h = Harness::start(CORE, None, &[], None).unwrap();
    h.run(1);
    let frame = h.frames.last();
    common::dump(&frame, "test-core");
    assert_eq!((frame.width, frame.height), (320, 240));
    for (x, y) in [(0, 0), (16, 0), (0, 16), (100, 200), (319, 239)] {
        assert_eq!(frame.pixel(x, y), checker(x, y, 0, 0), "pixel ({x}, {y})");
    }
    // The left stick places the cursor, centered at rest.
    assert_eq!(frame.pixel(160, 120), BLUE);
    assert_eq!(h.frames.count(), 1);
}

#[test]
fn creates_the_save_directory_for_the_core() {
    let _lock = lock();
    let h = Harness::start(CORE, None, &[], None).unwrap();
    assert!(h.dirs.path().join("saves").is_dir());
}

#[test]
fn the_dpad_scrolls_the_checkerboard() {
    let _lock = lock();
    let mut h = Harness::start(CORE, None, &[], None).unwrap();
    h.input.port(0).set_button(Button::Right, true);
    h.input.port(0).set_button(Button::Down, true);
    h.run(3);
    let frame = h.frames.last();
    for (x, y) in [(0, 0), (5, 5), (5, 6), (6, 6), (21, 0), (22, 0), (100, 100)] {
        assert_eq!(frame.pixel(x, y), checker(x, y, 6, 6), "pixel ({x}, {y})");
    }
    assert_ne!(checker(5, 6, 6, 6), checker(5, 6, 0, 0));

    h.input.port(0).set_buttons(0);
    h.run(2);
    assert_eq!(h.frames.last().pixel(5, 30), checker(5, 30, 6, 6));
}

#[test]
fn audio_arrives_resampled_to_the_sink_rate() {
    let _lock = lock();
    let mut h = Harness::start(CORE, None, &[], None).unwrap();
    assert_eq!(h.session.av_info().sample_rate, 30_000.0);
    h.run(120);
    let frames = h.audio.frames();
    let expected = 2 * Audio::RATE as usize;
    assert!(
        frames.abs_diff(expected) < 1_000,
        "{frames} frames for two seconds"
    );
    // A 300 Hz tone crosses zero 600 times a second.
    let crossings = zero_crossings(&h.audio.samples()) as f64 / (frames as f64 / 48_000.0);
    assert!(
        (590.0..610.0).contains(&crossings),
        "{crossings} crossings a second"
    );
}

#[test]
fn muted_audio_is_dropped() {
    let _lock = lock();
    let mut h = Harness::start(CORE, None, &[], None).unwrap();
    h.session.set_audio_muted(true);
    h.run(60);
    assert_eq!(h.audio.frames(), 0);
    assert_eq!(h.frames.count(), 60, "frames still arrive");
    h.session.set_audio_muted(false);
    h.run(60);
    let frames = h.audio.frames();
    let expected = Audio::RATE as usize;
    assert!(
        frames.abs_diff(expected) < 1_000,
        "{frames} frames for a second"
    );
}

#[test]
fn states_round_trip() {
    let _lock = lock();
    let mut h = Harness::start(CORE, None, &[], None).unwrap();
    h.input.port(0).set_button(Button::Right, true);
    h.run(5);
    let state = h.session.serialize_state().unwrap();
    let saved = h.frames.last();
    h.run(5);
    assert_ne!(h.frames.last(), saved);

    h.input.port(0).set_buttons(0);
    h.session.unserialize_state(&state).unwrap();
    h.run(1);
    assert_eq!(h.frames.last(), saved);

    let path = h.dirs.path().join("states/slot1.state");
    h.session.save_state(&path).unwrap();
    h.input.port(0).set_button(Button::Left, true);
    h.run(4);
    h.input.port(0).set_buttons(0);
    h.session.load_state(&path).unwrap();
    h.run(1);
    assert_eq!(h.frames.last(), saved);
}

#[test]
fn options_take_overrides() {
    let _lock = lock();
    let h = Harness::start(CORE, None, &[("test_samplerate", "20000")], None).unwrap();
    assert_eq!(h.session.av_info().sample_rate, 20_000.0);
}

#[test]
fn one_session_at_a_time() {
    let _lock = lock();
    let _first = Harness::start(CORE, None, &[], None).unwrap();
    assert!(matches!(
        Harness::start(CORE, None, &[], None),
        Err(SessionError::AlreadyActive)
    ));
}

#[test]
fn has_no_save_ram() {
    let _lock = lock();
    let mut h = Harness::start(CORE, None, &[], None).unwrap();
    assert!(h.session.save_ram().is_none());
    let path = h.dirs.path().join("test.srm");
    assert!(SaveRam::load(&path, &mut h.session).unwrap().is_none());
}
