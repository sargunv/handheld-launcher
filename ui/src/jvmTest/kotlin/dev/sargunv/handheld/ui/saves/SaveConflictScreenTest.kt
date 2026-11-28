package dev.sargunv.handheld.ui.saves

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.input.TestActionInputRoot
import dev.sargunv.handheld.ui.press
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class SaveConflictScreenTest {
  private val now = Instant.parse("2025-03-01T12:00:00Z")

  private fun conflict(deviceAt: String, serverAt: String) =
    SaveConflict(
      "Pokemon Black",
      device = SaveVersion(Instant.parse(deviceAt), 512 * 1024),
      server = SaveVersion(Instant.parse(serverAt), 512 * 1024),
    )

  private fun choose(conflict: SaveConflict, keys: List<Key>): List<Any> {
    val events = mutableListOf<Any>()
    runComposeUiTest {
      setContent {
        TestActionInputRoot {
          FocusRoot {
            SaveConflictScreen(conflict, { events += it }, { events += "cancel" }, now = now)
          }
        }
      }
      waitForIdle()
      onNodeWithTag(conflict.newer.name).assertIsFocused()
      press(keys)
    }
    return events
  }

  @Test
  fun theNewerCopyIsFocusedAndOnePressChoosesIt() {
    val serverNewer = conflict("2025-03-01T10:00:00Z", "2025-03-01T11:00:00Z")
    assertEquals(listOf<Any>(SaveCopy.Server), choose(serverNewer, listOf(Key.ButtonA)))
    val deviceNewer = conflict("2025-03-01T11:30:00Z", "2025-03-01T11:00:00Z")
    assertEquals(listOf<Any>(SaveCopy.Device), choose(deviceNewer, listOf(Key.ButtonA)))
  }

  @Test
  fun theOtherCopyIsOneMoveAway() {
    val serverNewer = conflict("2025-03-01T10:00:00Z", "2025-03-01T11:00:00Z")
    assertEquals(
      listOf<Any>(SaveCopy.Device),
      choose(serverNewer, listOf(Key.DirectionLeft, Key.ButtonA)),
    )
  }

  @Test
  fun eachCopyIsOneButtonThatSaysAllAboutIt() = runComposeUiTest {
    val conflict = conflict("2025-03-01T10:00:00Z", "2025-03-01T11:00:00Z")
    setContent {
      TestActionInputRoot { FocusRoot { SaveConflictScreen(conflict, {}, {}, now = now) } }
    }
    onNodeWithTag(SaveCopy.Server.name)
      .assert(expectValue(SemanticsProperties.Role, Role.Button))
      .assertTextEquals("RomM", "Saved 1 hour ago", "512 KiB", "Newer")
    onNodeWithText("Choose a save · ${conflict.gameName}").assert(isHeading())
    // The hints say what choosing and Back do.
    onNodeWithTag("hint:Confirm").assertTextEquals("A", "Play")
    onNodeWithTag("hint:Back").assertTextEquals("B", "Cancel")
  }

  @Test
  fun backCancels() {
    val conflict = conflict("2025-03-01T10:00:00Z", "2025-03-01T11:00:00Z")
    assertEquals(listOf<Any>("cancel"), choose(conflict, listOf(Key.ButtonB)))
  }

  @Test
  fun describesTimes() {
    assertEquals("just now", ago(now, now))
    assertEquals("1 minute ago", ago(Instant.parse("2025-03-01T11:59:00Z"), now))
    assertEquals("3 hours ago", ago(Instant.parse("2025-03-01T09:00:00Z"), now))
    assertEquals("2 days ago", ago(Instant.parse("2025-02-27T12:00:00Z"), now))
  }
}
