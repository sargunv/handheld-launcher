//! The session against tests/cores/events.c, a core that draws a stripe for
//! each port, white while it's pressed and gray once the session sets a
//! RetroPad on it, switches its video and audio modes on Select and quits on
//! Start.

mod common;

use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use common::{Harness, lock, options};
use handheld_session::{
    AchievementEvent, AvInfo, Button, GameSession, SessionError, SessionInfo, SessionListener,
};

const CORE: &str = "events_libretro.so";
const WHITE: [u8; 4] = [255, 255, 255, 255];
const GRAY: [u8; 4] = [64, 64, 64, 255];
const BLACK: [u8; 4] = [0, 0, 0, 255];

/// The color of each port's stripe in the last frame.
fn stripes(h: &Harness) -> Vec<[u8; 4]> {
    let frame = h.frames.last();
    (0..4)
        .map(|port| frame.pixel(port * frame.width / 4 + 1, 0))
        .collect()
}

#[test]
fn every_port_reaches_the_core() {
    let _lock = lock();
    let mut h = Harness::start(CORE, None, &[], None).unwrap();
    h.run(1);
    // The core declares three ports, so the session sets a RetroPad on those.
    assert_eq!(stripes(&h), [GRAY, GRAY, GRAY, BLACK]);

    h.input.port(1).set_button(Button::A, true);
    h.input.port(3).set_button(Button::B, true);
    h.run(1);
    assert_eq!(stripes(&h), [GRAY, WHITE, GRAY, WHITE]);

    h.input.port(1).set_buttons(0);
    h.input.port(3).set_buttons(0);
    h.input.port(2).set_button(Button::Up, true);
    h.run(1);
    assert_eq!(stripes(&h), [GRAY, GRAY, WHITE, BLACK]);
}

#[test]
fn takes_a_new_video_and_audio_mode_mid_game() {
    let _lock = lock();
    let mut h = Harness::start(CORE, None, &[], None).unwrap();
    h.run(1);
    assert_eq!(h.session.av_info().sample_rate, 44_100.0);

    h.input.port(0).set_button(Button::Select, true);
    h.run(1);
    h.input.port(0).set_buttons(0);
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

/// A listener that forwards what it hears.
struct Forward(Mutex<Sender<Heard>>);

#[derive(Debug, PartialEq)]
enum Heard {
    Info(SessionInfo),
    Quit,
}

impl SessionListener for Forward {
    fn info_changed(&self, info: SessionInfo) {
        let _ = self.0.lock().unwrap().send(Heard::Info(info));
    }

    fn core_quit(&self) {
        let _ = self.0.lock().unwrap().send(Heard::Quit);
    }

    fn achievement_event(&self, _event: AchievementEvent) {}
}

fn start(dir: &std::path::Path) -> (Arc<GameSession>, Receiver<Heard>) {
    let (heard, receiver) = mpsc::channel();
    let session = GameSession::start(
        options(dir, CORE, None),
        Some(Arc::new(Forward(Mutex::new(heard)))),
        None,
    )
    .unwrap();
    (session, receiver)
}

const WAIT: Duration = Duration::from_secs(10);

#[test]
fn tells_the_app_about_a_new_video_mode() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let (session, heard) = start(dir.path());
    assert_eq!(session.info().base_width, 320);
    session.advance(1).unwrap();
    assert!(heard.try_recv().is_err());

    session.set_button(0, Button::Select, true);
    session.advance(1).unwrap();
    assert_eq!(session.info().base_width, 384);
    let Heard::Info(info) = heard.recv_timeout(WAIT).unwrap() else {
        panic!("expected new info");
    };
    assert_eq!((info.base_width, info.base_height), (384, 216));
    assert_eq!(info.fps, 50.0);
    assert_eq!(info.aspect_ratio, 16.0 / 9.0);
    assert_eq!(session.info(), info);
    session.stop().unwrap();
}

#[test]
fn tells_the_app_when_the_core_quits() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let (session, heard) = start(dir.path());
    session.resume();
    session.set_button(0, Button::Start, true);
    assert_eq!(heard.recv_timeout(WAIT).unwrap(), Heard::Quit);
    assert!(matches!(session.advance(1), Err(SessionError::Stopped)));
    session.stop().unwrap();
    // The core is unloaded by the time the app hears, so another may start.
    let (_next, _) = start(dir.path());
}

/// A listener that stops its session when the core quits, as an app would.
struct StopOnQuit(Mutex<Option<Arc<GameSession>>>, Mutex<Sender<()>>);

impl SessionListener for StopOnQuit {
    fn info_changed(&self, _info: SessionInfo) {}

    fn core_quit(&self) {
        if let Some(session) = self.0.lock().unwrap().take() {
            session.stop().unwrap();
        }
        let _ = self.1.lock().unwrap().send(());
    }

    fn achievement_event(&self, _event: AchievementEvent) {}
}

#[test]
fn the_listener_may_stop_the_session() {
    let _lock = lock();
    let dir = tempfile::tempdir().unwrap();
    let (stopped, receiver) = mpsc::channel();
    let listener = Arc::new(StopOnQuit(Mutex::new(None), Mutex::new(stopped)));
    let session = GameSession::start(
        options(dir.path(), CORE, None),
        Some(listener.clone()),
        None,
    )
    .unwrap();
    *listener.0.lock().unwrap() = Some(session.clone());
    session.set_button(0, Button::Start, true);
    session.advance(1).unwrap();
    receiver.recv_timeout(WAIT).unwrap();
}
