package dev.sargunv.handheld.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.sargunv.handheld.ui.demo.DemoPrimary
import dev.sargunv.handheld.ui.demo.DemoSecondary
import dev.sargunv.handheld.ui.demo.DemoState
import dev.sargunv.handheld.ui.dualscreen.DualScreen
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.input.ActionInputRoot

/**
 * The app's root, shared by every platform.
 *
 * @param layout how the two screens' content shares the hardware.
 * @param secondaryDisplay shows its content on the second display. Only called when [layout] is
 *   [DualScreenLayout.Separate].
 */
@Composable
fun App(
  layout: DualScreenLayout,
  modifier: Modifier = Modifier,
  secondaryDisplay: @Composable (content: @Composable () -> Unit) -> Unit = {},
) {
  val state = remember { DemoState() }
  ActionInputRoot(state.inputSettings, modifier) {
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
