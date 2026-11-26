package dev.sargunv.handheld

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.installIdentifier
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DownloadState
import dev.sargunv.handheld.launch.LaunchDispatcher
import dev.sargunv.handheld.launch.StandalonePlayer
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

class MainActivity : ComponentActivity() {
  private val heldKeys = HeldKeys()

  private val gamepadMotion by lazy { GamepadMotion(lifecycleScope, ::dispatchKeyEvent) }

  /** A layout forced from the launch intent, for development on single-display devices. */
  private var forcedLayout by mutableStateOf<DualScreenLayout?>(null)

  private var showsDebugScreen = false

  private val credentials by lazy { credentialStore(this) }

  private val device by lazy {
    DeviceDescription(
      identifier = installIdentifier(Path(filesDir.path, "install-id")),
      name =
        if (Build.MODEL.startsWith(Build.MANUFACTURER)) Build.MODEL
        else "${Build.MANUFACTURER} ${Build.MODEL}",
      platform = "android",
      appVersion = packageManager.getPackageInfo(packageName, 0).versionName,
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val debugScreen = debugScreen(this)
    if (debugScreen != null) {
      // A debug screen takes the controller's input as it comes, without the launcher's mapping.
      showsDebugScreen = true
      setContent { debugScreen() }
      return
    }
    forcedLayout = forcedLayout(intent)
    getSystemService(InputManager::class.java).registerInputDeviceListener(gamepadMotion, null)
    val settingsFile = settingsFile(this)
    val settings = settingsFile.read()
    val downloads = downloadManager(this)
    keepDownloadsRunning()
    val launcher = StandalonePlayer(this, credentials)
    setContent {
      val display = rememberSecondaryDisplay()
      var failedDisplayId by remember { mutableStateOf<Int?>(null) }
      val usableDisplay = display?.takeIf { it.displayId != failedDisplayId }
      val layout =
        forcedLayout
          ?: if (usableDisplay != null) DualScreenLayout.Separate else DualScreenLayout.Folded
      App(
        layout = layout,
        heldKeys = heldKeys,
        credentials = credentials,
        device = device,
        library = libraryCache(this),
        downloads = downloads,
        coverCacheDir = Path(cacheDir.path, "covers"),
        initialSettings = settings,
        saveSettings = settingsFile::write,
        launcher = launcher,
        secondaryDisplay = { content ->
          if (usableDisplay != null) {
            SecondaryDisplayContent(
              display = usableDisplay,
              onFailed = { failedDisplayId = usableDisplay.displayId },
              content = content,
            )
          }
        },
      )
    }
  }

  override fun onDestroy() {
    if (!showsDebugScreen) {
      getSystemService(InputManager::class.java).unregisterInputDeviceListener(gamepadMotion)
    }
    super.onDestroy()
  }

  override fun onResume() {
    super.onResume()
    // The user may be back from granting all-files access, which queued downloads wait for.
    allFilesAccess.value = Environment.isExternalStorageManager()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    // The activity picks a debug screen when it's created, so a launch that opens, leaves or
    // changes one recreates it. That also closes a game session the old screen was running.
    if (showsDebugScreen || debugScreen(this) != null) {
      recreate()
    } else {
      forcedLayout = forcedLayout(intent)
    }
  }

  /**
   * Starts [DownloadService] whenever the activity shows and there's something to download, so the
   * queue goes on in the background. An app in the background can't start one. It asks for
   * all-files access, which writing games needs, and to show the download's progress in a
   * notification, when it lacks them.
   */
  private fun keepDownloadsRunning() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        downloadManager(this@MainActivity)
          .queue
          .map { queue -> queue.any { it.state != DownloadState.Failed } }
          .distinctUntilChanged()
          .collect { active ->
            if (!active) return@collect
            // Games go to shared storage, where the app writes only with all-files access.
            if (!Environment.isExternalStorageManager()) {
              startActivity(LaunchDispatcher(this@MainActivity).allFilesAccessSettings())
            } else if (
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                  PackageManager.PERMISSION_GRANTED
            ) {
              requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
            DownloadService.start(this@MainActivity)
          }
      }
    }
  }

  // The window sees every key, even while nothing in its content has focus. Activity's
  // dispatchKeyEvent is public; lint objects only because androidx.core's override is restricted.
  @SuppressLint("RestrictedApi")
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    heldKeys.track(ComposeKeyEvent(event))
    return super.dispatchKeyEvent(event)
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
    (!showsDebugScreen && gamepadMotion.onGenericMotionEvent(event)) ||
      super.dispatchGenericMotionEvent(event)

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus && !showsDebugScreen) gamepadMotion.release()
  }

  /**
   * Reads the `layout` extra (`frame` or `folded`) that debuggable builds accept, so a device with
   * one display can show the dual-screen frame: `am start -n ... --es layout frame`.
   */
  private fun forcedLayout(intent: Intent): DualScreenLayout? {
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
    return when (intent.getStringExtra("layout")) {
      "frame" -> DualScreenLayout.Frame
      "folded" -> DualScreenLayout.Folded
      else -> null
    }
  }
}

private var openLibrary: LibraryCache? = null

/**
 * The library cache, in the app's databases directory. It stays open for the life of the process,
 * so an activity that's recreated doesn't open a second connection to it.
 */
@Synchronized
internal fun libraryCache(context: Context): LibraryCache =
  openLibrary
    ?: LibraryCache.open(
        context.getDatabasePath("library.db").also { it.parentFile?.mkdirs() }.path,
      )
      .also { openLibrary = it }
