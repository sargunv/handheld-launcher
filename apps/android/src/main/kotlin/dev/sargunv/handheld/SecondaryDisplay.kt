package dev.sargunv.handheld

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** The display secondary content goes on, or null when there's only one. */
internal fun Context.secondaryDisplay(): Display? =
  getSystemService(DisplayManager::class.java)
    .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
    .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }

/** The second display as displays come and go, from [rememberSecondaryDisplay]. */
@Stable
class SecondaryDisplay internal constructor(private val context: Context) {
  private var current by mutableStateOf(context.secondaryDisplay())
  private var failedId by mutableStateOf<Int?>(null)

  /**
   * The display, or null when there's none, or the system refused to show [Show]'s content there.
   */
  val display: Display?
    get() = current?.takeIf { it.displayId != failedId }

  internal val listener =
    object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) = refresh()

      override fun onDisplayRemoved(displayId: Int) = refresh()

      override fun onDisplayChanged(displayId: Int) = refresh()
    }

  private fun refresh() {
    current = context.secondaryDisplay()
  }

  /**
   * Shows [content] on [display], when there is one, in a [Presentation] for as long as this is
   * composed.
   *
   * A presentation rather than an activity on that display keeps the secondary content in this
   * activity's composition: it reads the same state directly, with no second task in recents, and
   * it goes away with the activity. The presentation never takes input focus, so touching the
   * second screen doesn't move the gamepad's input there.
   */
  @Composable
  fun Show(content: @Composable () -> Unit) {
    val display = display ?: return
    val activity = LocalActivity.current as ComponentActivity
    val parent = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    DisposableEffect(activity, display) {
      val presentation = Presentation(activity, display)
      presentation.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
      presentation.setContentView(
        ComposeView(presentation.context).apply {
          setViewTreeLifecycleOwner(activity)
          setViewTreeSavedStateRegistryOwner(activity)
          setParentCompositionContext(parent)
          setContent { Box(Modifier.fillMaxSize().exposeTestTags()) { currentContent() } }
        },
      )
      try {
        presentation.show()
      } catch (_: WindowManager.InvalidDisplayException) {
        failedId = display.displayId
      }
      onDispose { presentation.dismiss() }
    }
  }
}

/** Follows the second display for as long as this is composed. */
@Composable
fun rememberSecondaryDisplay(): SecondaryDisplay {
  val context = LocalContext.current
  val secondary = remember(context) { SecondaryDisplay(context) }
  DisposableEffect(secondary) {
    val manager = context.getSystemService(DisplayManager::class.java)
    manager.registerDisplayListener(secondary.listener, Handler(Looper.getMainLooper()))
    onDispose { manager.unregisterDisplayListener(secondary.listener) }
  }
  return secondary
}
