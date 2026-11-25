package dev.sargunv.handheld.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys

/**
 * Runs the app in one window. By default the window shows both of the Thor's screens in a frame;
 * `--folded` shows the single-screen layout instead.
 *
 * Input comes from the keyboard for now. A gamepad source (SDL, through the session crate) can feed
 * the same pipeline by dispatching key events that carry Compose's `Key.Button*` codes to the
 * window.
 */
fun main(args: Array<String>) = application {
  val folded = "--folded" in args
  val heldKeys = remember { HeldKeys() }
  Window(
    onCloseRequest = ::exitApplication,
    // The window sees every key, even while nothing in its content has focus.
    onPreviewKeyEvent = {
      heldKeys.track(it)
      false
    },
    title = "Handheld",
    state =
      rememberWindowState(
        width = if (folded) 1280.dp else 720.dp,
        height = if (folded) 720.dp else 840.dp,
      ),
  ) {
    App(if (folded) DualScreenLayout.Folded else DualScreenLayout.Frame, heldKeys)
  }
}
