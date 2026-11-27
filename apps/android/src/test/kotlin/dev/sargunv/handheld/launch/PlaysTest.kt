package dev.sargunv.handheld.launch

import dev.sargunv.handheld.domain.GameKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PlaysTest {
  @Test
  fun aSecondLaunchWhileOneIsUnderWayDoesntStart() = runTest {
    val plays =
      Plays(
        backgroundScope,
        PlaySaves(MutableStateFlow(null)),
        closeEmulator = { true },
        launcherInFront = MutableStateFlow(true),
        keepAlive = {},
      )
    val first = GameKey.Romm(1)
    val gettingCore = CompletableDeferred<Unit>()
    val firstPlay = launch {
      plays.play(first, "First") {
        gettingCore.await()
        emptyList()
      }
    }
    runCurrent()
    var started = false
    val notes =
      plays.play(GameKey.Romm(2), "Second") {
        started = true
        emptyList()
      }
    assertEquals(listOf("Already playing · First"), notes)
    assertFalse(started)
    assertEquals(first, plays.playing.value)
    assertEquals(setOf(first), plays.unfinished.value)

    gettingCore.complete(Unit)
    firstPlay.join()
    assertEquals(null, plays.playing.value)
    assertEquals(emptySet(), plays.unfinished.value)
  }
}
