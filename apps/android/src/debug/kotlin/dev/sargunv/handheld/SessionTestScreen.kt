package dev.sargunv.handheld

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sargunv.handheld.session.AchievementEvent
import dev.sargunv.handheld.session.GameSession
import dev.sargunv.handheld.session.GameSessionView
import dev.sargunv.handheld.session.SessionException
import dev.sargunv.handheld.session.SessionListener
import dev.sargunv.handheld.session.SessionOptions
import dev.sargunv.handheld.session.startSession
import java.io.File
import java.io.IOException

private sealed interface Status {
  data object Loading : Status

  data class Failed(val message: String) : Status

  data class Running(val session: GameSession) : Status
}

/**
 * Runs the core [core] gives with [content] and [coreOptions] in a game session, for trying the
 * session on a device. Saves and states go in [dir]. When the core quits, the screen closes the
 * session.
 */
@Composable
internal fun SessionTestScreen(
  dir: File,
  core: suspend () -> File?,
  content: File?,
  coreOptions: Map<String, String>,
) {
  var status by remember { mutableStateOf<Status>(Status.Loading) }
  var message by remember { mutableStateOf("") }
  val listener = remember {
    object : SessionListener {
      override fun coreQuit() {
        Log.i(TAG, "the core quit")
        status = Status.Failed("The core quit, so the session closed.")
      }

      override fun achievementEvent(event: AchievementEvent) {}
    }
  }
  LaunchedEffect(core, content) {
    val core =
      try {
        core()
      } catch (e: IOException) {
        status = Status.Failed("Can't get the core: ${e.message}")
        return@LaunchedEffect
      }
    if (core == null) {
      status = Status.Failed("No core: pass one with --es core <file in ${dir.path}>")
      return@LaunchedEffect
    }
    status =
      try {
        Status.Running(startSession(options(dir, core, content, coreOptions), listener))
      } catch (e: SessionException) {
        Status.Failed("Can't start ${core.name}: ${e.message}")
      }
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    when (val s = status) {
      Status.Loading -> Label("Loading", Modifier.align(Alignment.Center))
      is Status.Failed -> Label(s.message, Modifier.align(Alignment.Center))
      is Status.Running -> {
        AndroidView(
          factory = { context ->
            GameSessionView(context).apply {
              session = s.session
              setOnKeyListener { _, _, event ->
                handleHotkey(s.session, dir, event)?.let { message = it } != null
              }
              requestFocus()
            }
          },
          modifier = Modifier.fillMaxSize(),
          onRelease = { view ->
            view.session = null
            try {
              s.session.stop()
            } catch (e: SessionException) {
              Log.e(TAG, "couldn't write the save memory", e)
            }
          },
        )
        val info = remember(s.session) { s.session.info() }
        Label(
          "${info.libraryName} ${info.libraryVersion}, ${info.baseWidth}x${info.baseHeight}, " +
            "${"%.2f".format(info.fps)} fps. F2 saves a state, F4 loads it. $message",
          Modifier.align(Alignment.BottomStart),
        )
      }
    }
  }
}

private fun options(dir: File, core: File, content: File?, coreOptions: Map<String, String>) =
  SessionOptions(
    corePath = core.path,
    contentPath = content?.path,
    systemDir = dir.resolve("system").path,
    saveDir = dir.resolve("saves").path,
    saveRamPath = dir.resolve("saves/${(content ?: core).nameWithoutExtension}.srm").path,
    coreOptions = coreOptions,
  )

/** Saves or loads the state for a hotkey, and returns what happened, or null for other keys. */
private fun handleHotkey(session: GameSession, dir: File, event: KeyEvent): String? {
  if (event.action != KeyEvent.ACTION_DOWN) return null
  val state = dir.resolve("states/slot1.state").path
  return try {
    when (event.keyCode) {
      KeyEvent.KEYCODE_F2 -> session.saveState(state).let { "Saved a state." }
      KeyEvent.KEYCODE_F4 -> session.loadState(state).let { "Loaded the state." }
      else -> null
    }
  } catch (e: SessionException) {
    e.message ?: e.toString()
  }
}

private const val TAG = "SessionTestScreen"

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
  BasicText(
    text,
    modifier.background(Color.Black.copy(alpha = 0.6f)).padding(8.dp),
    style = TextStyle(color = Color.White),
  )
}
