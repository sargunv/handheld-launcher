//! Controller state that the platform layer fills in and the core reads, in
//! libretro's RetroPad layout, and the touchscreen, as libretro's pointer.

use std::sync::atomic::{AtomicBool, AtomicI16, AtomicU16, AtomicU32, Ordering};

use crate::ffi;

/// How many controller ports a session exposes.
pub const MAX_PORTS: usize = 4;

/// A RetroPad button. The RetroPad follows the SNES layout: `B` is the bottom
/// face button, `A` the right one, `Y` the left one and `X` the top one.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, uniffi::Enum)]
#[repr(u8)]
pub enum Button {
    B = 0,
    Y = 1,
    Select = 2,
    Start = 3,
    Up = 4,
    Down = 5,
    Left = 6,
    Right = 7,
    A = 8,
    X = 9,
    L = 10,
    R = 11,
    L2 = 12,
    R2 = 13,
    L3 = 14,
    R3 = 15,
}

impl Button {
    /// The button's bit in a [`Port::buttons`] mask.
    pub fn mask(self) -> u16 {
        1 << self as u16
    }
}

/// An analog stick.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum Stick {
    Left,
    Right,
}

/// The state of every port. The platform layer writes it from any thread; the
/// session reads a snapshot of it when the core polls input.
///
/// A button pressed and released between two frames still reaches the core,
/// as pressed for the next frame, so that quick taps aren't lost.
#[derive(Debug, Default)]
pub struct InputState {
    ports: [Port; MAX_PORTS],
}

impl InputState {
    /// A state with nothing pressed.
    pub fn new() -> Self {
        Self::default()
    }

    /// The port with index `index`.
    ///
    /// # Panics
    ///
    /// If `index` is [`MAX_PORTS`] or more.
    pub fn port(&self, index: usize) -> &Port {
        &self.ports[index]
    }

    /// The buttons pressed on each port since the last call, and whether its
    /// pointer touched down, as [`Button::mask`] bits and [`POINTER_PRESS`],
    /// which the session takes as a frame starts.
    pub(crate) fn take_presses(&self) -> [u32; MAX_PORTS] {
        std::array::from_fn(|i| {
            let port = &self.ports[i];
            let buttons = u32::from(port.presses.swap(0, Ordering::Relaxed));
            let pointer = port.pointer_touched.swap(false, Ordering::Relaxed);
            buttons | if pointer { POINTER_PRESS } else { 0 }
        })
    }

    /// The state the core sees, with the frame's `presses` held down.
    pub(crate) fn snapshot(&self, presses: &[u32; MAX_PORTS]) -> [PortSnapshot; MAX_PORTS] {
        std::array::from_fn(|i| self.ports[i].snapshot(presses[i]))
    }
}

/// One controller port.
#[derive(Debug, Default)]
pub struct Port {
    buttons: AtomicU16,
    /// Buttons pressed since the session last took them.
    presses: AtomicU16,
    /// Left X, left Y, right X, right Y.
    sticks: [AtomicI16; 4],
    /// L2 and R2, for cores that read them as analog triggers.
    triggers: [AtomicI16; 2],
    /// Where the pointer last was, x in the high half and y in the low.
    pointer_position: AtomicU32,
    pointer_pressed: AtomicBool,
    /// Whether the pointer touched down since the session last took it.
    pointer_touched: AtomicBool,
}

impl Port {
    /// Presses or releases `button`.
    pub fn set_button(&self, button: Button, pressed: bool) {
        if pressed {
            self.buttons.fetch_or(button.mask(), Ordering::Relaxed);
            self.presses.fetch_or(button.mask(), Ordering::Relaxed);
        } else {
            self.buttons.fetch_and(!button.mask(), Ordering::Relaxed);
        }
    }

    /// Replaces every button at once with a mask of [`Button::mask`] bits.
    pub fn set_buttons(&self, mask: u16) {
        let before = self.buttons.swap(mask, Ordering::Relaxed);
        self.presses.fetch_or(mask & !before, Ordering::Relaxed);
    }

    /// Moves a stick. Each axis runs from -32768 to 32767, with positive `x`
    /// to the right and positive `y` down.
    pub fn set_stick(&self, stick: Stick, x: i16, y: i16) {
        let base = match stick {
            Stick::Left => 0,
            Stick::Right => 2,
        };
        self.sticks[base].store(x, Ordering::Relaxed);
        self.sticks[base + 1].store(y, Ordering::Relaxed);
    }

