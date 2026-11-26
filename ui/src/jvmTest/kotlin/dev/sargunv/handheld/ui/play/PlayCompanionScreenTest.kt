package dev.sargunv.handheld.ui.play

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlayCompanionScreenTest {
  @Test
  fun syncsAndGoesBackOnTap() = runComposeUiTest {
    var syncs = 0
    var backs = 0
    setContent {
      PlayCompanionScreen(
        "Taiko",
        sync = {
          syncs++
          "Sent the save to RomM."
        },
        onBackToLibrary = { backs++ },
      )
    }

    onNodeWithTag("companion:sync").performTouchInput { click() }
    waitForIdle()
    onNodeWithTag("message").assertTextEquals("Sent the save to RomM.")
    assertEquals(1, syncs)

    onNodeWithTag("companion:back").performTouchInput { click() }
    waitForIdle()
    assertEquals(1, backs)
  }

  @Test
  fun aScreenReaderPressesTheButtonsToo() = runComposeUiTest {
    var backs = 0
    setContent { PlayCompanionScreen("Taiko", sync = { "" }, onBackToLibrary = { backs++ }) }
    onNodeWithTag("companion:back")
      .assert(expectValue(SemanticsProperties.Role, Role.Button))
      .assertTextEquals("Back to library")
      .performSemanticsAction(SemanticsActions.OnClick)
    assertEquals(1, backs)
  }

  @Test
  fun quitsOnlyWhenTheLauncherCan() = runComposeUiTest {
    var quits = 0
    var canQuit by mutableStateOf(false)
    setContent {
      PlayCompanionScreen(
        "Taiko",
        sync = { "" },
        onBackToLibrary = {},
        onQuit = if (canQuit) ({ quits++ }) else null,
      )
    }
    onNodeWithTag("companion:quit").assertDoesNotExist()

    canQuit = true
    waitForIdle()
    onNodeWithTag("companion:quit").assertTextEquals("Quit").performTouchInput { click() }
    waitForIdle()
    assertEquals(1, quits)
  }
}
