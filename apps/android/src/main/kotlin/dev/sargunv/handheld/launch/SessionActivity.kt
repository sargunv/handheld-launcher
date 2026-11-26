package dev.sargunv.handheld.launch

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.sargunv.handheld.SecondaryDisplayContent
import dev.sargunv.handheld.achievements.SessionAchievementsHttp
import dev.sargunv.handheld.achievements.retroAchievementsClient
import dev.sargunv.handheld.achievements.retroAchievementsStore
import dev.sargunv.handheld.data.achievements.RetroAchievementsClient
import dev.sargunv.handheld.exposeTestTags
import dev.sargunv.handheld.rememberSecondaryDisplay
import dev.sargunv.handheld.secondaryDisplay
import dev.sargunv.handheld.session.AchievementEvent
import dev.sargunv.handheld.session.AchievementOptions
import dev.sargunv.handheld.session.GameSession
import dev.sargunv.handheld.session.GameSessionView
import dev.sargunv.handheld.session.LowerScreenView
import dev.sargunv.handheld.session.SessionException
import dev.sargunv.handheld.session.SessionInfo
import dev.sargunv.handheld.session.SessionListener
import dev.sargunv.handheld.session.SessionOptions
import dev.sargunv.handheld.session.VideoMode
import dev.sargunv.handheld.settingsFile
import dev.sargunv.handheld.ui.dualscreen.DualScreen
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.input.AButtonPosition
import dev.sargunv.handheld.ui.input.ActionInputRoot
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.InputSettings
import dev.sargunv.handheld.ui.play.QuickMenu
import dev.sargunv.handheld.ui.play.QuickMenuState
import dev.sargunv.handheld.ui.play.StateSlotsPanel
import dev.sargunv.handheld.ui.play.Toast
import dev.sargunv.handheld.ui.play.ToastHost
import dev.sargunv.handheld.ui.play.ToastState
import dev.sargunv.handheld.ui.play.unlockedToast
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A game in the built-in session, full screen. Back, or a controller's guide button, pauses it and
 * opens the quick menu over it, with the save-state slots on the second screen, while every game
 * button, Start included, goes to the game. It closes when the core quits on its own, on Quit in
 * the quick menu, on back before the game has started, or on HOME, which brings the launcher back,
 * and the session's save memory is written as it closes. A session that can't start closes it with
 * the reason as its result's [EXTRA_ERROR], and one whose save memory couldn't be written closes
 * with the reason as [EXTRA_SAVE_ERROR].
 *
 * The view fills the screen and the session fits the core's frame inside it at the core's aspect
 * ratio, following any change to it mid-game, so the view needs no new layout when that changes.
 *
 * A two-screen system's game, on a device with a second display, plays on both: the core draws its
 * screens stacked in one frame, and the session shows the top half here and the bottom half on the
 * second display, where touches reach the game. The save-state slots cover the bottom screen while
 * the quick menu is open. With one display, or a core whose session can't present to a second, the
 * game plays on this one, as the core lays it out by default.
 */
class SessionActivity : ComponentActivity() {
  private lateinit var view: GameSessionView
  private lateinit var menu: ComposeView
  private var session: GameSession? = null

  /** The game's lower screen on the second display, while it shows. */
  private var lowerView: LowerScreenView? = null

  /**
   * Whether the game draws its two screens stacked for showing on two displays, as decided when it
   * started.
   */
  private var twoScreens = false

  /** Whether the session turned down the lower screen, so the game plays on one display. */
  private var lowerUnsupported by mutableStateOf(false)
  private val heldKeys = HeldKeys()
  private var inputSettings = InputSettings()

  private val quickMenu by lazy {
    QuickMenuState(
      SessionQuickMenu(
        session = { session },
        statePaths = intent.getStringArrayExtra(EXTRA_STATES)?.toList().orEmpty(),
        saveRamPath = intent.getStringExtra(EXTRA_SAVE_RAM),
        hardcore = achievementsOn && intent.getBooleanExtra(EXTRA_HARDCORE, false),
        onResume = ::closeMenu,
        onQuit = ::stopAndFinish,
      ),
      lifecycleScope,
    )
  }

