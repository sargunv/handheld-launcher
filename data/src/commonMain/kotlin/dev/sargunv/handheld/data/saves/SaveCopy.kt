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
 * @param emulator the emulator the save belongs to, as the server files it.
 */
data class KeptSave(
  val path: String,
  val keptAt: Instant,
  val copy: SaveCopy,
  val emulator: String,
  val sizeBytes: Long,
)