    /// Sets how far an analog trigger is pulled, from 0 to 32767. Cores that
    /// read the trigger as a button see it pressed while it's pulled past
    /// halfway or [`Port::set_button`] holds it, since many gamepads report
    /// their triggers only as axes.
    ///
    /// # Panics
    ///
    /// If `button` isn't [`Button::L2`] or [`Button::R2`].
    pub fn set_trigger(&self, button: Button, value: i16) {
        let index = match button {
            Button::L2 => 0,
            Button::R2 => 1,
            _ => panic!("{button:?} isn't an analog trigger"),
        };
        self.triggers[index].store(value.max(0), Ordering::Relaxed);
    }

    /// Touches the pointer down at (`x`, `y`), or moves it there, or lifts it
    /// there when `pressed` is false. Each axis runs from -32767 to 32767,
    /// across the core's whole frame, with positive `y` down, as libretro's
    /// pointer does. A touch lifted before the next frame still reaches the
    /// core, as touching for that frame.
    pub fn set_pointer(&self, x: i16, y: i16, pressed: bool) {
        let position = (u32::from(x as u16) << 16) | u32::from(y as u16);
        self.pointer_position.store(position, Ordering::Relaxed);
        self.pointer_pressed.store(pressed, Ordering::Relaxed);
        if pressed {
            self.pointer_touched.store(true, Ordering::Relaxed);
        }
    }

    /// Lifts the pointer where it is.
    pub fn release_pointer(&self) {
        self.pointer_pressed.store(false, Ordering::Relaxed);
    }

    fn snapshot(&self, presses: u32) -> PortSnapshot {
        let triggers: [i16; 2] = std::array::from_fn(|i| self.triggers[i].load(Ordering::Relaxed));
        let mut buttons = self.buttons.load(Ordering::Relaxed) | presses as u16;
        for (value, button) in triggers.iter().zip([Button::L2, Button::R2]) {
            if *value > TRIGGER_PRESSED {
                buttons |= button.mask();
            }
        }
        let position = self.pointer_position.load(Ordering::Relaxed);
        PortSnapshot {
            buttons,
            sticks: std::array::from_fn(|i| self.sticks[i].load(Ordering::Relaxed)),
            triggers,
            pointer: [(position >> 16) as u16 as i16, position as u16 as i16],
            pointer_pressed: self.pointer_pressed.load(Ordering::Relaxed)
                || presses & POINTER_PRESS != 0,
        }
    }
}

/// How far an analog trigger is pulled, past which it counts as a pressed
/// button.
const TRIGGER_PRESSED: i16 = i16::MAX / 2;

/// The bit of a port's presses that says its pointer touched down, above the
/// buttons' bits.
pub(crate) const POINTER_PRESS: u32 = 1 << 16;

/// A port's state as the core sees it for one poll.
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct PortSnapshot {
    buttons: u16,
    sticks: [i16; 4],
    triggers: [i16; 2],
    /// X and y.
    pointer: [i16; 2],
    pointer_pressed: bool,
}