  /** Whether the quick menu is open, and the game paused under it. */
  private var menuOpen by mutableStateOf(false)

  /** The toasts over the game, such as unlocked achievements. */
  private val toasts = ToastState()

  /** Whether the session takes part in RetroAchievements, once it has started. */
  private var achievementsOn = false

  /** Sends the session's RetroAchievements requests, while it takes part. */
  private var achievementsClient: RetroAchievementsClient? = null

  /**
   * The keys pressed in the menu, including the one that opened it, so the rest of each press
   * (repeats and release) stays from the game once the menu closes.
   */
  private val menuKeys = mutableSetOf<Int>()

  private val listener =
    object : SessionListener {
      override fun infoChanged(info: SessionInfo) {
        Log.i(TAG, "the core changed its video mode: $info")
      }

      override fun coreQuit() {
        Log.i(TAG, "the core quit")
        runOnUiThread { stopAndFinish() }
      }

      override fun achievementEvent(event: AchievementEvent) {
        val toast =
          when (event) {
            is AchievementEvent.Unlocked ->
              unlockedToast(event.achievement.title, event.achievement.points.toInt())
            is AchievementEvent.Failed -> Toast("RetroAchievements", event.reason)
          }
        runOnUiThread { toasts.show(toast) }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.decorView.setBackgroundColor(Color.BLACK)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    inputSettings = settingsFile(this).read().input
    twoScreens =
      stackedScreensOptions().isNotEmpty() &&
        getSystemService(DisplayManager::class.java).secondaryDisplay() != null
    view = GameSessionView(this)
    view.pauseWhenUnfocused = intent.getBooleanExtra(EXTRA_PAUSE_WHEN_UNFOCUSED, false)
    view.faceKeyCodes =
      AButtonPosition.valueOf(intent.getStringExtra(EXTRA_A_BUTTON_POSITION)!!).faceKeyCodes()
    // The menu draws over the game's surface, which shows through where it's clear.
    menu =
      ComposeView(this).apply {
        // Closed, the menu can't take the focus from the game.
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setContent { SessionContent() }
      }
    setContentView(
      FrameLayout(this).apply {
        addView(view, MATCH_PARENT, MATCH_PARENT)
        addView(menu, MATCH_PARENT, MATCH_PARENT)
      },
    )
    view.requestFocus()
    onBackPressedDispatcher.addCallback(this) {
      when {
        menuOpen -> quickMenu.back()
        session != null -> openMenu()
        else -> stopAndFinish()
      }
    }
    lifecycleScope.launch { start() }
  }

  /**
   * The quick menu while it's open: over the game on this display, with the save-state slots on the
   * second display, or beside the menu when there's only one. With [twoScreens], the second display
   * shows the game's lower screen, which the slots cover while the menu is open.
   */
  @Composable
  private fun SessionContent() {
    val display = rememberSecondaryDisplay()
    var failedDisplayId by remember { mutableStateOf<Int?>(null) }
    val usableDisplay = display?.takeIf { it.displayId != failedDisplayId }
    val lowerScreen = twoScreens && usableDisplay != null && !lowerUnsupported
    val slots = @Composable { StateSlotsPanel(quickMenu) }
    // Toasts, such as unlocked achievements, show over the game while it runs.
    if (!menuOpen) ToastHost(toasts, Modifier.exposeTestTags())
    if (menuOpen) {
      val layout = if (usableDisplay != null) DualScreenLayout.Separate else DualScreenLayout.Folded
      ActionInputRoot(inputSettings, heldKeys, Modifier.exposeTestTags()) {
        FocusRoot {
          Box(Modifier.fillMaxSize()) {
            DualScreen(layout, primary = { QuickMenu(quickMenu) }, secondary = slots)
          }
        }
      }
    }
    if (usableDisplay != null && (menuOpen || lowerScreen)) {
      SecondaryDisplayContent(
        display = usableDisplay,
        onFailed = { failedDisplayId = usableDisplay.displayId },
      ) {
        Box(Modifier.fillMaxSize()) {
          if (lowerScreen) LowerScreen()
          if (menuOpen) slots()
        }
      }
    }
  }

