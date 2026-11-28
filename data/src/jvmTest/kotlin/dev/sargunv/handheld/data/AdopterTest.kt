package dev.sargunv.handheld.data

import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.PerGameState
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The device's Homebrew.gb is the server's ROM 2, which hands it the save kept for the file. */
class AdopterTest {
  private val local = GameKey.Local("/storage/ROMs/gb/Homebrew.gb")
  private val rom = GameKey.Romm(2)
  private val saves = ConcurrentHashMap(mapOf<GameKey, String>(local to "local save"))

  private fun move(adoptions: List<Adoption>) {
    for (adoption in adoptions) saves.remove(adoption.local)?.let { saves[adoption.rom] = it }
  }

  @Test
  fun aPlayWaitsForAnAdoptOfItsGameUnderWay() = runTest {
    val letAdopt = CompletableDeferred<Unit>()
    val state = PerGameState {
      letAdopt.await()
      move(it)
      emptyList()
    }
    val adopter = Adopter(flowOf(listOf(Adoption(local, rom))), state, backgroundScope)
    val played = async { adopter.play(rom) { saves[rom] } }
    runCurrent()
    letAdopt.complete(Unit)
    assertEquals("local save", played.await())
  }

  @Test
  fun anAdoptThatCouldntFinishIsTriedAgainWhenTheRomPlays() = runTest {
    var tries = 0
    // The first try fails, as when the save folder can't move.
    val state = PerGameState {
      if (tries++ == 0) return@PerGameState it
      move(it)
      emptyList()
    }
    val adopter = Adopter(flowOf(listOf(Adoption(local, rom))), state, backgroundScope)
    runCurrent()
    assertEquals(1, tries)
    assertEquals("local save", adopter.play(rom) { saves[rom] })
  }
}
