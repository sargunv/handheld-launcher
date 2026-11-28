package dev.sargunv.handheld.ui.input

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sargunv.handheld.data.settings.InputSettings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HeldKeysTest {
  /**
   * Sends a key event the way a window does: [heldKeys] sees it whether or not anything has focus.
   */
  // Compose has no public way to make a desktop key event without an AWT component.
  @OptIn(InternalComposeUiApi::class)
  private fun ComposeUiTest.windowKey(heldKeys: HeldKeys, key: Key, type: KeyEventType) {
    val event = KeyEvent(key, type)
    heldKeys.track(event)
    onRoot().performKeyPress(event)
    waitForIdle()
  }

  @Test
  fun aKeyReleasedWhileNothingHasFocusIsntHeld() = runComposeUiTest {
    val heldKeys = HeldKeys()
    val requester = FocusRequester()
    lateinit var focusManager: FocusManager
    var confirms = 0
    setContent {
      focusManager = LocalFocusManager.current
      ActionInputRoot(InputSettings(), heldKeys) {
        Box(
          Modifier.size(40.dp)
            .focusRequester(requester)
            .onAction { action ->
              if (action == Action.Confirm) confirms++
              true
            }
            .focusable(),
        )
      }
    }
    runOnIdle { requester.requestFocus() }
    windowKey(heldKeys, Key.ButtonA, KeyEventType.KeyDown)
    assertEquals(1, confirms)

    runOnIdle { focusManager.clearFocus() }
    windowKey(heldKeys, Key.ButtonA, KeyEventType.KeyUp)

    runOnIdle { requester.requestFocus() }
    windowKey(heldKeys, Key.ButtonA, KeyEventType.KeyDown)
    assertEquals(2, confirms, "the second press was taken for a repeat")
  }
}