/// Answers the core's `input_state` query from a snapshot.
pub(crate) fn query(
    ports: &[PortSnapshot; MAX_PORTS],
    port: u32,
    device: u32,
    index: u32,
    id: u32,
) -> i16 {
    let Some(state) = ports.get(port as usize) else {
        return 0;
    };
    match device & ffi::RETRO_DEVICE_MASK {
        ffi::RETRO_DEVICE_JOYPAD => {
            if id == ffi::RETRO_DEVICE_ID_JOYPAD_MASK {
                state.buttons as i16
            } else if id < 16 {
                i16::from(state.buttons & (1 << id) != 0)
            } else {
                0
            }
        }
        ffi::RETRO_DEVICE_ANALOG => match (index, id) {
            (ffi::RETRO_DEVICE_INDEX_ANALOG_LEFT, ffi::RETRO_DEVICE_ID_ANALOG_X) => state.sticks[0],
            (ffi::RETRO_DEVICE_INDEX_ANALOG_LEFT, ffi::RETRO_DEVICE_ID_ANALOG_Y) => state.sticks[1],
            (ffi::RETRO_DEVICE_INDEX_ANALOG_RIGHT, ffi::RETRO_DEVICE_ID_ANALOG_X) => {
                state.sticks[2]
            }
            (ffi::RETRO_DEVICE_INDEX_ANALOG_RIGHT, ffi::RETRO_DEVICE_ID_ANALOG_Y) => {
                state.sticks[3]
            }
            (ffi::RETRO_DEVICE_INDEX_ANALOG_BUTTON, id) if id < 16 => {
                let trigger = match id {
                    ffi::RETRO_DEVICE_ID_JOYPAD_L2 => state.triggers[0],
                    ffi::RETRO_DEVICE_ID_JOYPAD_R2 => state.triggers[1],
                    _ => 0,
                };
                if trigger > 0 {
                    trigger
                } else if state.buttons & (1 << id) != 0 {
                    i16::MAX
                } else {
                    0
                }
            }
            _ => 0,
        },
        // One touch: later ones, and a second touch, read as untouched.
        ffi::RETRO_DEVICE_POINTER if index == 0 => match id {
            ffi::RETRO_DEVICE_ID_POINTER_X => state.pointer[0],
            ffi::RETRO_DEVICE_ID_POINTER_Y => state.pointer[1],
            ffi::RETRO_DEVICE_ID_POINTER_PRESSED => i16::from(state.pointer_pressed),
            ffi::RETRO_DEVICE_ID_POINTER_COUNT => i16::from(state.pointer_pressed),
            _ => 0,
        },
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn query_one(state: &InputState, device: u32, index: u32, id: u32) -> i16 {
        query(&state.snapshot(&[0; MAX_PORTS]), 0, device, index, id)
    }

    #[test]
    fn button_ids_match_libretro() {
        assert_eq!(Button::B as u32, ffi::RETRO_DEVICE_ID_JOYPAD_B);
        assert_eq!(Button::Start as u32, ffi::RETRO_DEVICE_ID_JOYPAD_START);
        assert_eq!(Button::A as u32, ffi::RETRO_DEVICE_ID_JOYPAD_A);
        assert_eq!(Button::R3 as u32, ffi::RETRO_DEVICE_ID_JOYPAD_R3);
    }

    #[test]
    fn joypad_reads_buttons_and_mask() {
        let state = InputState::new();
        state.port(0).set_button(Button::A, true);
        state.port(0).set_button(Button::Left, true);
        state.port(0).set_button(Button::Left, false);
        let joypad = ffi::RETRO_DEVICE_JOYPAD;
        assert_eq!(
            query_one(&state, joypad, 0, ffi::RETRO_DEVICE_ID_JOYPAD_A),
            1
        );
        assert_eq!(
            query_one(&state, joypad, 0, ffi::RETRO_DEVICE_ID_JOYPAD_LEFT),
            0
        );
        assert_eq!(
            query_one(&state, joypad, 0, ffi::RETRO_DEVICE_ID_JOYPAD_MASK),
            Button::A.mask() as i16
        );
        assert_eq!(
            query(
                &state.snapshot(&[0; MAX_PORTS]),
                1,
                joypad,
                0,
                ffi::RETRO_DEVICE_ID_JOYPAD_A
            ),
            0
        );
        assert_eq!(
            query(
                &state.snapshot(&[0; MAX_PORTS]),
                9,
                joypad,
                0,
                ffi::RETRO_DEVICE_ID_JOYPAD_A
            ),
            0
        );
    }

    #[test]
    fn analog_reads_sticks_and_triggers() {
        let state = InputState::new();
        state.port(0).set_stick(Stick::Right, -100, 200);
        state.port(0).set_trigger(Button::R2, 1234);
        state.port(0).set_button(Button::L2, true);
        let analog = ffi::RETRO_DEVICE_ANALOG;
        let right = ffi::RETRO_DEVICE_INDEX_ANALOG_RIGHT;
        let button = ffi::RETRO_DEVICE_INDEX_ANALOG_BUTTON;
        assert_eq!(
            query_one(&state, analog, right, ffi::RETRO_DEVICE_ID_ANALOG_X),
            -100
        );
        assert_eq!(
            query_one(&state, analog, right, ffi::RETRO_DEVICE_ID_ANALOG_Y),
            200
        );
        assert_eq!(
            query_one(&state, analog, button, ffi::RETRO_DEVICE_ID_JOYPAD_R2),
            1234
        );
        assert_eq!(
            query_one(&state, analog, button, ffi::RETRO_DEVICE_ID_JOYPAD_L2),
            i16::MAX
        );
        assert_eq!(
            query_one(&state, analog, button, ffi::RETRO_DEVICE_ID_JOYPAD_A),
            0
        );
    }

    #[test]
    fn triggers_pulled_past_halfway_press_their_buttons() {
        let state = InputState::new();
        let joypad = ffi::RETRO_DEVICE_JOYPAD;
        state.port(0).set_trigger(Button::L2, i16::MAX / 4);
        state.port(0).set_trigger(Button::R2, i16::MAX);
        assert_eq!(
            query_one(&state, joypad, 0, ffi::RETRO_DEVICE_ID_JOYPAD_L2),
            0
        );
        assert_eq!(
            query_one(&state, joypad, 0, ffi::RETRO_DEVICE_ID_JOYPAD_R2),
            1
        );
        assert_eq!(
            query_one(&state, joypad, 0, ffi::RETRO_DEVICE_ID_JOYPAD_MASK),
            Button::R2.mask() as i16
        );
        state.port(0).set_trigger(Button::R2, 0);
        assert_eq!(
            query_one(&state, joypad, 0, ffi::RETRO_DEVICE_ID_JOYPAD_R2),
            0
        );
    }

    #[test]
    fn snapshot_is_stable_while_state_changes() {
        let state = InputState::new();
        state.port(0).set_button(Button::Start, true);
        let snapshot = state.snapshot(&[0; MAX_PORTS]);
        state.port(0).set_buttons(0);
        let joypad = ffi::RETRO_DEVICE_JOYPAD;
        assert_eq!(
            query(&snapshot, 0, joypad, 0, ffi::RETRO_DEVICE_ID_JOYPAD_START),
            1
        );
    }

    #[test]
    fn taps_between_frames_last_a_frame() {
        let state = InputState::new();
        let joypad = ffi::RETRO_DEVICE_JOYPAD;
        let a = ffi::RETRO_DEVICE_ID_JOYPAD_A;
        state.port(0).set_button(Button::A, true);
        state.port(0).set_button(Button::A, false);
        let presses = state.take_presses();
        let frame = state.snapshot(&presses);
        assert_eq!(query(&frame, 0, joypad, 0, a), 1);
        // A poll later in the same frame still sees it.
        assert_eq!(query(&state.snapshot(&presses), 0, joypad, 0, a), 1);
        let next = state.snapshot(&state.take_presses());
        assert_eq!(query(&next, 0, joypad, 0, a), 0);
    }

    #[test]
    fn pointer_reads_its_position_and_touch() {
        let state = InputState::new();
        let pointer = ffi::RETRO_DEVICE_POINTER;
        state.port(0).set_pointer(-1000, 32767, true);
        assert_eq!(
            query_one(&state, pointer, 0, ffi::RETRO_DEVICE_ID_POINTER_X),
            -1000
        );
        assert_eq!(
            query_one(&state, pointer, 0, ffi::RETRO_DEVICE_ID_POINTER_Y),
            32767
        );
        let pressed = ffi::RETRO_DEVICE_ID_POINTER_PRESSED;
        assert_eq!(query_one(&state, pointer, 0, pressed), 1);
        assert_eq!(query_one(&state, pointer, 1, pressed), 0);
        state.port(0).release_pointer();
        assert_eq!(query_one(&state, pointer, 0, pressed), 0);
        // Lifting keeps the position.
        assert_eq!(
            query_one(&state, pointer, 0, ffi::RETRO_DEVICE_ID_POINTER_X),
            -1000
        );
    }

    #[test]
    fn touches_between_frames_last_a_frame() {
        let state = InputState::new();
        let pointer = ffi::RETRO_DEVICE_POINTER;
        let pressed = ffi::RETRO_DEVICE_ID_POINTER_PRESSED;
        state.port(0).set_pointer(10, 20, true);
        state.port(0).set_pointer(10, 20, false);
        let frame = state.snapshot(&state.take_presses());
        assert_eq!(query(&frame, 0, pointer, 0, pressed), 1);
        assert_eq!(
            query(&frame, 0, pointer, 0, ffi::RETRO_DEVICE_ID_POINTER_Y),
            20
        );
        let next = state.snapshot(&state.take_presses());
        assert_eq!(query(&next, 0, pointer, 0, pressed), 0);
    }
}
