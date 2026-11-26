package dev.sargunv.handheld.ui.play

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.input.TestActionInputRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred

/** A session that records what the menu asked of it, with states saved in memory. */
private class FakeSession(
  private val saveFailure: String? = null,
  private val loading: CompletableDeferred<Unit>? = null,
) : QuickMenuSession {
  val calls = mutableListOf<String>()
  val saved = mutableMapOf<Int, Instant>()

  override suspend fun slots() = (1..6).map { StateSlot(it, saved[it]) }

  override suspend fun saveState(slot: Int): String? {
    calls += "save $slot"
    if (saveFailure == null) saved[slot] = Clock.System.now()
    return saveFailure
  }

  override suspend fun loadState(slot: Int): String? {
    calls += "load $slot"
    loading?.await()
    return null
  }

  override fun setFastForward(on: Boolean) {
    calls += "fast forward $on"
  }

  override fun resume() {
    calls += "resume"
  }

  override fun quit() {
    calls += "quit"
  }
}

@OptIn(ExperimentalTestApi::class)
class QuickMenuTest {
  private fun ComposeUiTest.showMenu(session: QuickMenuSession) {
    setContent {
      val scope = rememberCoroutineScope()
      val state = remember { QuickMenuState(session, scope).also { it.open() } }
      TestActionInputRoot {
        FocusRoot {
          Column {
            QuickMenu(state, Modifier.weight(1f))
            StateSlotsPanel(state, Modifier.weight(1f))
          }
        }
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.press(keys: List<Key>) {
    for (key in keys) {
      onRoot().performKeyInput { pressKey(key) }
      waitForIdle()
    }
  }

  @Test
  fun opensOnResume() = runComposeUiTest {
    val session = FakeSession()
    showMenu(session)
    onNodeWithTag("quick:resume").assertIsFocused()
    press(listOf(Key.ButtonA))
    assertEquals(listOf("resume"), session.calls)
  }

  @Test
  fun backResumes() = runComposeUiTest {
    val session = FakeSession()
    showMenu(session)
    press(listOf(Key.ButtonB))
    assertEquals(listOf("resume"), session.calls)
  }

  @Test
  fun savesToASlotAndComesBackToTheMenu() = runComposeUiTest {
    val session = FakeSession()
    showMenu(session)
    press(listOf(Key.DirectionDown, Key.ButtonA))
    onNodeWithTag("slot:1").assertIsFocused()
    onNodeWithTag("state:1").assert(expectValue(SemanticsProperties.Selected, true))
    press(listOf(Key.DirectionDown, Key.ButtonA))
    assertEquals(listOf("save 2"), session.calls)
    onNodeWithTag("quick:message").assertTextEquals("Saved to slot 2.")
    onNodeWithTag("quick:save").assertIsFocused()
    onNodeWithTag("state:2").assertTextEquals("2 · just now")
    onNodeWithTag("state:1").assertTextEquals("Empty", "1")
  }

  @Test
  fun aFailedSaveSaysWhyAndStaysOnTheSlots() = runComposeUiTest {
    val session = FakeSession(saveFailure = "This game's core can't save states.")
    showMenu(session)
    press(listOf(Key.DirectionDown, Key.ButtonA, Key.ButtonA))
    onNodeWithTag("quick:message").assertTextEquals("This game's core can't save states.")
    onNodeWithTag("slot:1").assertIsFocused()
  }

  @Test
  fun loadsASavedSlotAndResumes() = runComposeUiTest {
    val session = FakeSession()
    showMenu(session)
    press(listOf(Key.DirectionDown, Key.ButtonA, Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    session.calls.clear()
    // Load state, which starts on the slot saved to last.
    press(listOf(Key.DirectionDown, Key.ButtonA))
    onNodeWithTag("slot:3").assertIsFocused()
    press(listOf(Key.ButtonA))
    assertEquals(listOf("load 3", "resume"), session.calls)
  }

  @Test
  fun backWaitsForALoadUnderWay() = runComposeUiTest {
    val loading = CompletableDeferred<Unit>()
    val session = FakeSession(loading = loading)
    showMenu(session)
    press(listOf(Key.DirectionDown, Key.ButtonA, Key.ButtonA))
    session.calls.clear()
    press(listOf(Key.DirectionDown, Key.ButtonA, Key.ButtonA, Key.ButtonB, Key.ButtonB))
    assertEquals(listOf("load 1"), session.calls)
    loading.complete(Unit)
    waitForIdle()
    assertEquals(listOf("load 1", "resume"), session.calls)
  }

  @Test
  fun anEmptySlotDoesntLoad() = runComposeUiTest {
    val session = FakeSession()
    showMenu(session)
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    onNodeWithTag("slot:1").assertIsFocused()
    press(listOf(Key.ButtonA))
    // A tap on the lower screen doesn't load it either.
    onNodeWithTag("state:1").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    assertEquals(emptyList(), session.calls)
  }

  @Test
  fun backLeavesTheSlotsForTheMenu() = runComposeUiTest {
    val session = FakeSession()
    showMenu(session)
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.ButtonA, Key.ButtonB))
    onNodeWithTag("quick:load").assertIsFocused()
    assertEquals(emptyList(), session.calls)
  }

  @Test
  fun aTapOnTheLowerScreenChoosesTheSlot() = runComposeUiTest {
    val session = FakeSession()
    showMenu(session)
    press(listOf(Key.DirectionDown, Key.ButtonA))
    onNodeWithTag("state:4").performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
    assertEquals(listOf("save 4"), session.calls)
  }

  @Test
  fun fastForwardToggles() = runComposeUiTest {
    val session = FakeSession()
    showMenu(session)
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
    onNodeWithTag("quick:fast-forward").assertIsFocused().assertTextEquals("Fast forward", "Off")
    press(listOf(Key.ButtonA))
    onNodeWithTag("quick:fast-forward").assertTextEquals("Fast forward", "On")
    press(listOf(Key.ButtonA))
    assertEquals(listOf("fast forward true", "fast forward false"), session.calls)
  }

  @Test
  fun quitLeaves() = runComposeUiTest {
    val session = FakeSession()
    showMenu(session)
    press(
      listOf(
        Key.DirectionDown,
        Key.DirectionDown,
        Key.DirectionDown,
        Key.DirectionDown,
        Key.ButtonA,
      ),
    )
    assertEquals(listOf("quit"), session.calls)
  }
}
