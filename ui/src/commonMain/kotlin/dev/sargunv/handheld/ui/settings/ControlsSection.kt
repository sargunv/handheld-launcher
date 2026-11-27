package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import dev.sargunv.handheld.ui.input.AButtonPosition
import dev.sargunv.handheld.ui.input.ConfirmButton
import dev.sargunv.handheld.ui.input.PadButton
import dev.sargunv.handheld.ui.input.label
import dev.sargunv.handheld.ui.places.AppState

internal fun LazyListScope.controls(state: AppState) {
  sectionHeading("Controls")
  item(key = "confirm") {
    val input = state.settings.input
    val (confirm, other) =
      when (input.confirmButton) {
        ConfirmButton.A -> PadButton.A to ConfirmButton.B
        ConfirmButton.B -> PadButton.B to ConfirmButton.A
      }
    SettingRow(
      state.settingsView,
      "settings:confirm",
      "Confirm button",
      confirm.label,
      "The other face button goes back",
      onConfirm = {
        state.settings = state.settings.copy(input = input.copy(confirmButton = other))
      },
    )
  }
  item(key = "aButton") {
    val input = state.settings.input
    val (position, other) =
      when (input.aButtonPosition) {
        AButtonPosition.South -> "Bottom" to AButtonPosition.East
        AButtonPosition.East -> "Right" to AButtonPosition.South
      }
    SettingRow(
      state.settingsView,
      "settings:aButton",
      "A button position",
      position,
      "Where the A button sits on this device",
      onConfirm = {
        state.settings = state.settings.copy(input = input.copy(aButtonPosition = other))
      },
    )
  }
}
