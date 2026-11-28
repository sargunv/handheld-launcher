package dev.sargunv.handheld.ui.input

import dev.sargunv.handheld.data.settings.AButtonPosition

/** Where a face button sits in the diamond. */
enum class FacePosition {
  North,
  South,
  East,
  West,
}

/** The face button at [position], with the A button here. */
fun AButtonPosition.buttonAt(position: FacePosition): PadButton =
  when (this) {
    AButtonPosition.South ->
      when (position) {
        FacePosition.South -> PadButton.A
        FacePosition.East -> PadButton.B
        FacePosition.West -> PadButton.X
        FacePosition.North -> PadButton.Y
      }
    AButtonPosition.East ->
      when (position) {
        FacePosition.South -> PadButton.B
        FacePosition.East -> PadButton.A
        FacePosition.West -> PadButton.Y
        FacePosition.North -> PadButton.X
      }
  }
