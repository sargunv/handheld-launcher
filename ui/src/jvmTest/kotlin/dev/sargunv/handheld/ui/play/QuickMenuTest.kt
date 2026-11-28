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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.input.TestActionInputRoot
import dev.sargunv.handheld.ui.press
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred

/** A session that records what the menu asked of it, with states saved in memory. */
private class FakeSession(
  private val saveFailure: String? = null,
  private val loading: CompletableDeferred<Unit>? = null,
  var achievements: GameAchievements = GameAchievements.Off,
  override val hardcore: Boolean = false,
) : QuickMenuSession {
  override suspend fun achievements() = achievements

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

  @Test
  fun opensOnResumeAndBackResumesToo() = runComposeUiTest {
    val session = FakeSession()
    showMenu(session)
    onNodeWithTag("quick:resume").assertIsFocused()
    press(listOf(Key.ButtonA, Key.ButtonB))
    assertEquals(listOf("resume", "resume"), session.calls)
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
    onNodeWithTag("quick:message").assertTextEquals("Saved · Slot 2")
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
        Key.DirectionDown,
        Key.ButtonA,
      ),
    )
    assertEquals(listOf("quit"), session.calls)
  }

  private val set =
    GameAchievements.Ready(
      game = "Super Mario Land",
      hardcore = false,
      items =
        listOf(
          AchievementItem(1, "Mushroom Man", "Grab a mushroom", 1, unlocked = true),
          AchievementItem(2, "Pocket Change", "Collect 99 coins", 2, false, progress = "40/99"),
          AchievementItem(3, "Coin Allergy", "Finish World 1 without coins", 25, false),
        ),
    )

  @Test
  fun listsTheGamesAchievements() = runComposeUiTest {
    val session = FakeSession(achievements = set)
    showMenu(session)
    onNodeWithTag("quick:achievements").assertTextEquals("Achievements", "1 of 3")
    press(List(4) { Key.DirectionDown } + Key.ButtonA)
    onNodeWithTag("achievements:note").assertTextEquals("Super Mario Land · 1 of 3 unlocked")
    onNodeWithTag("achievement:1").assertIsFocused()
    onNodeWithTag("achievement:1")
      .assert(expectValue(SemanticsProperties.StateDescription, "Unlocked"))
    onNodeWithTag("achievement:2")
      .assert(expectValue(SemanticsProperties.StateDescription, "40/99"))
    onNodeWithTag("achievement:3")
      .assert(expectValue(SemanticsProperties.StateDescription, "Locked"))
    // Back returns to the menu, where the list was opened.
    press(listOf(Key.ButtonB))
    onNodeWithTag("quick:achievements").assertIsFocused()
    assertEquals(emptyList(), session.calls)
  }

  @Test
  fun readsAchievementsAgainUntilTheyLoad() = runComposeUiTest {
    val session = FakeSession(achievements = GameAchievements.Loading)
    showMenu(session)
    session.achievements = GameAchievements.NoSet
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithText("None", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag("quick:achievements").assertTextEquals("Achievements", "None")
  }

  @Test
  fun saysWhyThereAreNoAchievements() = runComposeUiTest {
    showMenu(FakeSession(achievements = GameAchievements.NoSet))
    onNodeWithTag("quick:achievements").assertTextEquals("Achievements", "None")
    press(List(4) { Key.DirectionDown } + Key.ButtonA)
    onNodeWithTag("achievements:note").assertTextEquals("No achievements")
    onNodeWithTag("quick:achievementList").assertIsFocused()
  }

  @Test
  fun hardcoreDisablesLoadingAndFastForward() = runComposeUiTest {
    val session = FakeSession(achievements = set.copy(hardcore = true), hardcore = true)
    showMenu(session)
    onNodeWithTag("quick:load").assertTextEquals("Load state", "Not in hardcore")
    onNodeWithTag("quick:fast-forward").assertTextEquals("Fast forward", "Not in hardcore")
    press(listOf(Key.DirectionDown, Key.DirectionDown, Key.ButtonA))
    press(listOf(Key.DirectionDown, Key.ButtonA))
    assertEquals(emptyList(), session.calls)
    // Saving still works.
    press(listOf(Key.DirectionUp, Key.DirectionUp, Key.ButtonA, Key.ButtonA))
    assertEquals(listOf("save 1"), session.calls)
  }

  @Test
  fun hardcoreWithoutASetAllowsEverything() = runComposeUiTest {
    val session = FakeSession(achievements = GameAchievements.NoSet, hardcore = true)
    showMenu(session)
    onNodeWithTag("quick:load").assertTextEquals("Load state")
    onNodeWithTag("quick:fast-forward").assertTextEquals("Fast forward", "Off")
  }

  @Test
  fun toastsShowOneAtATime() = runComposeUiTest {
    mainClock.autoAdvance = false
    val toasts = ToastState()
    setContent { ToastHost(toasts) }
    toasts.show(unlockedToast("Mushroom Man", 1))
    toasts.show(unlockedToast("Pocket Change", 2))
    mainClock.advanceTimeByFrame()
    onNodeWithTag("toast").assertTextEquals("Achievement unlocked: Mushroom Man", "1 point")
    mainClock.advanceTimeBy(4_100)
    onNodeWithTag("toast").assertTextEquals("Achievement unlocked: Pocket Change", "2 points")
    mainClock.advanceTimeBy(4_100)
    onNodeWithTag("toast").assertDoesNotExist()
  }
}
