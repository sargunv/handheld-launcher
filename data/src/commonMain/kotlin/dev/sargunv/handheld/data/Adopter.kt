package dev.sargunv.handheld.data

import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.PerGameState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one adopt step, for as long as [scope] runs: each of [adoptions], a file on the device that's
 * a ROM's, hands what [state] keeps for it as a local game to the ROM. A game whose play isn't
 * over, by [unfinished], waits until it is.
 */
class Adopter(
  private val adoptions: Flow<List<Adoption>>,
  private val state: PerGameState,
  scope: CoroutineScope,
) {
  /**
   * The games whose play isn't over: its launch, its game or what follows it, such as sending its
   * save. The platform's launcher keeps it up to date.
   */
  val unfinished = MutableStateFlow(emptySet<GameKey>())

  /** Held while adopting, so [play] waits for an adopt of its game that's under way. */
  private val mutex = Mutex()

  private val handover = MutableStateFlow(emptySet<Adoption>())

  /** The adoptions still listed that the adopt step has handed over. */
  val handedOver: StateFlow<Set<Adoption>> = handover.asStateFlow()

  init {
    scope.launch {
      combine(adoptions, unfinished) { listed, _ -> listed }
        .collect { listed ->
          mutex.withLock {
            handover.update { it intersect listed.toSet() }
            // Read under the lock, so a play that started since is left alone.
            val playing = unfinished.value
            adopt(listed.filterNot { it.local in playing || it.rom in playing })
          }
        }
    }
  }

  /**
   * Runs [launch], which adds [game] to [unfinished] before it first suspends, once any file the
   * latest scan matched to [game] has handed it what was kept for the file.
   */
  suspend fun <T> play(game: GameKey, launch: suspend () -> T): T = coroutineScope {
    mutex
      .withLock {
        adopt(adoptions.first().filter { it.rom == game })
        async(start = CoroutineStart.UNDISPATCHED) { launch() }
      }
      .await()
  }

  /** Hands over what's kept for each of [pending] that isn't handed over yet, under [mutex]. */
  private suspend fun adopt(pending: List<Adoption>) {
    val new = pending - handover.value
    if (new.isEmpty()) return
    val unfinished = state.adopt(new)
    handover.update { it + (new - unfinished.toSet()) }
  }
}
