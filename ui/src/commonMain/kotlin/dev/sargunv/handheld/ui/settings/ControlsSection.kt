package dev.sargunv.handheld.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import dev.sargunv.handheld.ui.input.AButtonPosition
import dev.sargunv.handheld.ui.input.ConfirmButton
import dev.sargunv.handheld.ui.input.PadButton
import dev.sargunv.handheld.ui.input.label
import dev.sargunv.handheld.ui.places.AppState
import dev.sargunv.handheld.ui.signin.Help

internal fun LazyListScope.controls(state: AppState) {
  sectionHeading("Controls")
  item(key = "confirm") {
    val confirm =
      when (state.inputSettings.confirmButton) {
        ConfirmButton.A -> PadButton.A
        ConfirmButton.B -> PadButton.B
      }
    SettingRow(
      state.settingsView,
      "settings:confirm",
      "Confirm button",
      confirm.label,
      Help("Confirm button", "Which face button confirms. The other one goes back."),
      onConfirm = state::swapConfirmButton,
    )
  }
  item(key = "aButton") {
    val position =
      when (state.inputSettings.aButtonPosition) {
        AButtonPosition.South -> "Bottom"
        AButtonPosition.East -> "Right"
      }
    SettingRow(
      state.settingsView,
      "settings:aButton",
      "A button position",
      position,
      Help(
        "A button position",
        "Where this device's A button sits, so games in Built-in get the button they expect " +
          "in each place.",
      ),
      onConfirm = state::switchAButtonPosition,
    )
  }
}
