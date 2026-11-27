package dev.sargunv.handheld.ui.input

import kotlinx.serialization.Serializable

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

/**
 * A gamepad button, named by the key code it sends. The face buttons are Android's `BUTTON_A`,
 * `BUTTON_B`, `BUTTON_X` and `BUTTON_Y`, wherever they sit: a handheld's own layout setting decides
 * which physical button sends which, and [AButtonPosition] says where each one is.
 */
enum class PadButton {
  A,
  B,
  X,
  Y,
  L1,
  R1,
  L2,
  R2,
  ThumbLeft,
  ThumbRight,
  Start,
  Select,
  DpadUp,
  DpadDown,
  DpadLeft,
  DpadRight,
}

/** Which face button confirms. The other one of the pair goes back. */
@Serializable
enum class ConfirmButton {
  A,
  B,
}

/**
 * The player's input preferences. Navigation follows the key codes, so [aButtonPosition] changes no
 * action; it tells the built-in session where each face button sits.
 */
@Serializable
data class InputSettings(
  val confirmButton: ConfirmButton = ConfirmButton.A,
  val aButtonPosition: AButtonPosition = AButtonPosition.South,
) {
  /** The action [button] performs, or null if it does nothing. */
  fun actionFor(button: PadButton): Action? =
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
      PadButton.Y,
      PadButton.Select,
      PadButton.L2,
      PadButton.R2,
      PadButton.ThumbLeft,
      PadButton.ThumbRight -> null
    }
}
