package dev.sargunv.handheld

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.places.OpenTarget
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val heldKeys = HeldKeys()

  private val gamepadMotion by lazy { GamepadMotion(lifecycleScope, ::dispatchKeyEvent) }

  /** A layout forced from the launch intent, for development on single-display devices. */
  private var forcedLayout by mutableStateOf<DualScreenLayout?>(null)

  /** The screens to show, from launch intents, for development tools. */
  private val openRequests = Channel<OpenTarget>(Channel.CONFLATED)

  /** The presses of HOME while the launcher was in front. */
  private val homePresses = Channel<Unit>(Channel.CONFLATED)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The system keeps the home app's launcher in a home task of its own, apart from the task an
    // explicit launch starts, so one launcher could run in each. The one in the home task stays.
    if (intent.isHome()) {
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
    BackgroundWorkService.follow(this)
    askForWorkPermissions()
    val app = handheldApp
    val platform = app.platform(this)
    val opens = openRequests.receiveAsFlow()
    val homes = homePresses.receiveAsFlow()
    setContent {
      val secondary = rememberSecondaryDisplay()
      // The presentation draws over the play companion's activity on the same display.
      val companionShows = app.plays.companion.collectAsState().value != null
      App(
        modifier = Modifier.exposeTestTags(),
        layout =
          forcedLayout
            ?: if (secondary.display != null) DualScreenLayout.Separate
            else DualScreenLayout.Folded,
        heldKeys = heldKeys,
        data = app.data,
        platform = platform,
        coverCacheDir = File(cacheDir, "covers"),
        secondaryDisplay = { content -> if (!companionShows) secondary.Show(content) },
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
    handheldApp.launcherInFront.value = true
    // The user may be back from granting all-files access, which queued downloads wait for.
    handheldApp.refreshAllFilesAccess()
    // Games may have been copied onto the device, or deleted, while the app was away.
    lifecycleScope.launch { handheldApp.data.games.rescan() }
  }

  override fun onPause() {
    handheldApp.launcherInFront.value = false
    super.onPause()
  }

  override fun onNewIntent(intent: Intent) {
    // Read before anything else: focus says whether the launcher was in front when the intent came.
    val wasInFront = hasWindowFocus()
    super.onNewIntent(intent)
    // The second display's home only hands the controller back, and the launcher stays as it was.
    if (intent.getBooleanExtra(EXTRA_TAKE_FOCUS, false)) return
    setIntent(intent)
    if (intent.hasExtra("layout")) forcedLayout = forcedLayout(intent)
    // HOME is a press in the app only when the launcher was in front, the way launchers start over
    // on their first page. From another app it only brings the launcher back as it was.
    val open = openTarget(intent)
    if (open != null) openRequests.trySend(open)
    else if (intent.isHome() && wasInFront) homePresses.trySend(Unit)
  }

  /**
   * Asks, once the queue gets something to download or an upload starts, for all-files access,
   * which writing games to shared storage needs, and once that's granted, to show the progress in a
   * notification. It waits for the activity to show.
   */
  private fun askForWorkPermissions() {
    val data = handheldApp.data
    lifecycleScope.launch {
      combine(data.downloads.queue, data.uploads.upload, handheldApp.allFilesAccess) {
          queue,
          upload,
          allFilesAccess ->
          (queue.any { !it.failed } || upload?.isRunning == true) to allFilesAccess
        }
        .distinctUntilChanged()
        .filter { (hasWork) -> hasWork }
        .collect { (_, allFilesAccess) ->
          lifecycle.withStarted {
            if (!allFilesAccess) {
              handheldApp.openAllFilesAccessSettings(this@MainActivity)
            } else if (
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                  PackageManager.PERMISSION_GRANTED
            ) {
              requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
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
