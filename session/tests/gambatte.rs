//! The session against Gambatte running µCity, a Game Boy Color game with
//! battery-backed save memory.

mod common;

use common::{Harness, lock};
use std::io::Write;

use handheld_session::{Button, PixelFormat, SaveRamLoad, SessionError};
use zip::write::SimpleFileOptions;

const CORE: &str = "gambatte_libretro.so";
const GAME: &str = "ucity.gbc";
/// The cartridge's 128 KiB of battery-backed RAM.
const SAVE_RAM_SIZE: usize = 128 * 1024;

#[test]
fn plays_the_title_screen_with_sound() {
    let _lock = lock();
    let mut h = Harness::start(CORE, Some(GAME)).unwrap();
    assert_eq!(h.session.system_info().library_name, "Gambatte");
    assert!(
        h.session
            .system_info()
            .valid_extensions
            .iter()
            .any(|e| e == "gbc")
    );
    assert_eq!(h.session.pixel_format(), PixelFormat::Rgb565);
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
fn save_ram_round_trips_through_the_core() {
    let _lock = lock();
    let pattern: Vec<u8> = (0..SAVE_RAM_SIZE).map(|i| (i * 7 % 251) as u8).collect();
    let dir = tempfile::tempdir().unwrap();
    let original = dir.path().join("original.srm");
    std::fs::write(&original, &pattern).unwrap();
    let flushed = dir.path().join("saves/ucity.srm");

    {
        let mut h = Harness::start(CORE, Some(GAME)).unwrap();
        assert_eq!(h.session.save_ram().map(<[u8]>::len), Some(SAVE_RAM_SIZE));
        assert_eq!(
            h.session.load_save_ram(&original).unwrap(),
            SaveRamLoad::Loaded
        );
        assert_eq!(h.session.save_ram().unwrap(), pattern);
        assert!(h.session.flush_save_ram(&flushed).unwrap());
        assert_eq!(
            h.session
                .load_save_ram(&dir.path().join("missing.srm"))
                .unwrap(),
            SaveRamLoad::Nothing
        );
    }
    assert_eq!(std::fs::read(&flushed).unwrap(), pattern);

    // A new session picks the flushed save up.
    let mut h = Harness::start(CORE, Some(GAME)).unwrap();
    assert_ne!(h.session.save_ram().unwrap(), pattern);
    assert_eq!(
        h.session.load_save_ram(&flushed).unwrap(),
        SaveRamLoad::Loaded
    );
    assert_eq!(h.session.save_ram().unwrap(), pattern);
}

#[test]
fn a_save_file_of_another_size_is_backed_up_as_it_loads() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("ucity.srm");
    let short: Vec<u8> = (0..1000).map(|i| (i % 251) as u8).collect();
    std::fs::write(&path, &short).unwrap();

    let mut h = Harness::start(CORE, Some(GAME)).unwrap();
    let SaveRamLoad::Mismatched { backup } = h.session.load_save_ram(&path).unwrap() else {
        panic!("the sizes differ");
    };
    assert_eq!(backup.parent(), Some(dir.path()));
    assert_eq!(std::fs::read(&backup).unwrap(), short);
    assert_eq!(&h.session.save_ram().unwrap()[..short.len()], short);

    // Writing save memory back leaves the original in the backup.
    assert!(h.session.flush_save_ram(&path).unwrap());
    assert_eq!(std::fs::read(&path).unwrap().len(), SAVE_RAM_SIZE);
    assert_eq!(std::fs::read(&backup).unwrap(), short);
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
