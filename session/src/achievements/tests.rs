//! The client against a fake server and a fake console's memory.

use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;

use super::*;
use crate::ffi::RETRO_MEMORY_SYSTEM_RAM;

thread_local! {
    /// The fake console's system memory, which each test thread has its own of.
    static RAM: RefCell<[u8; 2048]> = const { RefCell::new([0; 2048]) };
}

unsafe extern "C" fn ram_data(id: c_uint) -> *mut c_void {
    if id == RETRO_MEMORY_SYSTEM_RAM {
        RAM.with(|ram| ram.as_ptr().cast())
    } else {
        std::ptr::null_mut()
    }
}

unsafe extern "C" fn ram_size(id: c_uint) -> usize {
    if id == RETRO_MEMORY_SYSTEM_RAM {
        2048
    } else {
        0
    }
}

fn poke(address: usize, value: u8) {
    RAM.with(|ram| ram.borrow_mut()[address] = value);
}

/// The requests the client sent, waiting for the test to answer them.
#[derive(Clone, Default)]
struct FakeServer(Rc<RefCell<Vec<(u64, AchievementsRequest)>>>);

impl FakeServer {
    /// What the client sends its requests through.
    fn send(&self) -> SendAchievementsRequest {
        let requests = self.0.clone();
        Box::new(move |id, request| requests.borrow_mut().push((id, request)))
    }

    /// Takes the one waiting request, which calls the API `api`, and returns
    /// its id and form parameters.
    fn take(&self, api: &str) -> (u64, String) {
        let mut requests = self.0.borrow_mut();
        assert_eq!(requests.len(), 1, "requests waiting: {:?}", *requests);
        let (id, request) = requests.remove(0);
        let params = request.post_data.expect("a POST");
        assert!(
            params.starts_with(&format!("r={api}&")),
            "expected {api}, got {}",
            params.split('&').next().unwrap_or_default(),
        );
        assert_eq!(request.url, "https://retroachievements.org/dorequest.php");
        (id, params)
    }

    fn is_idle(&self) -> bool {
        self.0.borrow().is_empty()
    }
}

const NES: u32 = ffi::RC_CONSOLE_NINTENDO;

/// An NES game file, whose RetroAchievements hash is the MD5 of its bytes.
fn game(dir: &Path) -> (PathBuf, String) {
    let path = dir.join("game.nes");
    let bytes = b"not really an NES game".to_vec();
    std::fs::write(&path, &bytes).unwrap();
    // Precomputed: md5 of the bytes above.
    (path, "6c13885d98111a56e41e242784604141".to_owned())
}

const LOGIN: &str = r#"{"Success":true,"User":"Player","Token":"ApiToken","Score":0,"SoftcoreScore":0,"Messages":0}"#;

/// A set with one achievement, earned when RAM's second byte is 3.
const SET: &str = r#"{"Success":true,"GameId":1234,"Title":"Sample Game","ConsoleId":7,
  "ImageIconUrl":"","RichPresenceGameId":1234,"RichPresencePatch":"","Sets":[{
  "AchievementSetId":1111,"GameId":1234,"Title":null,"Type":"core","ImageIconUrl":"",
  "Achievements":[{"ID":5501,"Title":"Three","Description":"Make it three","Flags":3,
    "Points":5,"MemAddr":"0xH0001=3","Author":"Someone","BadgeName":"00234",
    "Created":1367266583,"Modified":1376929305},
   {"ID":5502,"Title":"Counting","Description":"Count to ten","Flags":3,
    "Points":2,"MemAddr":"M:0xH0002=10","Author":"Someone","BadgeName":"00235",
    "Created":1367266583,"Modified":1376929305}],
  "Leaderboards":[]}]}"#;

const NO_UNLOCKS: &str = r#"{"Success":true,"Unlocks":[],"HardcoreUnlocks":[]}"#;

fn options(hardcore: bool) -> AchievementOptions {
    AchievementOptions {
        username: "Player".to_owned(),
        token: "ApiToken".to_owned(),
        console_id: NES,
        hardcore,
    }
}

