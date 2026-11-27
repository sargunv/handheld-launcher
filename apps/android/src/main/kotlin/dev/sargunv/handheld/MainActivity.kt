package dev.sargunv.handheld

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
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
import androidx.lifecycle.withStarted
import dev.sargunv.handheld.achievements.retroAchievementsSignIn
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.installIdentifier
import dev.sargunv.handheld.launch.AndroidGameLauncher
import dev.sargunv.handheld.launch.BuiltInPlayer
import dev.sargunv.handheld.launch.PlayCompanion
import dev.sargunv.handheld.launch.PlaySaves
import dev.sargunv.handheld.launch.SessionRomHeaders
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.AppPlatform
import dev.sargunv.handheld.ui.about.AppInfo
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.places.OpenTarget
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

class MainActivity : ComponentActivity() {
  private val heldKeys = HeldKeys()

  private val gamepadMotion by lazy { GamepadMotion(lifecycleScope, ::dispatchKeyEvent) }

  /** A layout forced from the launch intent, for development on single-display devices. */
  private var forcedLayout by mutableStateOf<DualScreenLayout?>(null)

  /** The screens to show, from launch intents, for development tools. */
  private val openRequests = Channel<OpenTarget>(Channel.CONFLATED)

  /** The presses of HOME while the launcher was in front. */
  private val homePresses = Channel<Unit>(Channel.CONFLATED)

  private var player: AndroidGameLauncher? = null

