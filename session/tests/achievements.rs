//! RetroAchievements in the session, against a fake server: Gambatte running
//! µCity, identified by its hash, with achievements read from the core's
//! memory.

mod common;

use std::cell::RefCell;
use std::io::Write;
use std::rc::Rc;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use common::{Harness, fixture, lock, options};
use handheld_session::{
    AchievementEvent, AchievementOptions, AchievementStatus, AchievementsHttp, AchievementsRequest,
    GameSession, SessionError, SessionListener, SessionOptions,
};
use zip::write::SimpleFileOptions;

const CORE: &str = "gambatte_libretro.so";
const GAME: &str = "ucity.gbc";
/// RetroAchievements' hash of µCity: for the Game Boy Color, the MD5 of the
/// whole file.
const GAME_HASH: &str = "83fdaac59da9d3e8b1f73f529401a626";
const GAME_BOY_COLOR: u32 = 6;

const LOGIN: &str = r#"{"Success":true,"User":"Player","Token":"ApiToken","Score":0,"SoftcoreScore":0,"Messages":0}"#;
const NO_UNLOCKS: &str = r#"{"Success":true,"Unlocks":[],"HardcoreUnlocks":[]}"#;

/// A set whose one achievement measures the cartridge type in the ROM's
/// header, at $0147, against a target it can't reach.
fn set(game_id: u32) -> String {
    format!(
        r#"{{"Success":true,"GameId":{game_id},"Title":"µCity","ConsoleId":6,
        "ImageIconUrl":"","RichPresenceGameId":{game_id},"RichPresencePatch":"","Sets":[{{
        "AchievementSetId":1,"GameId":{game_id},"Title":null,"Type":"core","ImageIconUrl":"",
        "Achievements":[{{"ID":1,"Title":"Header","Description":"Read the header","Flags":3,
          "Points":1,"MemAddr":"M:0xH0147=255","Author":"Test","BadgeName":"00001",
          "Created":1367266583,"Modified":1376929305}}],
        "Leaderboards":[]}}]}}"#
    )
}

/// Answers each request the way RetroAchievements would for µCity's hash,
/// and remembers the hashes it was asked about.
fn answer(request: &AchievementsRequest, hashes: &mut Vec<String>) -> String {
    let params = request.post_data.clone().expect("a POST");
    let api = params.split('&').next().unwrap().trim_start_matches("r=");
    match api {
        "login2" => LOGIN.to_owned(),
        "achievementsets" => {
            let hash = params.rsplit("&m=").next().unwrap().to_owned();
            hashes.push(hash.clone());
            if hash == GAME_HASH {
                set(42)
            } else {
                r#"{"Success":false,"Error":"Unknown game","Code":"not_found","Status":404}"#
                    .to_owned()
            }
        }
        "startsession" => NO_UNLOCKS.to_owned(),
        "ping" => r#"{"Success":true}"#.to_owned(),
        other => panic!("unexpected request {other}"),
    }
}

fn account(hardcore: bool) -> AchievementOptions {
    AchievementOptions {
        username: "Player".to_owned(),
        token: "ApiToken".to_owned(),
        console_id: GAME_BOY_COLOR,
        hardcore,
    }
}

/// The requests a session sent, waiting for the test to answer them on the
/// session's thread.
type Waiting = Rc<RefCell<Vec<(u64, AchievementsRequest)>>>;

/// Starts `content` in Gambatte, taking part in RetroAchievements.
fn start(content: &str, hardcore: bool) -> (Harness, Waiting) {
    let waiting = Waiting::default();
    let sent = waiting.clone();
    let h = Harness::start(
        CORE,
        Some(content),
        &[],
        Some((
            account(hardcore),
            Box::new(move |id, request| sent.borrow_mut().push((id, request))),
        )),
    )
    .unwrap();
    (h, waiting)
}

/// Runs frames, answering the session's requests, until its achievements
/// are ready, and returns the hashes the server was asked about.
fn load(h: &mut Harness, waiting: &Waiting) -> Vec<String> {
    let mut hashes = Vec::new();
    for _ in 0..10 {
        let requests: Vec<_> = waiting.borrow_mut().drain(..).collect();
        for (id, request) in requests {
            let body = answer(&request, &mut hashes);
            h.session
                .achievements_response(id, Some(200), body.as_bytes());
        }
        h.run(1);
    }
    hashes
}