fn core() -> CoreMemory {
    CoreMemory {
        data: ram_data,
        size: ram_size,
    }
}

/// Starts a client and answers its sign-in, then its request for the game.
fn loaded(dir: &Path, hardcore: bool) -> (Achievements, FakeServer) {
    let (path, hash) = game(dir);
    let server = FakeServer::default();
    let achievements =
        Achievements::start(&options(hardcore), &path, server.send()).expect("a client");
    achievements.set_memory(None, core());
    assert!(achievements.has_memory());
    assert_eq!(achievements.status(), AchievementStatus::Loading);

    let (id, params) = server.take("login2");
    assert_eq!(params, "r=login2&u=Player&t=ApiToken");
    achievements.respond(id, Some(200), LOGIN.as_bytes());

    let (id, params) = server.take("achievementsets");
    assert!(params.ends_with(&format!("&m={hash}")), "{params}");
    achievements.respond(id, Some(200), SET.as_bytes());

    let (id, params) = server.take("startsession");
    assert!(params.contains("&g=1234&"), "{params}");
    assert!(
        params.contains(&format!("&h={}&", u8::from(hardcore))),
        "{params}"
    );
    achievements.respond(id, Some(200), NO_UNLOCKS.as_bytes());
    // The set activates on the next idle call or frame.
    achievements.idle();
    (achievements, server)
}

fn listed(achievements: &Achievements) -> Vec<Achievement> {
    match achievements.status() {
        AchievementStatus::Ready {
            game, achievements, ..
        } => {
            assert_eq!(game, "Sample Game");
            achievements
        }
        status => panic!("not ready: {status:?}"),
    }
}

fn find(list: &[Achievement], id: u32) -> &Achievement {
    list.iter().find(|a| a.id == id).expect("listed")
}

#[test]
fn unlocks_an_achievement_when_memory_matches() {
    let dir = tempfile::tempdir().unwrap();
    poke(1, 0);
    let (achievements, server) = loaded(dir.path(), false);
    let list = listed(&achievements);
    assert_eq!(list.len(), 2);
    assert!(!find(&list, 5501).unlocked);

    // An achievement waits to see its condition false before it can trigger.
    for _ in 0..3 {
        achievements.do_frame();
    }
    assert!(achievements.take_events().is_empty());
    poke(1, 3);
    achievements.do_frame();
    let events = achievements.take_events();
    let [AchievementEvent::Unlocked { achievement }] = events.as_slice() else {
        panic!("expected an unlock, got {events:?}");
    };
    assert_eq!(achievement.id, 5501);
    assert_eq!(achievement.title, "Three");
    assert_eq!(achievement.points, 5);

    let (id, params) = server.take("awardachievement");
    assert!(params.contains("&a=5501&h=0&"), "{params}");
    achievements.respond(
        id,
        Some(200),
        br#"{"Success":true,"Score":5,"SoftcoreScore":5,"AchievementID":5501,"AchievementsRemaining":1}"#,
    );
    assert!(find(&listed(&achievements), 5501).unlocked);
    assert!(server.is_idle());
}

#[test]
fn shows_measured_progress() {
    let dir = tempfile::tempdir().unwrap();
    poke(2, 0);
    let (achievements, _server) = loaded(dir.path(), false);
    achievements.do_frame();
    poke(2, 4);
    achievements.do_frame();
    let counting = find(&listed(&achievements), 5502).clone();
    assert_eq!(counting.progress.as_deref(), Some("4/10"));
}

