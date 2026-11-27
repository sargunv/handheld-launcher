package dev.sargunv.handheld.data.saves

import kotlin.time.Instant

/** One of the two copies of a game's save: the one on this device, or the one on the server. */
enum class SaveCopy {
  Device,
  Server,
}

/**
 * A copy of a game's save that save sync kept in the game's history, on this device, before another
 * copy replaced it.
 *
 * @param copy where the kept copy came from.
 * @param emulator what kept the save on this device, as its history folder names it: the emulator,
 *   or `builtin` for the built-in session's saves under every core.
 */
data class KeptSave(
  val path: String,
  val keptAt: Instant,
  val copy: SaveCopy,
  val emulator: String,
  val sizeBytes: Long,
)
