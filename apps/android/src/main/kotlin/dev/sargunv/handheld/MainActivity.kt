package dev.sargunv.handheld

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout

class MainActivity : ComponentActivity() {
  private val gamepadMotion by lazy {
    GamepadMotion(lifecycleScope) { window.decorView.dispatchKeyEvent(it) }
  }

  /** A layout forced from the launch intent, for development on single-display devices. */
  private var forcedLayout by mutableStateOf<DualScreenLayout?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    forcedLayout = forcedLayout(intent)
    setContent {
      val display = rememberSecondaryDisplay()
      var failedDisplayId by remember { mutableStateOf<Int?>(null) }
      val usableDisplay = display?.takeIf { it.displayId != failedDisplayId }
      val layout =
        forcedLayout
          ?: if (usableDisplay != null) DualScreenLayout.Separate else DualScreenLayout.Folded
      App(
        layout = layout,
        secondaryDisplay = { content ->
          if (usableDisplay != null) {
            SecondaryDisplayContent(
              display = usableDisplay,
              onFailed = { failedDisplayId = usableDisplay.displayId },
              content = content,
            )
          }
        },
      )
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    forcedLayout = forcedLayout(intent)
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
    gamepadMotion.onGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus) gamepadMotion.release()
  }

  /**
   * Reads the `layout` extra (`frame` or `folded`) that debuggable builds accept, so a device with
   * one display can show the dual-screen frame: `am start -n ... --es layout frame`.
   */
  private fun forcedLayout(intent: Intent): DualScreenLayout? {
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
    return when (intent.getStringExtra("layout")) {
      "frame" -> DualScreenLayout.Frame
      "folded" -> DualScreenLayout.Folded
      else -> null
    }
  }
}
