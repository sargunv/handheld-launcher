package dev.sargunv.handheld.ui.focus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.sargunv.handheld.ui.dialog.Dialog
import dev.sargunv.handheld.ui.input.TestActionInputRoot
import dev.sargunv.handheld.ui.navigation.NavigationHost
import dev.sargunv.handheld.ui.navigation.Navigator
import dev.sargunv.handheld.ui.press
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    press(Key.ButtonA)
    assertEquals("loading", navigator.current)

    // Items that appear later take focus from the scope.
    loaded = true
    waitForIdle()
    onNodeWithTag("loaded").assertIsFocused()

    loaded = false
    waitForIdle()
    press(Key.ButtonB)
    assertEquals("home", navigator.current)
    onNodeWithTag("open").assertIsFocused()
  }

  @Test
  fun aScreenRecreatedUnderADialogLeavesFocusInTheDialog() = runComposeUiTest {
    var screenKey by mutableStateOf(0)
    var dialogOpen by mutableStateOf(true)
    setContent {
      TestActionInputRoot {
        FocusRoot {
          Box {
            key(screenKey) {
              FocusScope { Box(Modifier.size(40.dp).testTag("screen").focusItem("screen")) }
            }
            if (dialogOpen) {
              Dialog(onDismissRequest = { dialogOpen = false }) {
                Box(Modifier.size(40.dp).testTag("dialog").focusItem("dialog"))
              }
            }
          }
        }
      }
    }
    waitForIdle()
    onNodeWithTag("dialog").assertIsFocused()

    screenKey++
    waitForIdle()
    onNodeWithTag("dialog").assertIsFocused()

    // Back still reaches the dialog, and closing it hands focus to the new screen.
    press(Key.ButtonB)
    onNodeWithTag("screen").assertIsFocused()
  }

  @Test
  fun aScopeInsideADialogStillCountsAsADialog() = runComposeUiTest {
    var scopes: FocusScopes? = null
    setContent {
      TestActionInputRoot {
        FocusRoot {
          scopes = LocalFocusScopes.current
          Box {
            FocusScope { Box(Modifier.size(40.dp).testTag("screen").focusItem("screen")) }
            Dialog(onDismissRequest = {}) {
              // A page inside the dialog, as the RetroAchievements page is.
              FocusScope { Box(Modifier.size(40.dp).testTag("page").focusItem("page")) }
            }
          }
        }
      }
    }
    waitForIdle()
    onNodeWithTag("page").assertIsFocused()
    assertTrue(scopes!!.isDialogOpen)
  }
}
