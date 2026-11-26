package dev.sargunv.handheld.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sargunv.handheld.data.settings.SettingsFile
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import kotlinx.io.files.Path

/**
 * Runs the app in one window. By default the window shows both of the Thor's screens in a frame;
 * `--folded` shows the single-screen layout instead.
 *
 * Input comes from the keyboard for now. A gamepad source (SDL, through the session crate) can feed
 * the same pipeline by dispatching key events that carry Compose's `Key.Button*` codes to the
 * window.
 */
fun main(args: Array<String>) {
  val folded = "--folded" in args
  val settingsFile = SettingsFile(settingsPath(), Settings.serializer(), Settings())
  val settings = settingsFile.read()
  application {
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
      App(
        layout = if (folded) DualScreenLayout.Folded else DualScreenLayout.Frame,
        heldKeys = heldKeys,
        initialSettings = settings,
        saveSettings = settingsFile::write,
      )
    }
  }
}

/**
 * Where the settings live: the user's Application Support directory on macOS, and the XDG config
 * directory elsewhere.
 */
private fun settingsPath(): Path {
  val home = System.getProperty("user.home")
  val dir =
    if (System.getProperty("os.name").startsWith("Mac"))
      "$home/Library/Application Support/Handheld"
    else "${System.getenv("XDG_CONFIG_HOME")?.ifEmpty { null } ?: "$home/.config"}/handheld"
  return Path(dir, "settings.json")
}
