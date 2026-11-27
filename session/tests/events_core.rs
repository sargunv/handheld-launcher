//! The session against tests/cores/events.c, a core that draws a stripe for
//! each port, white while it's pressed and gray once the session sets a
//! RetroPad on it, switches its video and audio modes on Select and quits on
//! Start.

mod common;

use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};

use common::{Harness, WAIT, lock, options, pixel, wait_until};
use handheld_session::{
    AchievementEvent, AchievementsReply, AchievementsRequest, AvInfo, Button, GameSession,
    SessionError, SessionListener,
};

const CORE: &str = "events_libretro.so";
const WHITE: [u8; 4] = [255, 255, 255, 255];
const GRAY: [u8; 4] = [64, 64, 64, 255];
const BLACK: [u8; 4] = [0, 0, 0, 255];

/// The color of each port's stripe in the last frame.
fn stripes(h: &Harness) -> Vec<[u8; 4]> {
    let frame = h.frames.last();
    (0..4)
        .map(|port| pixel(&frame, port * frame.width / 4 + 1, 0))
        .collect()
}

#[test]
fn every_port_reaches_the_core() {
    let _lock = lock();
    let mut h = Harness::start(CORE).unwrap();
    h.run(1);
    // The core declares three ports, so the session sets a RetroPad on those.
    assert_eq!(stripes(&h), [GRAY, GRAY, GRAY, BLACK]);

    h.input.port(1).set_button(Button::A, true);
    h.input.port(3).set_button(Button::B, true);
    h.run(1);
    assert_eq!(stripes(&h), [GRAY, WHITE, GRAY, WHITE]);

    h.input.port(1).set_button(Button::A, false);
    h.input.port(3).set_button(Button::B, false);
    h.input.port(2).set_button(Button::Up, true);
    h.run(1);
    assert_eq!(stripes(&h), [GRAY, GRAY, WHITE, BLACK]);
}

#[test]
fn takes_a_new_video_and_audio_mode_mid_game() {
    let _lock = lock();
    let mut h = Harness::start(CORE).unwrap();
    h.run(1);
    assert_eq!(h.session.av_info().sample_rate, 44_100.0);

    h.input.port(0).set_button(Button::Select, true);
    h.run(1);
    h.input.port(0).set_button(Button::Select, false);
    let av = h.session.av_info();
    assert_eq!(
        av,
        AvInfo {
            base_width: 384,
            base_height: 216,
            max_width: 384,
            max_height: 240,
            aspect_ratio: 16.0 / 9.0,
            fps: 50.0,
            sample_rate: 32_000.0,
        }
    );
    let frame = h.frames.last();
    assert_eq!((frame.width, frame.height), (384, 216));

    // A second of the core's audio at its new rate still comes out as a
    // second at the sink's.
    let before = h.audio.frames();
    h.run(50);
    let frames = h.audio.frames() - before;
    assert!(
        frames.abs_diff(48_000) < 1_000,
        "{frames} frames for a second"
    );
}

/// A listener that says when the core quits.
struct Forward(Sender<()>);

impl SessionListener for Forward {
    fn core_quit(&self) {
        let _ = self.0.send(());
    }

    fn achievement_event(&self, _event: AchievementEvent) {}

    fn achievements_request(&self, _request: AchievementsRequest, _reply: Arc<AchievementsReply>) {}
}

fn start(dir: &std::path::Path) -> (Arc<GameSession>, Receiver<()>) {
    let (heard, receiver) = mpsc::channel();
    let session = GameSession::start(options(dir, CORE, None), Arc::new(Forward(heard))).unwrap();
    (session, receiver)
}

/// The size of the session's last frame, once it has one.
fn frame_size(session: &GameSession) -> Option<(u32, u32)> {
    let frame = session.capture_frame().unwrap()?;
    Some((frame.width, frame.height))
}

#[test]
fn a_running_session_takes_a_new_video_mode() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let (session, _) = start(dir.path());
    session.resume();
    wait_until("a frame", || frame_size(&session).is_some());
    assert_eq!(frame_size(&session), Some((320, 240)));
    session.set_button(0, Button::Select, true);
    wait_until("the new video mode", || {
        frame_size(&session) == Some((384, 216))
    });
    session.stop().unwrap();
}

#[test]
fn tells_the_app_when_the_core_quits() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let (session, heard) = start(dir.path());
    session.resume();
    session.set_button(0, Button::Start, true);
    heard.recv_timeout(WAIT).unwrap();
    assert!(matches!(session.pause(), Err(SessionError::Stopped)));
    session.stop().unwrap();
    // The core is unloaded by the time the app hears, so another may start.
    let (_next, _) = start(dir.path());
}

/// A listener that stops its session when the core quits, as an app would.
struct StopOnQuit(Mutex<Option<Arc<GameSession>>>, Sender<()>);

impl SessionListener for StopOnQuit {
    fn core_quit(&self) {
        if let Some(session) = self.0.lock().unwrap().take() {
            session.stop().unwrap();
        }
        let _ = self.1.send(());
    }

    fn achievement_event(&self, _event: AchievementEvent) {}

    fn achievements_request(&self, _request: AchievementsRequest, _reply: Arc<AchievementsReply>) {}
}

#[test]
fn the_listener_may_stop_the_session() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let (stopped, receiver) = mpsc::channel();
    let listener = Arc::new(StopOnQuit(Mutex::new(None), stopped));
    let session = GameSession::start(options(dir.path(), CORE, None), listener.clone()).unwrap();
    *listener.0.lock().unwrap() = Some(session.clone());
    session.set_button(0, Button::Start, true);
    session.resume();
    receiver.recv_timeout(WAIT).unwrap();
}