#[test]
fn hardcore_restricts_only_games_with_achievements() {
    let dir = tempfile::tempdir().unwrap();
    let (achievements, _server) = loaded(dir.path(), true);
    assert!(achievements.restricts());
    let AchievementStatus::Ready { hardcore, .. } = achievements.status() else {
        panic!("not ready");
    };
    assert!(hardcore);

    // An unknown game has nothing to protect.
    let (path, _) = game(dir.path());
    let server = FakeServer::default();
    let unknown = Achievements::start(&options(true), &path, server.send()).unwrap();
    // Restricted until the game is identified.
    assert!(unknown.restricts());
    let (id, _) = server.take("login2");
    unknown.respond(id, Some(200), LOGIN.as_bytes());
    let (id, _) = server.take("achievementsets");
    unknown.respond(
        id,
        Some(404),
        br#"{"Success":false,"Error":"Unknown game","Code":"not_found","Status":404}"#,
    );
    assert_eq!(unknown.status(), AchievementStatus::NoSet);
    assert!(!unknown.restricts());
}

#[test]
fn the_set_waits_for_the_cores_memory() {
    let dir = tempfile::tempdir().unwrap();
    let (path, _) = game(dir.path());
    let server = FakeServer::default();
    let achievements = Achievements::start(&options(false), &path, server.send()).unwrap();
    let (id, _) = server.take("login2");
    achievements.respond(id, Some(200), LOGIN.as_bytes());
    let (id, _) = server.take("achievementsets");
    achievements.respond(id, Some(200), SET.as_bytes());
    let (id, _) = server.take("startsession");
    achievements.respond(id, Some(200), NO_UNLOCKS.as_bytes());
    // A core with no memory yet: the set's addresses would all fail.
    achievements.idle();
    achievements.do_frame();
    assert_eq!(achievements.status(), AchievementStatus::Loading);
    achievements.set_memory(None, core());
    achievements.idle();
    assert_eq!(listed(&achievements).len(), 2);
}

#[test]
fn an_empty_response_fails_cleanly() {
    let dir = tempfile::tempdir().unwrap();
    let (path, _) = game(dir.path());
    let server = FakeServer::default();
    let achievements = Achievements::start(&options(false), &path, server.send()).unwrap();
    let (id, _) = server.take("login2");
    achievements.respond(id, Some(500), b"");
    assert!(matches!(
        achievements.status(),
        AchievementStatus::Failed { .. }
    ));
}

#[test]
fn softcore_never_restricts() {
    let dir = tempfile::tempdir().unwrap();
    let (achievements, _server) = loaded(dir.path(), false);
    assert!(!achievements.restricts());
}

#[test]
fn says_when_the_token_no_longer_works() {
    let dir = tempfile::tempdir().unwrap();
    let (path, _) = game(dir.path());
    let server = FakeServer::default();
    let achievements = Achievements::start(&options(true), &path, server.send()).unwrap();
    let (id, _) = server.take("login2");
    achievements.respond(
        id,
        Some(401),
        br#"{"Success":false,"Error":"Invalid user/token combination.","Code":"invalid_credentials","Status":401}"#,
    );
    let reason = "Sign in to RetroAchievements again in Settings.".to_owned();
    assert_eq!(
        achievements.take_events(),
        vec![AchievementEvent::Failed {
            reason: reason.clone()
        }]
    );
    assert_eq!(achievements.status(), AchievementStatus::Failed { reason });
    assert!(!achievements.restricts());
    assert!(achievements.take_events().is_empty());
}

#[test]
fn retries_an_unlock_the_server_missed() {
    let dir = tempfile::tempdir().unwrap();
    poke(1, 0);
    let (achievements, server) = loaded(dir.path(), false);
    achievements.do_frame();
    poke(1, 3);
    achievements.do_frame();
    let (id, _) = server.take("awardachievement");
    // The server is out of reach: the client keeps the unlock and tries again.
    achievements.respond(id, None, b"");
    assert!(find(&listed(&achievements), 5501).unlocked);
}

#[test]
fn requests_hide_their_form_in_debug_output() {
    let request = AchievementsRequest {
        url: "https://retroachievements.org/dorequest.php".to_owned(),
        post_data: Some("r=login2&u=Player&t=secret".to_owned()),
        content_type: None,
    };
    assert!(!format!("{request:?}").contains("secret"));
}
