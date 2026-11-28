package dev.sargunv.handheld.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sargunv.handheld.data.HandheldData
import dev.sargunv.handheld.data.JsonFile
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.auth.installIdentifier
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.StorageTarget
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.AppPlatform
import dev.sargunv.handheld.ui.about.AppInfo
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Runs the app in one window: the Thor's two screens in a frame, or with `HANDHELD_LAYOUT=folded`
 * the single-screen layout. Its files go in the XDG base directories, and the credentials
 * unencrypted.
 */
fun main() {
  val folded = System.getenv("HANDHELD_LAYOUT") == "folded"
  val dataDir = xdgDir("XDG_DATA_HOME", ".local/share")
  val configDir = xdgDir("XDG_CONFIG_HOME", ".config")
  val cacheDir = xdgDir("XDG_CACHE_HOME", ".cache")
  dataDir.mkdirs()
  val data =
    HandheldData(
      credentials = JsonFile(File(dataDir, "credentials.json"), RommCredentials.serializer()),
      library = LibraryCache.open(File(dataDir, "library.db").path),
      dataDir = dataDir,
      cacheDir = cacheDir,
      settingsFile = File(configDir, "settings.json"),
      storage = { listOf(StorageTarget(dataDir.path, "Data folder")) },
      scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
  // The build passes the version and puts the list of components in the resources.
  val version = System.getProperty("handheld.version") ?: "unknown"
  val os = System.getProperty("os.name")
  val device =
    DeviceDescription(installIdentifier(File(dataDir, "install-id")), os, os.lowercase(), version)
  val appInfo =
    AppInfo(version) {
      ClassLoader.getSystemResourceAsStream("aboutlibraries.json")?.use {
        it.readBytes().decodeToString()
      }
    }
  val platform = AppPlatform(device, appInfo = appInfo)
  val heldKeys = HeldKeys()
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
        coverCacheDir = File(cacheDir, "covers"),
      )
    }
  }
}

/** The app's folder in the XDG base directory [variable] names, or in [default] under home. */
private fun xdgDir(variable: String, default: String): File {
  val base =
    System.getenv(variable)?.ifEmpty { null } ?: "${System.getProperty("user.home")}/$default"
  return File(base, "handheld")
}
