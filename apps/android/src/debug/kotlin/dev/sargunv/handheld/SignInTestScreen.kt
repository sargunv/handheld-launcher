package dev.sargunv.handheld

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommSignIn
import dev.sargunv.handheld.ui.dualscreen.DualScreen
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.input.ActionInputRoot
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.InputSettings
import dev.sargunv.handheld.ui.signin.SignInPrimary
import dev.sargunv.handheld.ui.signin.SignInSecondary
import dev.sargunv.handheld.ui.signin.SignInState

/**
 * The sign-in screens, in a dual-screen frame, signing in to nothing that lasts: credentials stay
 * in memory, so the app stays signed in to its own server. For trying sign-in against a test server
 * on a device that's signed in.
 */
@Composable
internal fun SignInTestScreen() {
  var signedIn by remember { mutableStateOf(false) }
  if (signedIn) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
      BasicText("Signed in", style = TextStyle(color = Color.White))
    }
    return
  }
  val state = remember {
    SignInState(
      RommSignIn(InMemoryCredentialStore()),
      DeviceDescription("sign-in-test", "Sign-in test", "android", null),
      onSignedIn = { signedIn = true },
    )
  }
  val heldKeys = remember { HeldKeys() }
  ActionInputRoot(
    InputSettings(),
    heldKeys,
    Modifier.exposeTestTags().onPreviewKeyEvent {
      heldKeys.track(it)
      false
    },
  ) {
    FocusRoot {
      DualScreen(
        DualScreenLayout.Frame,
        primary = { SignInPrimary(state) },
        secondary = { SignInSecondary(state) },
      )
    }
  }
}
