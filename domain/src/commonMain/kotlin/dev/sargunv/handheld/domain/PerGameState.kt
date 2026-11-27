package dev.sargunv.handheld.domain

/**
 * A local game whose file is now [rom]'s: a scan matched the file at [local]'s path to the ROM, by
 * its content or because the server gained a game of its name.
 */
data class Adoption(val local: GameKey.Local, val rom: GameKey.Romm)

/**
 * State the app keeps for each game by its [GameKey], such as its Open with choice or the built-in
 * session's saves. A game's key changes once, when its file stops being a local game, named by its
 * path, and becomes a ROM, named by its id. [adopt] is the one step that hands everything kept
 * under the path to the ROM then, so nothing stays behind under a key that no game has any more.
 *
 * Each holder of per-game state is one of these, and the app runs them all together for every file
 * a scan matches.
 */
fun interface PerGameState {
  /**
   * Hands what's kept for each adoption's local game to its ROM. What the ROM has already wins, and
   * what it replaces is kept where it can't be lost when it's a save. Running it again, or for a
   * local game that kept nothing, changes nothing. Returns the adoptions it couldn't finish yet,
   * such as one whose files couldn't move, which a later adopt tries again.
   */
  suspend fun adopt(adoptions: List<Adoption>): List<Adoption>

  companion object {
    /** Keeps nothing per game. */
    val None = PerGameState { emptyList() }

    /** Each of [states], one after the other. */
    fun of(vararg states: PerGameState) = PerGameState { adoptions ->
      states.flatMap { it.adopt(adoptions) }.distinct()
    }
  }
}
