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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.installIdentifier
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.downloads.DownloadState
import dev.sargunv.handheld.launch.LaunchDispatcher
import dev.sargunv.handheld.launch.PlayCompanion
import dev.sargunv.handheld.launch.StandalonePlayer
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.places.OpenRequest
import dev.sargunv.handheld.ui.places.OpenTarget
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

class MainActivity : ComponentActivity() {
  private val heldKeys = HeldKeys()

  private val gamepadMotion by lazy { GamepadMotion(lifecycleScope, ::dispatchKeyEvent) }

  /** A layout forced from the launch intent, for development on single-display devices. */
  private var forcedLayout by mutableStateOf<DualScreenLayout?>(null)

  /** A screen to show, from the launch intent, for development tools. */
  private var openRequest by mutableStateOf<OpenRequest?>(null)

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
    // A recreated activity has already shown the screen its intent asked for.
    if (savedInstanceState == null) openRequest = openRequest(intent)
    getSystemService(InputManager::class.java).registerInputDeviceListener(gamepadMotion, null)
    val settingsFile = settingsFile(this)
    val settings = settingsFile.read()
    val downloads = downloadManager(this)
    keepDownloadsRunning()
    val launcher = StandalonePlayer(this, credentials)
    // Made once: App keeps its state per catalog, and this block recomposes when a game starts.
    val apps = InstalledApps(this)
    setContent {
      val display = rememberSecondaryDisplay()
      // The presentation draws over the play companion's activity on the same display.
      val companionShows = PlayCompanion.session.collectAsState().value != null
      var failedDisplayId by remember { mutableStateOf<Int?>(null) }
      val usableDisplay = display?.takeIf { it.displayId != failedDisplayId }
      val layout =
        forcedLayout
          ?: if (usableDisplay != null) DualScreenLayout.Separate else DualScreenLayout.Folded
      App(
        modifier = Modifier.exposeTestTags(),
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
        apps = apps,
        secondaryDisplay = { content ->
          if (usableDisplay != null && !companionShows) {
            SecondaryDisplayContent(
              display = usableDisplay,
              onFailed = { failedDisplayId = usableDisplay.displayId },
              content = content,
            )
          }
        },
        openRequest = openRequest,
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
      openRequest(intent)?.let { openRequest = it }
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
    if (!isDebuggable) return null
    return when (intent.getStringExtra("layout")) {
      "frame" -> DualScreenLayout.Frame
      "folded" -> DualScreenLayout.Folded
      else -> null
    }
  }

  /**
   * Reads the `open` extra that debuggable builds accept, which shows a screen straight away, such
   * as `--es open place:downloads`, `--es open game:42` or `--es open library:gba`. See
   * [OpenTarget.parse] for the targets.
   */
  private fun openRequest(intent: Intent): OpenRequest? {
    if (!isDebuggable) return null
    return intent.getStringExtra("open")?.let(OpenTarget::parse)?.let(::OpenRequest)
  }

  private val isDebuggable: Boolean
    get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

/**
 * Shows the test tags of the content below as resource ids to accessibility services and to
 * `uiautomator dump`, so tools can tell the elements apart. Each window's content needs its own.
 */
internal fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }

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
