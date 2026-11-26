package dev.sargunv.handheld.ui.library

import androidx.compose.runtime.Immutable
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.places.gameCount
import kotlinx.serialization.Serializable

/** Library's settings. */
@Serializable
data class LibrarySettings(
  /** Whether an "All games" entry, holding the whole library, comes before the platforms. */
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

/**
 * The [DeviceState]s of the games with [statuses], by ROM id. A download that failed waits in the
 * queue for a retry, but the game is no closer to the device, so it has no state.
 */
internal fun deviceStates(statuses: Map<Long, RomStatus>): Map<Long, DeviceState> = buildMap {
  for ((id, status) in statuses) {
    when (status) {
      is RomStatus.OnDevice -> put(id, DeviceState.OnDevice)
      RomStatus.Queued -> put(id, DeviceState.Downloading(null))
      is RomStatus.Downloading ->
        put(
          id,
          DeviceState.Downloading(
            status.totalBytes.takeIf { it > 0 }?.let { status.bytesDownloaded.toFloat() / it },
          ),
        )
      is RomStatus.Failed,
      RomStatus.NotOnDevice -> Unit
    }
  }
}

/**
 * An entry on Library's list of platforms, which opens a grid of its games. Inside a grid, L and R
 * move to the neighboring entries.
 */
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
 * The shelves in the order Library lists them: "All games" first when [allGames] is on, then each
 * platform with games, in the order given.
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

/** A shelf on Library's list, with how many games it holds and how many are on this device. */
@Immutable
data class ShelfSummary(val shelf: LibraryShelf, val games: Int, val onDevice: Int) {
  /** What a screen reader says for the shelf. */
  val description: String
    get() = "${shelf.title}, ${gameCount(games)}, $onDevice on this device"
}

/**
 * The [shelves] with their counts. [romPlatforms] gives each game's platform by ROM id, and
 * [states] says which games are on the device. "All games" holds every platform's games.
 */
fun shelfSummaries(
  shelves: List<LibraryShelf>,
  romPlatforms: Map<Long, Long>,
  states: Map<Long, DeviceState>,
): List<ShelfSummary> {
  val onDevice =
    states
      .filterValues { it == DeviceState.OnDevice }
      .keys
      .mapNotNull(romPlatforms::get)
      .groupingBy { it }
      .eachCount()
  val platforms = shelves.filterIsInstance<LibraryShelf.OfPlatform>().map { it.platform }
  return shelves.map { shelf ->
    when (shelf) {
      LibraryShelf.AllGames ->
        ShelfSummary(
          shelf,
          platforms.sumOf { it.romCount },
          platforms.sumOf { onDevice[it.id] ?: 0 },
        )
      is LibraryShelf.OfPlatform ->
        ShelfSummary(shelf, shelf.platform.romCount, onDevice[shelf.platform.id] ?: 0)
    }
  }
}

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
