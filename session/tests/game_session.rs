//! [`GameSession`], the session on its own thread, keeping Gambatte's save
//! memory in its file while µCity runs.

mod common;

use std::collections::HashMap;
use std::path::Path;
use std::time::{Duration, Instant};

use common::{fixture, lock};
use handheld_session::{GameSession, SessionOptions, VideoMode};

/// The cartridge's 128 KiB of battery-backed RAM.
const SAVE_RAM_SIZE: usize = 128 * 1024;

fn start(dir: &Path) -> std::sync::Arc<GameSession> {
    GameSession::start(
        SessionOptions {
            core_path: fixture("gambatte_libretro.so")
                .to_string_lossy()
                .into_owned(),
            content_path: Some(fixture("ucity.gbc").to_string_lossy().into_owned()),
            content_cache_dir: None,
            system_dir: dir.join("system").to_string_lossy().into_owned(),
            save_dir: dir.join("saves").to_string_lossy().into_owned(),
            save_ram_path: Some(dir.join("saves/game.srm").to_string_lossy().into_owned()),
            core_options: HashMap::new(),
            video: VideoMode::Offscreen,
        },
        None,
    )
    .unwrap()
}

fn pattern(len: usize) -> Vec<u8> {
    (0..len).map(|i| (i * 7 % 251) as u8).collect()
}

#[test]
fn writes_save_memory_soon_after_it_changes_while_running() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let save = dir.path().join("saves/game.srm");
    // A state of the game with blank save memory, which a state includes.
    let blank = dir.path().join("blank.state");
    {
        let session = start(dir.path());
        session
            .save_state(blank.to_string_lossy().into_owned())
            .unwrap();
        session.stop().unwrap();
    }
    assert!(!save.exists(), "unchanged save memory isn't written");

    let pattern = pattern(SAVE_RAM_SIZE);
    std::fs::write(&save, &pattern).unwrap();
    let session = start(dir.path());
    session.resume();
    session
        .load_state(blank.to_string_lossy().into_owned())
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    while std::fs::read(&save).unwrap() == pattern {
        assert!(
            Instant::now() < deadline,
            "save memory wasn't written while running"
        );
        std::thread::sleep(Duration::from_millis(50));
    }
    assert_eq!(std::fs::read(&save).unwrap().len(), SAVE_RAM_SIZE);
    session.stop().unwrap();
}

#[test]
fn keeps_a_save_file_of_another_size_before_replacing_it() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let saves = dir.path().join("saves");
    std::fs::create_dir_all(&saves).unwrap();
    let short = pattern(1000);
    std::fs::write(saves.join("game.srm"), &short).unwrap();

    start(dir.path()).stop().unwrap();

    let written = std::fs::read(saves.join("game.srm")).unwrap();
    assert_eq!(written.len(), SAVE_RAM_SIZE);
    assert_eq!(written[..short.len()], short);
    let backups: Vec<_> = std::fs::read_dir(&saves)
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|e| e == "bak"))
        .collect();
    assert_eq!(backups.len(), 1, "{backups:?}");
    let name = backups[0].file_name().unwrap().to_str().unwrap();
    assert!(name.starts_with("game.srm."), "{name}");
    assert_eq!(std::fs::read(&backups[0]).unwrap(), short);
}