  private val device by lazy {
    DeviceDescription(
      identifier = installIdentifier(Path(filesDir.path, "install-id")),
      name =
        if (Build.MODEL.startsWith(Build.MANUFACTURER)) Build.MODEL
        else "${Build.MANUFACTURER} ${Build.MODEL}",
      platform = "android",
      appVersion = handheldApp.versionName,
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The system keeps the home app's launcher in a home task of its own, apart from the task an
    // explicit launch starts, so one launcher could run in each. The one in the home task stays.
    if (isHomeIntent(intent.action, intent.categories)) {
      // One that ran before the app became the home app closes.
      for (other in running.toList()) other.finishAndRemoveTask()
    } else if (isDefaultHome()) {
      // A launch from another launcher or a tool goes to the one in the home task.
      startActivity(launcherIntent().putExtras(intent))
      finishAndRemoveTask()
      return
    }
    running += this
    forcedLayout = forcedLayout(intent)
    // A recreated activity has already shown the screen its intent asked for.
    if (savedInstanceState == null) openTarget(intent)?.let(openRequests::trySend)
    getSystemService(InputManager::class.java).registerInputDeviceListener(gamepadMotion, null)
    val app = handheldApp
    val settings = app.settingsFile.read()
    keepDownloadsRunning()
    val saves = PlaySaves(app.data.saveSync)
    val launcher =
      AndroidGameLauncher(
          activity = this,
          romm = app.data.romm,
          saves = saves,
          builtIn = BuiltInPlayer(this, saves, app.data.builtInSaveFolders.saves, app.settingsFile),
          emulators = app.emulators,
          allFilesAccess = app.allFilesAccess,
          storageVolumes = { app.storageTargets().map { it.root } },
        )
        .also { player = it }
    val retroAchievements = retroAchievementsSignIn(this)
    // About reads the list of components that the build puts in the app's resources.
    val appInfo =
      AppInfo(app.versionName) {
        resources.openRawResource(R.raw.aboutlibraries).use { it.readBytes().decodeToString() }
      }
    val platform =
      AppPlatform(
        device = device,
        launcher = launcher,
        apps = InstalledApps(this),
        romHeaders = SessionRomHeaders,
        retroAchievements = retroAchievements,
        appInfo = appInfo,
        gameState = app.data.builtInSaveFolders,
      )
    val opens = openRequests.receiveAsFlow()
    val homes = homePresses.receiveAsFlow()
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
        data = app.data,
        platform = platform,
        coverCacheDir = Path(cacheDir.path, "covers"),
        initialSettings = settings,
        saveSettings = app.settingsFile::write,
        secondaryDisplay = { content ->
          if (usableDisplay != null && !companionShows) {
            SecondaryDisplayContent(
              display = usableDisplay,
              onFailed = { failedDisplayId = usableDisplay.displayId },
              content = content,
            )
          }
        },
        openRequests = opens,
        homePresses = homes,
      )
    }
  }

  override fun onDestroy() {
    running -= this
    getSystemService(InputManager::class.java).unregisterInputDeviceListener(gamepadMotion)
    super.onDestroy()
  }

  override fun onResume() {
    super.onResume()
    // The user may be back from granting all-files access, which queued downloads wait for.
    handheldApp.allFilesAccess.value = Environment.isExternalStorageManager()
    // Games may have been copied onto the device, or deleted, while the app was away.
    lifecycleScope.launch { handheldApp.data.games.rescan() }
  }

  override fun onNewIntent(intent: Intent) {
    // Read before anything else: focus says whether the launcher was in front when the intent came.
    val wasInFront = hasWindowFocus()
    super.onNewIntent(intent)
    // The second display's home only hands the controller back, and the launcher stays as it was.
    if (intent.getBooleanExtra(EXTRA_TAKE_FOCUS, false)) return
    setIntent(intent)
    val home = isHomeIntent(intent.action, intent.categories)
    // HOME keeps the layout a development launch forced, unless it carries one from such a launch.
    if (!home || intent.hasExtra("layout")) forcedLayout = forcedLayout(intent)
    when (
      val response =
        respondTo(
          home = home,
          open = openTarget(intent),
          wasInFront = wasInFront,
          playing = player?.isPlaying == true,
        )
    ) {
      is IntentResponse.Open -> openRequests.trySend(response.target)
      IntentResponse.PressHome -> homePresses.trySend(Unit)
      IntentResponse.Show -> Unit
    }
  }

  /**
   * Starts [DownloadService] when the queue gets something to download, so the queue goes on in the
   * background. It waits for the activity to show, since an app in the background can't start one.
   * While the queue has work, it asks once for all-files access, which writing games needs, and
   * once that's granted, to show the download's progress in a notification.
   */
  private fun keepDownloadsRunning() {
    val hasWork = handheldApp.data.downloads.queue.map { queue -> queue.any { it.isActive } }
    lifecycleScope.launch {
      combine(hasWork, handheldApp.allFilesAccess, ::Pair)
        .distinctUntilChanged()
        .filter { it.first }
        .collect { (_, allFilesAccess) ->
          lifecycle.withStarted {
            // Games go to shared storage, where the app writes only with all-files access.
            if (!allFilesAccess) {
              startActivity(allFilesAccessSettings())
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
    gamepadMotion.onGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus) gamepadMotion.release()
  }

  /**
   * Reads the `layout` extra (`frame` or `folded`), so a device with one display can show the
   * dual-screen frame: `am start -n ... --es layout frame`.
   */
  private fun forcedLayout(intent: Intent): DualScreenLayout? =
    when (intent.getStringExtra("layout")) {
      "frame" -> DualScreenLayout.Frame
      "folded" -> DualScreenLayout.Folded
      else -> null
    }

  /**
   * Reads the `open` extra, which shows a screen straight away, such as `--es open place:downloads`
   * or `--es open game:42`. See [OpenTarget.parse] for the targets.
   */
  private fun openTarget(intent: Intent): OpenTarget? =
    intent.getStringExtra("open")?.let(OpenTarget::parse)
}

/**
 * Shows the test tags of the content below as resource ids to accessibility services and to
 * `uiautomator dump`, so tools can tell the elements apart. Each window's content needs its own.
 */
internal fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }

/** The launchers this process shows, at most one but for a moment as the home app takes over. */
private val running = mutableSetOf<MainActivity>()

/** Whether a launcher is resumed, which makes it what the first display shows. */
internal fun isLauncherResumed(): Boolean = running.any {
  it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
}
