package dev.sargunv.handheld.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import dev.sargunv.handheld.ui.demo.DemoPrimary
import dev.sargunv.handheld.ui.demo.DemoSecondary
import dev.sargunv.handheld.ui.demo.DemoState
import dev.sargunv.handheld.ui.dualscreen.DualScreen
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.input.ActionInputRoot
import dev.sargunv.handheld.ui.input.HeldKeys
import kotlinx.coroutines.flow.drop

/**
 * The app's root, shared by every platform.
 *
 * @param layout how the two screens' content shares the hardware.
 * @param heldKeys the main window's held keys, which the platform keeps up to date.
 * @param initialSettings the stored settings, which the app reads once, when it first composes.
 * @param saveSettings stores the settings each time they change, in the order they changed.
 * @param secondaryDisplay shows its content on the second display. Only called when [layout] is
 *   [DualScreenLayout.Separate].
 */
@Composable
fun App(
  layout: DualScreenLayout,
  heldKeys: HeldKeys,
  modifier: Modifier = Modifier,
  initialSettings: Settings = Settings(),
  saveSettings: suspend (Settings) -> Unit = {},
  secondaryDisplay: @Composable (content: @Composable () -> Unit) -> Unit = {},
) {
  val state = remember { DemoState(initialSettings) }
  val currentSaveSettings by rememberUpdatedState(saveSettings)
  LaunchedEffect(state) {
    snapshotFlow { state.settings }.drop(1).collect { currentSaveSettings(it) }
  }
  ActionInputRoot(state.inputSettings, heldKeys, modifier) {
    FocusRoot {
      DualScreen(
        layout = layout,
        primary = { DemoPrimary(state) },
        secondary = { DemoSecondary(state) },
      )
    }
  }
  if (layout == DualScreenLayout.Separate) secondaryDisplay { DemoSecondary(state) }
}
