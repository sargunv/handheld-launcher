package dev.sargunv.handheld.ui.play

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
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
}
