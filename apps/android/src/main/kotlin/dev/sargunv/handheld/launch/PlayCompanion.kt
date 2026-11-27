package dev.sargunv.handheld.launch

import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import dev.sargunv.handheld.exposeTestTags
import dev.sargunv.handheld.launcherIntent
import dev.sargunv.handheld.secondaryDisplay
import dev.sargunv.handheld.ui.play.CompanionSync
import dev.sargunv.handheld.ui.play.PlayCompanionScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A game playing in another app, as the companion on the second display shows it. [sync] sends the
 * save now, and [syncState] says how that's going. [quit], when set, asks for the emulator to be
 * closed once the launcher is back in front.
 */
class PlaySession(
  val title: String,
  val syncState: StateFlow<CompanionSync>,
  val sync: () -> Unit,
  val quit: (() -> Unit)? = null,
)

/**
 * The companion on the second display while a game plays in another app, with touch buttons to sync
 * the save now, to go back to the library, and, with closing emulators on, to quit. Its own task
 * keeps it apart from the launcher's, so it stays on the second display while the emulator runs on
 * the first.
 */
object PlayCompanion {
  private val current = MutableStateFlow<PlaySession?>(null)

  /**
   * The game playing while the companion shows, or null. The launcher's presentation, which draws
   * over activities on the second display, stays away while it's set.
   */
  val session: StateFlow<PlaySession?> = current.asStateFlow()

  /**
   * Shows the companion for [session] on the second display, when there is one. Call it before
   * starting the emulator, so the emulator's display ends up with the focus. [close] hides it.
   */
  fun show(activity: ComponentActivity, session: PlaySession) {
    val display = activity.getSystemService(DisplayManager::class.java).secondaryDisplay() ?: return
    current.value = session
    try {
      activity.startActivity(
        Intent(activity, PlayCompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle(),
      )
    } catch (e: SecurityException) {
      log.w(e) { "can't show the companion on display ${display.displayId}" }
      current.value = null
    }
  }

  /** Closes the companion, which finishes once it sees the session end. */
  fun close() {
    current.value = null
  }
}

private val log = Logger.withTag("PlayCompanion")

/** The activity [PlayCompanion] shows. */
class PlayCompanionActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A focusable window would take the controller from the emulator at the first tap.
    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    lifecycleScope.launch {
      PlayCompanion.session.first { it == null }
      finish()
    }
    setContent {
      val session by PlayCompanion.session.collectAsState()
      Box(Modifier.fillMaxSize().exposeTestTags()) {
        session?.let { session ->
          PlayCompanionScreen(
            session.title,
            session.syncState,
            session.sync,
            ::backToLibrary,
            onQuit =
              session.quit?.let { quit ->
                {
                  // The emulator closes once the launcher is in front, so it's in the background.
                  quit()
                  backToLibrary()
                }
              },
          )
        }
      }
    }
  }

  /**
   * Brings the launcher's task to the front on its display. The emulator stays in the background.
   */
  private fun backToLibrary() {
    startActivity(
      launcherIntent(),
      ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
    )
  }
}
