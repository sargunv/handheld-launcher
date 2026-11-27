//! [`GameSession`], the session on its own thread, running µCity in Gambatte
//! and keeping its save memory in its file.

mod common;

use std::path::Path;
use std::time::{Duration, Instant};

use common::{lock, options};
use handheld_session::{GameSession, SessionError, SessionOptions};

/// The cartridge's 128 KiB of battery-backed RAM.
const SAVE_RAM_SIZE: usize = 128 * 1024;

fn start(dir: &Path) -> Result<std::sync::Arc<GameSession>, SessionError> {
    GameSession::start(
        SessionOptions {
            save_ram_path: Some(dir.join("saves/game.srm").to_string_lossy().into_owned()),
            ..options(dir, "gambatte_libretro.so", Some("ucity.gbc"))
        },
        None,
        None,
    )
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
        let session = start(dir.path()).unwrap();
        session
            .save_state(blank.to_string_lossy().into_owned())
            .unwrap();
        session.stop().unwrap();
    }
    assert!(!save.exists(), "unchanged save memory isn't written");

    let pattern = pattern(SAVE_RAM_SIZE);
    std::fs::write(&save, &pattern).unwrap();
    let session = start(dir.path()).unwrap();
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

    start(dir.path()).unwrap().stop().unwrap();

    let written = std::fs::read(saves.join("game.srm")).unwrap();
    assert_eq!(written.len(), SAVE_RAM_SIZE);
    assert_eq!(written[..short.len()], short);
    assert_eq!(backups(&saves), [short]);
}

#[test]
fn keeps_save_memory_before_loading_a_state() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let state = dir.path().join("blank.state");
    {
        let session = start(dir.path()).unwrap();
        session
            .save_state(state.to_string_lossy().into_owned())
            .unwrap();
        session.stop().unwrap();
    }
    let saves = dir.path().join("saves");
    let pattern = pattern(SAVE_RAM_SIZE);
    std::fs::create_dir_all(&saves).unwrap();
    std::fs::write(saves.join("game.srm"), &pattern).unwrap();

    let session = start(dir.path()).unwrap();
    session
        .load_state(state.to_string_lossy().into_owned())
        .unwrap();
    session.stop().unwrap();

    assert_ne!(std::fs::read(saves.join("game.srm")).unwrap(), pattern);
    assert_eq!(backups(&saves), [pattern]);
}

#[test]
fn keeps_no_backup_when_a_state_doesnt_load() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let saves = dir.path().join("saves");
    std::fs::create_dir_all(&saves).unwrap();
    std::fs::write(saves.join("game.srm"), pattern(SAVE_RAM_SIZE)).unwrap();

    let session = start(dir.path()).unwrap();
    let missing = dir.path().join("missing.state");
    assert!(matches!(
        session.load_state(missing.to_string_lossy().into_owned()),
        Err(SessionError::Io(_))
    ));
    session.stop().unwrap();

    assert!(backups(&saves).is_empty());
}

/// The contents of the backups of `game.srm` in `saves`.
fn backups(saves: &Path) -> Vec<Vec<u8>> {
    std::fs::read_dir(saves)
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| {
            let name = p.file_name().unwrap().to_string_lossy();
            name.starts_with("game.srm.") && name.ends_with(".bak")
        })
        .map(|p| std::fs::read(p).unwrap())
        .collect()
}

#[test]
fn a_stopped_session_refuses_work() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let session = start(dir.path()).unwrap();
    session.stop().unwrap();
    session.stop().unwrap();
    assert!(matches!(session.pause(), Err(SessionError::Stopped)));
}

#[test]
fn an_unreadable_save_file_fails_the_start() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    // A directory where the save file goes can't be read as one.
    let save = dir.path().join("saves/game.srm");
    std::fs::create_dir_all(&save).unwrap();
    assert!(matches!(
        start(dir.path()),
        Err(SessionError::ReadSaveRam(_))
    ));
    assert!(save.is_dir());
    // The failed start released the session.
    std::fs::remove_dir(&save).unwrap();
    start(dir.path()).unwrap().stop().unwrap();
}
