package dev.sargunv.handheld.ui.input

import dev.sargunv.handheld.data.settings.ConfirmButton
import dev.sargunv.handheld.data.settings.InputSettings

/** What the player asked for, whatever button or key they used. Every screen speaks this. */
enum class Action {
  Up,
  Down,
  Left,
  Right,
  /** Activates the focused item. */
  Confirm,
  /** Closes the dialog or leaves the screen. */
  Back,
  /** Opens the context menu for the focused item. */
  Menu,
  PreviousShelf,
  NextShelf,
  /** Opens the main menu (the Start button), which goes to the top-level pages. */
  MainMenu;

  /** Whether holding the input repeats this action. Only movement repeats. */
  val repeats: Boolean
    get() = direction != null

  /** The direction this action moves focus in, or null if it doesn't move focus. */
  val direction: Direction?
    get() =
      when (this) {
        Up -> Direction.Up
        Down -> Direction.Down
        Left -> Direction.Left
        Right -> Direction.Right
        else -> null
      }
}

enum class Direction {
  Up,
  Down,
  Left,
  Right,
}

/** A gamepad button the app uses, named by the key code it sends. */
enum class PadButton {
  A,
  B,
  X,
  Y,
  L1,
  R1,
  Start,
  DpadUp,
  DpadDown,
  DpadLeft,
  DpadRight,
}

/** The action [button] performs, or null if it does nothing. */
fun InputSettings.actionFor(button: PadButton): Action? =
  when (button) {
    PadButton.A -> if (confirmButton == ConfirmButton.A) Action.Confirm else Action.Back
    PadButton.B -> if (confirmButton == ConfirmButton.B) Action.Confirm else Action.Back
    PadButton.X -> Action.Menu
    PadButton.L1 -> Action.PreviousShelf
    PadButton.R1 -> Action.NextShelf
    PadButton.Start -> Action.MainMenu
    PadButton.DpadUp -> Action.Up
    PadButton.DpadDown -> Action.Down
    PadButton.DpadLeft -> Action.Left
    PadButton.DpadRight -> Action.Right
    PadButton.Y -> null
  }

/** The button that performs [action], or null when none does. */
fun InputSettings.buttonFor(action: Action): PadButton? =
  PadButton.entries.firstOrNull { actionFor(it) == action }