  /** The game's lower screen, which the session presents to while it's composed. */
  @Composable
  private fun LowerScreen() {
    AndroidView(
      factory = { context ->
        LowerScreenView(context).also {
          it.onUnsupported = { lowerUnsupported = true }
          it.touchable = !menuOpen
          it.session = session
          lowerView = it
        }
      },
      modifier = Modifier.fillMaxSize(),
      onRelease = {
        it.session = null
        if (lowerView === it) lowerView = null
      },
    )
  }

  /** Pauses the game and opens the quick menu over it. */
  private fun openMenu() {
    if (menuOpen || session == null) return
    // Not running releases what the game held, and the menu takes the focus, which the game can't
    // hold meanwhile, so the game hears nothing until the menu closes.
    view.running = false
    lowerView?.touchable = false
    setGameFocusable(false)
    quickMenu.open()
    menu.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    menuOpen = true
  }

  /** Closes the quick menu, and runs the game again with the controls back in its hands. */
  private fun closeMenu() {
    if (!menuOpen) return
    menuOpen = false
    menu.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    setGameFocusable(true)
    lowerView?.touchable = true
    view.running = true
    view.requestFocus()
  }

  private fun setGameFocusable(focusable: Boolean) {
    view.isFocusable = focusable
    view.isFocusableInTouchMode = focusable
  }

  /**
   * Stops the session, which writes its save memory, and closes. A save that couldn't be written
   * goes back as [EXTRA_SAVE_ERROR], so the player hears of it rather than losing it unseen.
   */
  private fun stopAndFinish() {
    if (isFinishing) return
    stopSession()?.let { setResult(RESULT_OK, Intent().putExtra(EXTRA_SAVE_ERROR, it)) }
    finish()
  }

  /** Stops the session, which writes its save memory. Returns why the save couldn't be written. */
  private fun stopSession(): String? {
    val stopping = session ?: return null
    session = null
    view.session = null
    lowerView?.session = null
    return try {
      stopping.stop()
      null
    } catch (e: SessionException) {
      Log.e(TAG, "couldn't write the save memory", e)
      e.message ?: e.toString()
    } finally {
      stopping.close()
    }
  }

  override fun onPause() {
    super.onPause()
    // Something else closed the session, such as HOME, which clears the launcher's task to bring
    // it to the front. The save memory is written now, before the launcher resumes and reads it.
    // The result of an activity closed that way says nothing, so a failure waits for the launcher.
    if (isFinishing) stopSession()?.let { unreportedSaveError = it }
  }

