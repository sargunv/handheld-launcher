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
import dev.sargunv.handheld.exposeTestTags
import dev.sargunv.handheld.rememberSecondaryDisplay
import dev.sargunv.handheld.secondaryDisplay
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A game in the built-in session, full screen. Start pauses it and opens the quick menu over it,
 * with the save-state slots on the second screen. It closes when the core quits on its own, on Quit
 * in the quick menu, or on the system's back gesture while the menu is closed, and the session's
 * save memory is written as it closes. A session that can't start closes it with the reason as its
 * result's [EXTRA_ERROR], and one whose save memory couldn't be written closes with the reason as
 * [EXTRA_SAVE_ERROR].
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
        onResume = ::closeMenu,
        onQuit = ::stopAndFinish,
      ),
      lifecycleScope,
    )
  }

  /** Whether the quick menu is open, and the game paused under it. */
  private var menuOpen by mutableStateOf(false)

  /**
   * The keys pressed in the menu, including the Start that opened it, so the rest of each press
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
      if (menuOpen) quickMenu.back() else stopAndFinish()
    }
    lifecycleScope.launch { start(options()) }
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
    val stopping = session
    session = null
    view.session = null
    lowerView?.session = null
    try {
      stopping?.stop()
    } catch (e: SessionException) {
      Log.e(TAG, "couldn't write the save memory", e)
      setResult(RESULT_OK, Intent().putExtra(EXTRA_SAVE_ERROR, e.message ?: e.toString()))
    }
    stopping?.close()
    finish()
  }

  private suspend fun start(options: SessionOptions) {
    // Starting can't be abandoned halfway. If the activity goes meanwhile, withContext throws on
    // the way back and the session it started is closed here, since nothing will show it.
    var unclaimed: GameSession? = null
    try {
      val started =
        try {
          withContext(Dispatchers.IO + NonCancellable) {
            GameSession.start(options, listener).also { unclaimed = it }
          }
        } catch (e: SessionException) {
          Log.e(TAG, "can't start the session", e)
          setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, e.message ?: e.toString()))
          finish()
          return
        }
      unclaimed = null
      session = started
      view.session = started
      lowerView?.session = started
    } finally {
      unclaimed?.let { withContext(Dispatchers.IO + NonCancellable) { it.close() } }
    }
  }

  private fun options(): SessionOptions {
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
    // Start opens the menu. In the menu, it closes it and presses Start in the game, which has no
    // other way to get it.
    val key = event.keyCode
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
      // A new press starts over, even if an earlier one's release went to another window.
      menuKeys -= key
      if (key == KeyEvent.KEYCODE_BUTTON_START) {
        if (menuOpen) {
          // A save or load under way finishes before the game runs again.
          if (quickMenu.busy) {
            menuKeys += key
            return true
          }
          closeMenu()
        } else if (session != null) {
          menuKeys += key
          openMenu()
          return true
        }
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

    /** Why the session couldn't start, in the result of one that couldn't. */
    const val EXTRA_ERROR = "error"

    /** Why the game's save memory couldn't be written as the session closed. */
    const val EXTRA_SAVE_ERROR = "save_error"

    /**
     * The intent that runs [core] with [content], with [systemDir] for its system files, its own
     * saves in [saveDir], its save memory in [saveRam] and its save states in [states], one file
     * per slot, with the gamepad's face buttons where [aButtonPosition] puts them.
     * [stackedScreensOptions] are the core's options that draw a two-screen system's screens
     * stacked, for playing on two displays, or none for a one-screen system.
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
  }
}
