package dev.sargunv.handheld.launch

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.sargunv.handheld.session.GameSession
import dev.sargunv.handheld.session.GameSessionView
import dev.sargunv.handheld.session.SessionException
import dev.sargunv.handheld.session.SessionInfo
import dev.sargunv.handheld.session.SessionListener
import dev.sargunv.handheld.session.SessionOptions
import dev.sargunv.handheld.session.VideoMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A game in the built-in session, full screen. It closes when the core quits on its own, when the
 * player holds Start for a second, or on the system's back gesture, and the session's save memory
 * is written as it closes. A session that can't start closes it with the reason as its result's
 * [EXTRA_ERROR].
 *
 * The view fills the screen and the session fits the core's frame inside it at the core's aspect
 * ratio, following any change to it mid-game, so the view needs no new layout when that changes.
 */
class SessionActivity : ComponentActivity() {
  private lateinit var view: GameSessionView
  private var session: GameSession? = null
  private val handler = Handler(Looper.getMainLooper())
  private val leave = Runnable { finish() }

  private val listener =
    object : SessionListener {
      override fun infoChanged(info: SessionInfo) {
        Log.i(TAG, "the core changed its video mode: $info")
      }

      override fun coreQuit() {
        Log.i(TAG, "the core quit")
        runOnUiThread { finish() }
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
    view = GameSessionView(this)
    view.pauseWhenUnfocused = intent.getBooleanExtra(EXTRA_PAUSE_WHEN_UNFOCUSED, false)
    setContentView(view)
    view.requestFocus()
    lifecycleScope.launch { start(options()) }
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
    } finally {
      unclaimed?.let { withContext(Dispatchers.IO + NonCancellable) { it.close() } }
    }
  }

  private fun options(): SessionOptions {
    val intent = intent
    return SessionOptions(
      corePath = intent.getStringExtra(EXTRA_CORE)!!,
      contentPath = intent.getStringExtra(EXTRA_CONTENT),
      systemDir = intent.getStringExtra(EXTRA_SYSTEM_DIR)!!,
      saveDir = intent.getStringExtra(EXTRA_SAVE_DIR)!!,
      saveRamPath = intent.getStringExtra(EXTRA_SAVE_RAM),
      coreOptions = emptyMap(),
      video = VideoMode.WINDOW,
    )
  }

  // Activity's dispatchKeyEvent is public; lint objects only because androidx.core's override is
  // restricted.
  @SuppressLint("RestrictedApi")
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    // Start still reaches the game; holding it is what leaves.
    if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
      when (event.action) {
        KeyEvent.ACTION_DOWN ->
          if (event.repeatCount == 0) handler.postDelayed(leave, HOLD_TO_LEAVE_MILLIS)
        KeyEvent.ACTION_UP -> handler.removeCallbacks(leave)
      }
    }
    return super.dispatchKeyEvent(event)
  }

  override fun onDestroy() {
    handler.removeCallbacks(leave)
    view.session = null
    // Closing stops the core and writes its save memory.
    session?.close()
    session = null
    super.onDestroy()
  }

  companion object {
    private const val TAG = "SessionActivity"
    private const val HOLD_TO_LEAVE_MILLIS = 1000L

    private const val EXTRA_CORE = "core"
    private const val EXTRA_CONTENT = "content"
    private const val EXTRA_SYSTEM_DIR = "system_dir"
    private const val EXTRA_SAVE_DIR = "save_dir"
    private const val EXTRA_SAVE_RAM = "save_ram"
    private const val EXTRA_PAUSE_WHEN_UNFOCUSED = "pause_when_unfocused"

    /** Why the session couldn't start, in the result of one that couldn't. */
    const val EXTRA_ERROR = "error"

    /**
     * The intent that runs [core] with [content], with [systemDir] for its system files, its own
     * saves in [saveDir] and its save memory in [saveRam].
     */
    fun intent(
      context: Context,
      core: String,
      content: String,
      systemDir: String,
      saveDir: String,
      saveRam: String,
      pauseWhenUnfocused: Boolean,
    ): Intent =
      Intent(context, SessionActivity::class.java)
        .putExtra(EXTRA_CORE, core)
        .putExtra(EXTRA_CONTENT, content)
        .putExtra(EXTRA_SYSTEM_DIR, systemDir)
        .putExtra(EXTRA_SAVE_DIR, saveDir)
        .putExtra(EXTRA_SAVE_RAM, saveRam)
        .putExtra(EXTRA_PAUSE_WHEN_UNFOCUSED, pauseWhenUnfocused)
  }
}
