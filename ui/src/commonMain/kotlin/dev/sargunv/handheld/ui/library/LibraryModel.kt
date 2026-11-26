package dev.sargunv.handheld.ui.library

import androidx.compose.runtime.Immutable
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import kotlinx.serialization.Serializable

/** Library's settings. */
@Serializable
data class LibrarySettings(
  /** Whether an "All games" shelf, holding the whole library, comes before the platforms. */
  val allGames: Boolean = false,
)

/**
 * Whether a game is on this device, or how far its download has got. A game with no state is only
 * on the server.
 */
@Immutable
sealed interface DeviceState {
  data object OnDevice : DeviceState

  /** On its way: [progress] runs from 0 to 1, or is null while the size isn't known. */
  data class Downloading(val progress: Float?) : DeviceState
}

/** One page of Library, which L and R move between. */
@Immutable
sealed interface LibraryShelf {
  /** Identifies the shelf across syncs. */
  val key: String
  val title: String

  /** The whole library in one grid. */
  data object AllGames : LibraryShelf {
    override val key = "all"
    override val title = "All games"
  }

  data class OfPlatform(val platform: Platform) : LibraryShelf {
    override val key = "platform:${platform.id}"
    override val title = platform.name
  }
}

/**
 * The shelves in the order L and R step through them: "All games" first when [allGames] is on, then
 * each platform with games, in the order given.
 */
fun libraryShelves(platforms: List<Platform>, allGames: Boolean): List<LibraryShelf> = buildList {
  if (allGames) add(LibraryShelf.AllGames)
  platforms.filter { it.romCount > 0 }.mapTo(this) { LibraryShelf.OfPlatform(it) }
}

/** The shelf with [key], or the first shelf when none has it. */
fun List<LibraryShelf>.shelfAt(key: String?): LibraryShelf? =
  firstOrNull { it.key == key } ?: firstOrNull()

/**
 * The shelf [step] places along from the one [shelfAt] gives for [key], or null past either end.
 */
fun List<LibraryShelf>.step(key: String?, step: Int): LibraryShelf? =
  getOrNull(indexOfFirst { it.key == key }.coerceAtLeast(0) + step)

enum class LibrarySort(val title: String) {
  Name("Name"),
  RecentlyAdded("Recently added"),
}

enum class LibraryFilter(val title: String) {
  All("All"),
  OnDevice("On device"),
  NotOnDevice("Not on device"),
}

/** The games [filter] keeps, in [sort]'s order. [states] says which games are on the device. */
fun List<Rom>.shown(
  filter: LibraryFilter,
  sort: LibrarySort,
  states: Map<Long, DeviceState>,
): List<Rom> {
  val kept =
    when (filter) {
      LibraryFilter.All -> this
      LibraryFilter.OnDevice -> filter { states[it.id] == DeviceState.OnDevice }
      LibraryFilter.NotOnDevice -> filter { states[it.id] != DeviceState.OnDevice }
    }
  return when (sort) {
    LibrarySort.Name -> kept.sortedWith(compareBy({ it.sortKey.lowercase() }, { it.id }))
    LibrarySort.RecentlyAdded ->
      kept.sortedWith(compareByDescending<Rom> { it.createdAt }.thenByDescending { it.id })
  }
}

/** The letter the A–Z jump files [rom] under: its sort key's first letter, or # for the rest. */
fun jumpLetter(rom: Rom): Char {
  val first = rom.sortKey.firstOrNull()?.uppercaseChar()
  return if (first != null && first in 'A'..'Z') first else '#'
}

/** Each letter's first game in [roms], in the order the letters first appear. */
fun jumpIndex(roms: List<Rom>): Map<Char, Int> = buildMap {
  roms.forEachIndexed { index, rom -> getOrPut(jumpLetter(rom)) { index } }
}
