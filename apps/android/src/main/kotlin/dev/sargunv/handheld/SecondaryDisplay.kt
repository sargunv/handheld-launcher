package dev.sargunv.handheld

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The display the secondary content goes on, or null when there's only one. Updates as displays
 * come and go.
 */
@Composable
fun rememberSecondaryDisplay(): Display? {
  val context = LocalContext.current
  val displayManager = remember(context) { context.getSystemService(DisplayManager::class.java) }
  var display by remember(displayManager) { mutableStateOf(displayManager.secondaryDisplay()) }
  DisposableEffect(displayManager) {
    val listener =
      object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
          display = displayManager.secondaryDisplay()
        }

        override fun onDisplayRemoved(displayId: Int) {
          display = displayManager.secondaryDisplay()
        }

        override fun onDisplayChanged(displayId: Int) {
          display = displayManager.secondaryDisplay()
        }
      }
    displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    onDispose { displayManager.unregisterDisplayListener(listener) }
  }
  return display
}

internal fun DisplayManager.secondaryDisplay(): Display? =
  getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull {
    it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF
  }

/**
 * Shows [content] on [display] in a [Presentation] for as long as this is composed.
 *
 * A presentation rather than an activity on that display keeps the secondary content in this
 * activity's composition: it reads the same state directly, with no second task in recents, and it
 * goes away with the activity. The presentation never takes input focus, so touching the second
 * screen doesn't move the gamepad's input there.
 *
 * @param onFailed called if the system refuses to show a presentation on [display].
 */
@Composable
fun SecondaryDisplayContent(
  display: Display,
  onFailed: () -> Unit,
  content: @Composable () -> Unit,
) {
  val activity = LocalContext.current.findActivity()
  val parent = rememberCompositionContext()
  val currentContent by rememberUpdatedState(content)
  val currentOnFailed by rememberUpdatedState(onFailed)
  DisposableEffect(activity, display) {
    val presentation = Presentation(activity, display)
    presentation.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    presentation.setContentView(
      ComposeView(presentation.context).apply {
        setViewTreeLifecycleOwner(activity as LifecycleOwner)
        setViewTreeSavedStateRegistryOwner(activity as SavedStateRegistryOwner)
        setParentCompositionContext(parent)
        setContent { Box(Modifier.fillMaxSize().exposeTestTags()) { currentContent() } }
      },
    )
    try {
      presentation.show()
    } catch (_: WindowManager.InvalidDisplayException) {
      currentOnFailed()
    }
    onDispose { presentation.dismiss() }
  }
}

private tailrec fun Context.findActivity(): Activity =
  when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> error("Not in an activity")
  }
