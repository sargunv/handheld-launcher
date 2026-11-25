package dev.sargunv.handheld

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.lifecycle.lifecycleScope
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.KeystoreCredentialStore
import dev.sargunv.handheld.data.auth.installIdentifier
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import java.io.File
import kotlinx.io.files.Path

class MainActivity : ComponentActivity() {
  private val heldKeys = HeldKeys()

  private val gamepadMotion by lazy { GamepadMotion(lifecycleScope, ::dispatchKeyEvent) }

  /** A layout forced from the launch intent, for development on single-display devices. */
  private var forcedLayout by mutableStateOf<DualScreenLayout?>(null)

  private var showsDebugScreen = false

  private val credentials by lazy { KeystoreCredentialStore(File(filesDir, "credentials")) }

  private val device by lazy {
    DeviceDescription(
      identifier = installIdentifier(Path(filesDir.path, "install-id")),
      name =
        if (Build.MODEL.startsWith(Build.MANUFACTURER)) Build.MODEL
        else "${Build.MANUFACTURER} ${Build.MODEL}",
      platform = "android",
      appVersion = packageManager.getPackageInfo(packageName, 0).versionName,
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val debugScreen = debugScreen(this)
    if (debugScreen != null) {
      // A debug screen takes the controller's input as it comes, without the launcher's mapping.
      showsDebugScreen = true
      setContent { debugScreen() }
      return
    }
    forcedLayout = forcedLayout(intent)
    getSystemService(InputManager::class.java).registerInputDeviceListener(gamepadMotion, null)
    val settingsFile = settingsFile(this)
    val settings = settingsFile.read()
    setContent {
      val display = rememberSecondaryDisplay()
      var failedDisplayId by remember { mutableStateOf<Int?>(null) }
      val usableDisplay = display?.takeIf { it.displayId != failedDisplayId }
      val layout =
        forcedLayout
          ?: if (usableDisplay != null) DualScreenLayout.Separate else DualScreenLayout.Folded
      App(
        layout = layout,
        heldKeys = heldKeys,
        credentials = credentials,
        device = device,
        initialSettings = settings,
        saveSettings = settingsFile::write,
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

  override fun onDestroy() {
    if (!showsDebugScreen) {
      getSystemService(InputManager::class.java).unregisterInputDeviceListener(gamepadMotion)
    }
    super.onDestroy()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    // The activity picks a debug screen when it's created, so a launch that opens, leaves or
    // changes one recreates it. That also closes a game session the old screen was running.
    if (showsDebugScreen || debugScreen(this) != null) recreate()
    else forcedLayout = forcedLayout(intent)
  }

  // The window sees every key, even while nothing in its content has focus. Activity's
  // dispatchKeyEvent is public; lint objects only because androidx.core's override is restricted.
  @SuppressLint("RestrictedApi")
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    heldKeys.track(ComposeKeyEvent(event))
    return super.dispatchKeyEvent(event)
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
    (!showsDebugScreen && gamepadMotion.onGenericMotionEvent(event)) ||
      super.dispatchGenericMotionEvent(event)

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus && !showsDebugScreen) gamepadMotion.release()
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