#[test]
fn identifies_the_game_and_reads_its_memory() {
    let _lock = lock();
    let (mut h, waiting) = start(GAME, false);
    assert_eq!(load(&mut h, &waiting), vec![GAME_HASH]);
    let AchievementStatus::Ready {
        game, achievements, ..
    } = h.session.achievement_status()
    else {
        panic!("not ready: {:?}", h.session.achievement_status());
    };
    assert_eq!(game, "µCity");
    // The header's cartridge type: MBC5 with RAM and a battery.
    let header = std::fs::read(fixture(GAME)).unwrap()[0x147];
    assert_eq!(header, 0x1b);
    assert_eq!(achievements[0].progress.as_deref(), Some("27/255"));
    assert!(!h.session.hardcore_restricts());
}

#[test]
fn hashes_the_game_extracted_from_a_zip() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("ucity.zip");
    let mut zip = zip::ZipWriter::new(std::fs::File::create(&archive).unwrap());
    zip.start_file("ucity.gbc", SimpleFileOptions::default())
        .unwrap();
    zip.write_all(&std::fs::read(fixture(GAME)).unwrap())
        .unwrap();
    zip.finish().unwrap();
    let (mut h, waiting) = start(archive.to_str().unwrap(), false);
    assert_eq!(load(&mut h, &waiting), vec![GAME_HASH]);
}

#[test]
fn hardcore_refuses_to_load_states() {
    let _lock = lock();
    let (mut h, waiting) = start(GAME, true);
    load(&mut h, &waiting);
    assert!(h.session.hardcore_restricts());
    let state = h.dirs.path().join("ucity.state");
    h.session.save_state(&state).unwrap();
    assert!(matches!(
        h.session.load_state(&state),
        Err(SessionError::Hardcore)
    ));
}

/// The app's side: sends each request on a thread of its own and hands the
/// response back to the session, as the app's HTTP client does.
struct AppHttp {
    session: Arc<OnceLock<Arc<GameSession>>>,
    hashes: Arc<Mutex<Vec<String>>>,
}

impl AchievementsHttp for AppHttp {
    fn request(&self, id: u64, request: AchievementsRequest) {
        let session = self.session.clone();
        let hashes = self.hashes.clone();
        std::thread::spawn(move || {
            let body = answer(&request, &mut hashes.lock().unwrap());
            let session = loop {
                if let Some(session) = session.get() {
                    break session;
                }
                std::thread::sleep(Duration::from_millis(5));
            };
            session.achievements_response(id, Some(200), body.into_bytes());
        });
    }
}

struct Quiet;

impl SessionListener for Quiet {
    fn core_quit(&self) {}
    fn achievement_event(&self, _event: AchievementEvent) {}
}

#[test]
fn the_app_sends_the_requests() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let cell = Arc::new(OnceLock::new());
    let hashes = Arc::new(Mutex::new(Vec::new()));
    let http = Arc::new(AppHttp {
        session: cell.clone(),
        hashes: hashes.clone(),
    });
    let session = GameSession::start(
        SessionOptions {
            achievements: Some(account(true)),
            ..options(dir.path(), CORE, Some(GAME))
        },
        Some(Arc::new(Quiet)),
        Some(http),
    )
    .unwrap();
    let _ = cell.set(session.clone());
    // The session takes the responses while paused.
    let deadline = Instant::now() + Duration::from_secs(10);
    let achievements = loop {
        match session.achievements().unwrap() {
            AchievementStatus::Ready { achievements, .. } => break achievements,
            AchievementStatus::Loading if Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(10))
            }
            status => panic!("not ready: {status:?}"),
        }
    };
    assert_eq!(achievements.len(), 1);
    assert_eq!(*hashes.lock().unwrap(), vec![GAME_HASH]);
    let path = dir.path().join("state");
    session
        .save_state(path.to_string_lossy().into_owned())
        .unwrap();
    assert!(matches!(
        session.load_state(path.to_string_lossy().into_owned()),
        Err(SessionError::Hardcore)
    ));
    session.stop().unwrap();
}

#[test]
fn without_an_http_client_the_session_has_no_achievements() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let session = GameSession::start(
        SessionOptions {
            achievements: Some(account(true)),
            ..options(dir.path(), CORE, Some(GAME))
        },
        None,
        None,
    )
    .unwrap();
    assert_eq!(session.achievements().unwrap(), AchievementStatus::Off);
    session.stop().unwrap();
}
