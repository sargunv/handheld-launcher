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
  Search,
  PreviousShelf,
  NextShelf,
  /** Opens the quick menu (the Start button). */
  QuickMenu,
  Select;

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
  Right;

  val action: Action
    get() =
      when (this) {
        Up -> Action.Up
        Down -> Action.Down
        Left -> Action.Left
        Right -> Action.Right
      }
}

/**
 * A gamepad button, named by where it sits rather than by its label, because handhelds disagree on
 * which face button says "A".
 */
enum class PadButton {
  South,
  East,
  West,
  North,
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
enum class ConfirmButton {
  South,
  East,
}

/** The player's input preferences. */
@Serializable
data class InputSettings(val confirmButton: ConfirmButton = ConfirmButton.South) {
  /** The action [button] performs, or null if it does nothing. */
  fun actionFor(button: PadButton): Action? =
    when (button) {
      PadButton.South -> if (confirmButton == ConfirmButton.South) Action.Confirm else Action.Back
      PadButton.East -> if (confirmButton == ConfirmButton.East) Action.Confirm else Action.Back
      PadButton.West -> Action.Menu
      PadButton.North -> Action.Search
      PadButton.L1 -> Action.PreviousShelf
      PadButton.R1 -> Action.NextShelf
      PadButton.Start -> Action.QuickMenu
      PadButton.Select -> Action.Select
      PadButton.DpadUp -> Action.Up
      PadButton.DpadDown -> Action.Down
      PadButton.DpadLeft -> Action.Left
      PadButton.DpadRight -> Action.Right
      PadButton.L2,
      PadButton.R2,
      PadButton.ThumbLeft,
      PadButton.ThumbRight -> null
    }
}
