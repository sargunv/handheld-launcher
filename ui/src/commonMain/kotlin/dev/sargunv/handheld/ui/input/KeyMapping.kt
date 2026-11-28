package dev.sargunv.handheld.ui.input

import androidx.compose.ui.input.key.Key
import dev.sargunv.handheld.data.settings.ConfirmButton
import dev.sargunv.handheld.data.settings.InputSettings

/**
 * The pad button [key] stands for, or null if it isn't one. A desktop gamepad source delivers the
 * same [Key]s. D-pad keys double as the keyboard's arrow keys.
 */
fun padButtonFor(key: Key): PadButton? =
  when (key) {
    Key.ButtonA -> PadButton.A
    Key.ButtonB -> PadButton.B
    Key.ButtonX -> PadButton.X
    Key.ButtonY -> PadButton.Y
    Key.ButtonL1 -> PadButton.L1
    Key.ButtonR1 -> PadButton.R1
    Key.ButtonStart -> PadButton.Start
    Key.DirectionUp -> PadButton.DpadUp
    Key.DirectionDown -> PadButton.DpadDown
    Key.DirectionLeft -> PadButton.DpadLeft
    Key.DirectionRight -> PadButton.DpadRight
    else -> null
  }

/**
 * The action a non-gamepad [key] performs, or null if it does nothing.
 *
 * A keyboard isn't a pad, so its keys mean actions directly and don't follow [ConfirmButton]. The
 * system back key (Android's back button, Escape on a keyboard) always goes back.
 */
fun keyActionFor(key: Key): Action? =
  when (key) {
    Key.Enter,
    Key.NumPadEnter,
    Key.DirectionCenter -> Action.Confirm
    Key.Back,
    Key.Escape,
    Key.Backspace -> Action.Back
    Key.Tab,
    Key.Menu -> Action.Menu
    Key.PageUp -> Action.PreviousShelf
    Key.PageDown -> Action.NextShelf
    Key.F1 -> Action.MainMenu
    else -> null
  }

/** The action [key] performs under these settings, or null if it does nothing. */
fun InputSettings.actionFor(key: Key): Action? =
  padButtonFor(key)?.let(::actionFor) ?: keyActionFor(key)
