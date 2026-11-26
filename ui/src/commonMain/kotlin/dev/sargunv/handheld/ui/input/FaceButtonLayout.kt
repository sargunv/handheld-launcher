package dev.sargunv.handheld.ui.input

/** Where a face button sits in the diamond. */
enum class FacePosition {
  North,
  South,
  East,
  West,
}

/**
 * Which face button sits where. A handheld's own layout setting decides which physical button sends
 * which key code. The app's navigation follows the key codes, so it needs the layout only where
 * position matters: the built-in session's RetroPad, which is positional, and showing the buttons
 * where they sit.
 */
enum class FaceButtonLayout {
  /** A south, B east, X west, Y north: Android's documented default. */
  Xbox,

  /** A east, B south, X north, Y west. */
  Nintendo;

  /** The face button at [position]. */
  fun buttonAt(position: FacePosition): PadButton =
    when (this) {
      Xbox ->
        when (position) {
          FacePosition.South -> PadButton.A
          FacePosition.East -> PadButton.B
          FacePosition.West -> PadButton.X
          FacePosition.North -> PadButton.Y
        }
      Nintendo ->
        when (position) {
          FacePosition.South -> PadButton.B
          FacePosition.East -> PadButton.A
          FacePosition.West -> PadButton.Y
          FacePosition.North -> PadButton.X
        }
    }

  /** Where [button] sits, or null if it isn't a face button. */
  fun positionOf(button: PadButton): FacePosition? =
    FacePosition.entries.firstOrNull { buttonAt(it) == button }
}
