//! The session against Gambatte running µCity, a Game Boy Color game with
//! battery-backed save memory.

mod common;

use common::{Harness, lock};
use std::io::Write;

use handheld_session::{Button, SaveRam, SessionError};
use zip::write::SimpleFileOptions;

const CORE: &str = "gambatte_libretro.so";
const GAME: &str = "ucity.gbc";
/// The cartridge's 128 KiB of battery-backed RAM.
const SAVE_RAM_SIZE: usize = 128 * 1024;

#[test]
fn plays_the_title_screen_with_sound() {
    let _lock = lock();
    let mut h = Harness::start(CORE, Some(GAME)).unwrap();
    h.run(600);
    let frame = h.frames.last();
    frame.dump("ucity-title");
    assert_eq!((frame.width, frame.height), (160, 144));
    assert_eq!(
        frame.sha256(),
        "c669d3a0dfff3049be5b7e6d41dd5717d0603ba2ad5e792e6c99bc80ef85394a",
        "the title screen changed; see it with HANDHELD_SESSION_DUMP_DIR"
    );
    let peak = h.audio.samples().iter().fold(0f32, |a, s| a.max(s.abs()));
    assert!(peak > 0.05, "title music peaks at {peak}");
}

#[test]
fn plays_the_game_from_a_zip() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("ucity.zip");
    let mut zip = zip::ZipWriter::new(std::fs::File::create(&archive).unwrap());
    zip.start_file("readme.txt", SimpleFileOptions::default())
        .unwrap();
    zip.write_all(b"not the game").unwrap();
    zip.start_file("µCity/ucity.gbc", SimpleFileOptions::default())
        .unwrap();
    zip.write_all(&std::fs::read(common::fixture(GAME)).unwrap())
        .unwrap();
    zip.finish().unwrap();

    let mut h = Harness::start(CORE, Some(archive.to_str().unwrap())).unwrap();
    let extracted = h.dirs.path().join("content/ucity.gbc");
    assert!(extracted.exists());
    h.run(600);
    assert_eq!(
        h.frames.last().sha256(),
        "c669d3a0dfff3049be5b7e6d41dd5717d0603ba2ad5e792e6c99bc80ef85394a",
        "the zipped game shows the same title screen"
    );
}

#[test]
fn needs_content() {
    let _lock = lock();
    assert!(matches!(
        Harness::start(CORE, None),
        Err(SessionError::ContentRequired)
    ));
}

#[test]
fn save_ram_round_trips_through_its_file() {
    let _lock = lock();
    let pattern: Vec<u8> = (0..SAVE_RAM_SIZE).map(|i| (i * 7 % 251) as u8).collect();
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("ucity.srm");
    std::fs::write(&path, &pattern).unwrap();

    let mut h = Harness::start(CORE, Some(GAME)).unwrap();
    let blank = h.session.save_ram().unwrap().to_vec();
    // A state of the game with blank save memory, which a state includes.
    let state = h.session.serialize_state().unwrap();
    let mut save_ram = SaveRam::load(&path, &mut h.session).unwrap().unwrap();
    assert_eq!(h.session.save_ram().unwrap(), pattern);

    h.session.unserialize_state(&state).unwrap();
    save_ram.flush(&h.session).unwrap();
    assert_eq!(std::fs::read(&path).unwrap(), blank);
}

#[test]
fn restoring_a_state_replays_identically() {
    let _lock = lock();
    let mut h = Harness::start(CORE, Some(GAME)).unwrap();
    h.run(200);
    let state = h.session.serialize_state().unwrap();

    let play = |h: &mut Harness| {
        h.input.port(0).set_button(Button::Start, true);
        h.run(10);
        h.input.port(0).set_buttons(0);
        h.run(90);
        h.frames.last()
    };
    let first = play(&mut h);
    first.dump("ucity-after-start");
    h.session.unserialize_state(&state).unwrap();
    let second = play(&mut h);
    assert_eq!(first, second);
}
