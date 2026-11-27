package dev.sargunv.handheld.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.FileCredentialStore
import dev.sargunv.handheld.data.auth.installIdentifier
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.data.settings.SettingsFile
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.AppPlatform
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.about.AppInfo
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Runs the app in one window: the Thor's two screens in a frame, or with `--folded` the
 * single-screen layout. Its files go in the XDG base directories, and the credentials unencrypted.
 */
fun main(args: Array<String>) {
  val folded = "--folded" in args
  val dataDir = xdgDir("XDG_DATA_HOME", ".local/share")
  val configDir = xdgDir("XDG_CONFIG_HOME", ".config")
  val cacheDir = xdgDir("XDG_CACHE_HOME", ".cache")
  SystemFileSystem.createDirectories(dataDir)
  val settingsFile =
    SettingsFile(Path(configDir, "settings.json"), Settings.serializer(), Settings())
  val data =
    HandheldData(
      credentials = FileCredentialStore(Path(dataDir, "credentials.json")),
      library = LibraryCache.open(Path(dataDir, "library.db").toString()),
      dataDir = dataDir,
      cacheDir = cacheDir,
      storage = { listOf(StorageTarget(dataDir.toString(), "Data folder")) },
      scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
      storageSettings = { settingsFile.read().storage },
    )
  val os = System.getProperty("os.name")
  val device =
    DeviceDescription(installIdentifier(Path(dataDir, "install-id")), os, os.lowercase(), null)
  // The build passes the version and puts the list of components in the resources.
  val appInfo =
    AppInfo(System.getProperty("handheld.version") ?: "unknown") {
      ClassLoader.getSystemResourceAsStream("aboutlibraries.json")?.use {
        it.readBytes().decodeToString()
      }
    }
  val platform = AppPlatform(device, appInfo = appInfo)
  val heldKeys = HeldKeys()
  val settings = settingsFile.read()
  application {
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
        data = data,
        platform = platform,
        coverCacheDir = Path(cacheDir, "covers"),
        initialSettings = settings,
        saveSettings = settingsFile::write,
      )
    }
  }
}

/** The app's folder in the XDG base directory [variable] names, or in [default] under home. */
private fun xdgDir(variable: String, default: String): Path {
  val base =
    System.getenv(variable)?.ifEmpty { null } ?: "${System.getProperty("user.home")}/$default"
  return Path(base, "handheld")
}
