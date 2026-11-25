package dev.sargunv.handheld.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.RommSignIn
import dev.sargunv.handheld.ui.demo.DemoPrimary
import dev.sargunv.handheld.ui.demo.DemoSecondary
import dev.sargunv.handheld.ui.demo.DemoState
import dev.sargunv.handheld.ui.demo.Placeholder
import dev.sargunv.handheld.ui.dualscreen.DualScreen
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.input.ActionInputRoot
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.signin.SignInPrimary
import dev.sargunv.handheld.ui.signin.SignInSecondary
import dev.sargunv.handheld.ui.signin.SignInState
import kotlinx.coroutines.flow.drop

/**
 * The app's root, shared by every platform. It opens on sign-in until [credentials] holds a server,
 * and on the home screen after that.
 *
 * @param layout how the two screens' content shares the hardware.
 * @param heldKeys the main window's held keys, which the platform keeps up to date.
 * @param initialSettings the stored settings, which the app reads once, when it first composes.
 * @param saveSettings stores the settings each time they change, in the order they changed.
 * @param device this device, as it introduces itself to the server when it signs in.
 * @param secondaryDisplay shows its content on the second display. Only called when [layout] is
 *   [DualScreenLayout.Separate].
 */
@Composable
fun App(
  layout: DualScreenLayout,
  heldKeys: HeldKeys,
  credentials: CredentialStore,
  device: DeviceDescription,
  modifier: Modifier = Modifier,
  initialSettings: Settings = Settings(),
  saveSettings: suspend (Settings) -> Unit = {},
  secondaryDisplay: @Composable (content: @Composable () -> Unit) -> Unit = {},
) {
  // Null until the stored credentials have been read.
  var signedIn by remember { mutableStateOf<Boolean?>(null) }
  LaunchedEffect(credentials) { signedIn = credentials.load() != null }
  val state = remember { DemoState(initialSettings) }
  val currentSaveSettings by rememberUpdatedState(saveSettings)
  LaunchedEffect(state) {
    snapshotFlow { state.settings }.drop(1).collect { currentSaveSettings(it) }
  }
  val signIn =
    remember(credentials, device) {
      SignInState(RommSignIn(credentials), device, onSignedIn = { signedIn = true })
    }
  val primary: @Composable () -> Unit = {
    when (signedIn) {
      null -> Box(Modifier.fillMaxSize().background(Placeholder.background))
      false -> SignInPrimary(signIn)
      true -> DemoPrimary(state)
    }
  }
  val secondary: @Composable () -> Unit = {
    when (signedIn) {
      null -> Box(Modifier.fillMaxSize().background(Placeholder.secondaryBackground))
      false -> SignInSecondary(signIn)
      true -> DemoSecondary(state)
    }
  }
  ActionInputRoot(state.inputSettings, heldKeys, modifier) {
    FocusRoot { DualScreen(layout = layout, primary = primary, secondary = secondary) }
  }
  if (layout == DualScreenLayout.Separate) secondaryDisplay(secondary)
}
