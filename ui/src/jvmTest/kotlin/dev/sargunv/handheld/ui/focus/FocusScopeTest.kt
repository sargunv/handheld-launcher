package dev.sargunv.handheld.ui.focus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.sargunv.handheld.ui.input.TestActionInputRoot
import dev.sargunv.handheld.ui.navigation.NavigationHost
import dev.sargunv.handheld.ui.navigation.Navigator
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FocusScopeTest {
  @Test
  fun aScreenWithoutItemsStillTakesBack() = runComposeUiTest {
    val navigator = Navigator("home")
    var loaded by mutableStateOf(false)
    setContent {
      TestActionInputRoot {
        FocusRoot {
          NavigationHost(navigator) { screen ->
            NavEntry(screen) {
              when {
                screen == "home" ->
                  Box(
                    Modifier.size(40.dp)
                      .testTag("open")
                      .focusItem("open", onConfirm = { navigator.push("loading") }),
                  )
                loaded -> Box(Modifier.size(40.dp).testTag("loaded").focusItem("loaded"))
                else -> Box(Modifier.size(40.dp))
              }
            }
          }
        }
      }
    }
    waitForIdle()
    onRoot().performKeyInput { pressKey(Key.ButtonA) }
    waitForIdle()
    assertEquals("loading", navigator.current)

    // Items that appear later take focus from the scope.
    loaded = true
    waitForIdle()
    onNodeWithTag("loaded").assertIsFocused()

    loaded = false
    waitForIdle()
    onRoot().performKeyInput { pressKey(Key.ButtonB) }
    waitForIdle()
    assertEquals("home", navigator.current)
    onNodeWithTag("open").assertIsFocused()
  }
}
