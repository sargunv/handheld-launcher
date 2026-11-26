package dev.sargunv.handheld.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.FileCredentialStore
import dev.sargunv.handheld.data.auth.installIdentifier
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.settings.SettingsFile
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Runs the app in one window. By default the window shows both of the Thor's screens in a frame;
 * `--folded` shows the single-screen layout instead.
 *
 * It keeps the signed-in server's credentials, unencrypted, and the library cache under
 * `$XDG_DATA_HOME/handheld` (`~/.local/share/handheld` by default), and covers under
 * `$XDG_CACHE_HOME/handheld/covers` (`~/.cache/handheld/covers` by default).
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
    val dataDir = remember {
      Path(
        System.getenv("XDG_DATA_HOME")
          ?: Path(System.getProperty("user.home"), ".local", "share").toString(),
        "handheld",
      )
    }
    val credentials = remember { FileCredentialStore(Path(dataDir, "credentials.json")) }
    val library = remember {
      SystemFileSystem.createDirectories(dataDir)
      LibraryCache.open(Path(dataDir, "library.db").toString())
    }
    val device = remember {
      val os = System.getProperty("os.name")
      DeviceDescription(installIdentifier(Path(dataDir, "install-id")), os, os.lowercase(), null)
    }
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
        credentials = credentials,
        device = device,
        library = library,
        coverCacheDir = cacheDir(),
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

/** Where fetched covers are kept: under the XDG cache directory. */
private fun cacheDir(): Path {
  val home = System.getProperty("user.home")
  val dir = System.getenv("XDG_CACHE_HOME")?.ifEmpty { null } ?: "$home/.cache"
  return Path(dir, "handheld", "covers")
}