  private suspend fun start() {
    val achievements = achievementOptions()
    val ready = CompletableDeferred<GameSession>()
    val http = achievements?.let {
      val client = retroAchievementsClient(this).also { achievementsClient = it }
      SessionAchievementsHttp(client, lifecycleScope, ready)
    }
    achievementsOn = http != null
    val options = options(achievements)
    // Starting can't be abandoned halfway. If the activity goes meanwhile, withContext throws on
    // the way back and the session it started is closed here, since nothing will show it.
    var unclaimed: GameSession? = null
    try {
      val started =
        try {
          withContext(Dispatchers.IO + NonCancellable) {
            GameSession.start(options, listener, http).also { unclaimed = it }
          }
        } catch (e: SessionException) {
          Log.e(TAG, "can't start the session", e)
          ready.cancel()
          setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, e.message ?: e.toString()))
          finish()
          return
        }
      unclaimed = null
      session = started
      view.session = started
      lowerView?.session = started
      ready.complete(started)
    } finally {
      unclaimed?.let { withContext(Dispatchers.IO + NonCancellable) { it.close() } }
    }
  }

  /**
   * How the game takes part in RetroAchievements: when the device is signed in and the platform has
   * achievements, with the account's token and the hardcore setting the launcher passed.
   */
  private suspend fun achievementOptions(): AchievementOptions? {
    val console = intent.getIntExtra(EXTRA_ACHIEVEMENTS_CONSOLE, -1).takeIf { it >= 0 }
    if (console == null) {
      Log.i(TAG, "RetroAchievements has no achievements for the game's platform")
      return null
    }
    val account = retroAchievementsStore(this).load()
    if (account == null) {
      Log.i(TAG, "not signed in to RetroAchievements")
      return null
    }
    return AchievementOptions(
      username = account.username,
      token = account.token,
      consoleId = console.toUInt(),
      hardcore = intent.getBooleanExtra(EXTRA_HARDCORE, false),
    )
  }

  private fun options(achievements: AchievementOptions?): SessionOptions {
    val intent = intent
    return SessionOptions(
      corePath = intent.getStringExtra(EXTRA_CORE)!!,
      contentPath = intent.getStringExtra(EXTRA_CONTENT),
      // A zipped game whose core can't open zips is extracted here.
      contentCacheDir = File(cacheDir, "session-content").path,
      systemDir = intent.getStringExtra(EXTRA_SYSTEM_DIR)!!,
      saveDir = intent.getStringExtra(EXTRA_SAVE_DIR)!!,
      saveRamPath = intent.getStringExtra(EXTRA_SAVE_RAM),
      coreOptions = if (twoScreens) stackedScreensOptions() else emptyMap(),
      video = VideoMode.WINDOW,
      lowerScreenWidthShare =
        intent.getFloatExtra(EXTRA_LOWER_SCREEN_WIDTH_SHARE, 0f).takeIf { twoScreens && it > 0f },
      achievements = achievements,
    )
  }

  /** The core options that draw a two-screen system's screens stacked, or none for one screen. */
  private fun stackedScreensOptions(): Map<String, String> {
    val keys = intent.getStringArrayExtra(EXTRA_STACKED_SCREENS_KEYS).orEmpty()
    val values = intent.getStringArrayExtra(EXTRA_STACKED_SCREENS_VALUES).orEmpty()
    return keys.zip(values).toMap()
  }

  // Activity's dispatchKeyEvent is public; lint objects only because androidx.core's override is
  // restricted.
  @SuppressLint("RestrictedApi")
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    heldKeys.track(ComposeKeyEvent(event))
    // Back opens the menu through the back callback. The guide button, which games never get, does
    // what back does, for controllers without a back button.
    val key = event.keyCode
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
      // A new press starts over, even if an earlier one's release went to another window.
      menuKeys -= key
      if (key == KeyEvent.KEYCODE_BUTTON_MODE) {
        menuKeys += key
        onBackPressedDispatcher.onBackPressed()
        return true
      } else if (menuOpen) {
        menuKeys += key
      }
    } else if (key in menuKeys) {
      if (event.action == KeyEvent.ACTION_UP) menuKeys -= key
      if (!menuOpen) return true
    }
    // The game takes its controls while the menu is closed, wherever the focus is.
    if (!menuOpen && view.dispatchKeyEvent(event)) return true
    return super.dispatchKeyEvent(event)
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
    (!menuOpen && view.dispatchGenericMotionEvent(event)) || super.dispatchGenericMotionEvent(event)

  override fun onDestroy() {
    view.session = null
    lowerView?.session = null
    // Closing stops the core and writes its save memory.
    session?.close()
    session = null
    achievementsClient?.close()
    achievementsClient = null
    super.onDestroy()
  }

  companion object {
    private const val TAG = "SessionActivity"

    private const val EXTRA_CORE = "core"
    private const val EXTRA_CONTENT = "content"
    private const val EXTRA_SYSTEM_DIR = "system_dir"
    private const val EXTRA_SAVE_DIR = "save_dir"
    private const val EXTRA_SAVE_RAM = "save_ram"
    private const val EXTRA_STATES = "states"
    private const val EXTRA_PAUSE_WHEN_UNFOCUSED = "pause_when_unfocused"
    private const val EXTRA_A_BUTTON_POSITION = "a_button_position"
    private const val EXTRA_STACKED_SCREENS_KEYS = "stacked_screens_keys"
    private const val EXTRA_STACKED_SCREENS_VALUES = "stacked_screens_values"
    private const val EXTRA_LOWER_SCREEN_WIDTH_SHARE = "lower_screen_width_share"
    private const val EXTRA_ACHIEVEMENTS_CONSOLE = "achievements_console"
    private const val EXTRA_HARDCORE = "hardcore"

    /** Why the session couldn't start, in the result of one that couldn't. */
    const val EXTRA_ERROR = "error"

    /** Why the game's save memory couldn't be written as the session closed. */
    const val EXTRA_SAVE_ERROR = "save_error"

    @Volatile private var unreportedSaveError: String? = null

    /**
     * Why the save memory of a session that something else closed couldn't be written, once, or
     * null. Such a session's result has no [EXTRA_SAVE_ERROR].
     */
    fun takeUnreportedSaveError(): String? = unreportedSaveError.also { unreportedSaveError = null }

    /**
     * The intent that runs [core] with [content], with [systemDir] for its system files, its own
     * saves in [saveDir], its save memory in [saveRam] and its save states in [states], one file
     * per slot, with the gamepad's face buttons where [aButtonPosition] puts them.
     * [stackedScreensOptions] are the core's options that draw a two-screen system's screens
     * stacked, for playing on two displays, or none for a one-screen system, and
     * [lowerScreenWidthShare] is the bottom screen's share of that frame's width, centred, or null
     * when it spans the whole width. The game takes part in RetroAchievements as console
     * [achievementsConsole], when it has one and the device is signed in, in hardcore if
     * [hardcore].
     */
    fun intent(
      context: Context,
      core: String,
      content: String,
      systemDir: String,
      saveDir: String,
      saveRam: String,
      states: List<String>,
      pauseWhenUnfocused: Boolean,
      aButtonPosition: AButtonPosition,
      stackedScreensOptions: Map<String, String> = emptyMap(),
      lowerScreenWidthShare: Float? = null,
      achievementsConsole: Int? = null,
      hardcore: Boolean = false,
    ): Intent =
      Intent(context, SessionActivity::class.java)
        .putExtra(EXTRA_CORE, core)
        .putExtra(EXTRA_CONTENT, content)
        .putExtra(EXTRA_SYSTEM_DIR, systemDir)
        .putExtra(EXTRA_SAVE_DIR, saveDir)
        .putExtra(EXTRA_SAVE_RAM, saveRam)
        .putExtra(EXTRA_STATES, states.toTypedArray())
        .putExtra(EXTRA_PAUSE_WHEN_UNFOCUSED, pauseWhenUnfocused)
        .putExtra(EXTRA_A_BUTTON_POSITION, aButtonPosition.name)
        .putExtra(EXTRA_STACKED_SCREENS_KEYS, stackedScreensOptions.keys.toTypedArray())
        .putExtra(EXTRA_STACKED_SCREENS_VALUES, stackedScreensOptions.values.toTypedArray())
        .apply { lowerScreenWidthShare?.let { putExtra(EXTRA_LOWER_SCREEN_WIDTH_SHARE, it) } }
        .putExtra(EXTRA_ACHIEVEMENTS_CONSOLE, achievementsConsole ?: -1)
        .putExtra(EXTRA_HARDCORE, hardcore)
  }
}
