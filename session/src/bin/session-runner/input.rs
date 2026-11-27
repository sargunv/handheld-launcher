//! Keyboard and gamepad input, mapped to the RetroPad.

use gilrs::{Axis, EventType, Gilrs};
use handheld_session::{Button, Port, Stick};
use winit::keyboard::KeyCode;

/// The RetroPad button a key presses.
pub fn key_button(key: KeyCode) -> Option<Button> {
    Some(match key {
        KeyCode::ArrowUp => Button::Up,
        KeyCode::ArrowDown => Button::Down,
        KeyCode::ArrowLeft => Button::Left,
        KeyCode::ArrowRight => Button::Right,
        KeyCode::KeyZ => Button::B,
        KeyCode::KeyX => Button::A,
        KeyCode::KeyA => Button::Y,
        KeyCode::KeyS => Button::X,
        KeyCode::KeyQ => Button::L,
        KeyCode::KeyW => Button::R,
        KeyCode::Digit1 => Button::L2,
        KeyCode::Digit2 => Button::R2,
        KeyCode::Enter => Button::Start,
        KeyCode::ShiftRight | KeyCode::Backspace => Button::Select,
        _ => return None,
    })
}

/// The RetroPad button for a gamepad button, by position: gilrs names face
/// buttons by compass direction, and the RetroPad by the SNES's letters.
fn pad_button(button: gilrs::Button) -> Option<Button> {
    use gilrs::Button as G;
    Some(match button {
        G::South => Button::B,
        G::East => Button::A,
        G::West => Button::Y,
        G::North => Button::X,
        G::LeftTrigger => Button::L,
        G::RightTrigger => Button::R,
        G::LeftTrigger2 => Button::L2,
        G::RightTrigger2 => Button::R2,
        G::LeftThumb => Button::L3,
        G::RightThumb => Button::R3,
        G::Select => Button::Select,
        G::Start => Button::Start,
        G::DPadUp => Button::Up,
        G::DPadDown => Button::Down,
        G::DPadLeft => Button::Left,
        G::DPadRight => Button::Right,
        _ => return None,
    })
}

/// Gamepads, when the system lets us read them.
pub struct Gamepads {
    gilrs: Option<Gilrs>,
}

impl Gamepads {
    pub fn new() -> Self {
        let gilrs = Gilrs::new()
            .inspect_err(|e| log::warn!("no gamepad support: {e}"))
            .ok();
        if let Some(gilrs) = &gilrs {
            for (_, pad) in gilrs.gamepads() {
                log::info!("gamepad: {}", pad.name());
            }
        }
        Self { gilrs }
    }

    /// Applies the gamepads' button events to `port`, and sets each of its
    /// sticks from the gamepad that pushes it furthest.
    pub fn update(&mut self, port: &Port) {
        let Some(gilrs) = &mut self.gilrs else {
            return;
        };
        while let Some(event) = gilrs.next_event() {
            let (button, pressed) = match event.event {
                EventType::ButtonPressed(button, _) => (button, true),
                EventType::ButtonReleased(button, _) => (button, false),
                EventType::Connected => {
                    log::info!("gamepad connected: {}", gilrs.gamepad(event.id).name());
                    continue;
                }
                _ => continue,
            };
            if let Some(button) = pad_button(button) {
                port.set_button(button, pressed);
            }
        }
        let (mut left, mut right) = ((0, 0), (0, 0));
        for (_, pad) in gilrs.gamepads() {
            // gilrs's sticks point up for positive Y; the RetroPad's point
            // down.
            let pad_left = (
                axis(pad.value(Axis::LeftStickX)),
                axis(-pad.value(Axis::LeftStickY)),
            );
            let pad_right = (
                axis(pad.value(Axis::RightStickX)),
                axis(-pad.value(Axis::RightStickY)),
            );
            left = further(left, pad_left);
            right = further(right, pad_right);
        }
        port.set_stick(Stick::Left, left.0, left.1);
        port.set_stick(Stick::Right, right.0, right.1);
    }
}

/// Whichever stick position is further from the center.
fn further(a: (i16, i16), b: (i16, i16)) -> (i16, i16) {
    let magnitude = |(x, y): (i16, i16)| i32::from(x).pow(2) + i32::from(y).pow(2);
    if magnitude(b) > magnitude(a) { b } else { a }
}

fn axis(value: f32) -> i16 {
    (value.clamp(-1.0, 1.0) * 32767.0) as i16
}
