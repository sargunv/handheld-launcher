package dev.sargunv.handheld.ui.library

import androidx.compose.runtime.Immutable
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.places.gameCount
import kotlinx.serialization.Serializable

/** Library's tabs, in the order L and R step through them. */
@Serializable
enum class LibraryTab(val title: String) {
  OnDevice("On device"),
  Platforms("Platforms"),
  Collections("Collections"),
  AllGames("All games"),
}

/** Library's settings. */
@Serializable
data class LibrarySettings(
  /** The tab Library shows when it opens: the one it was last left on. */
  val tab: LibraryTab = LibraryTab.Platforms,
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
 * A platform or a collection on its Library tab, which opens a grid of its games. Inside a grid, L
 * and R move to the neighboring entries.
 */
@Immutable
sealed interface LibraryShelf {
  /** Identifies the shelf across syncs. */
  val key: String
  val title: String

  data class OfPlatform(val platform: Platform) : LibraryShelf {
    override val key = "platform:${platform.id}"
    override val title = platform.name
  }

  data class OfCollection(val collection: Collection) : LibraryShelf {
    override val key = collectionShelfKey(collection.kind, collection.id)
    override val title = collection.name
  }
}

/** The key of the collection of [kind] with [id]'s shelf. */
fun collectionShelfKey(kind: CollectionKind, id: Long) = "collection:${kind.name}:$id"

/** The platforms the Platforms tab lists: those with games, in the order given. */
fun platformShelves(platforms: List<Platform>): List<LibraryShelf> =
  platforms.filter { it.romCount > 0 }.map { LibraryShelf.OfPlatform(it) }

/** The collections the Collections tab lists: those with games, in the order given. */
fun collectionShelves(collections: List<Collection>): List<LibraryShelf> =
  collections.filter { it.romCount > 0 }.map { LibraryShelf.OfCollection(it) }

/** The shelf with [key], or the first shelf when none has it. */
fun List<LibraryShelf>.shelfAt(key: String?): LibraryShelf? =
  firstOrNull { it.key == key } ?: firstOrNull()

/**
 * The shelf [step] places along from the one [shelfAt] gives for [key], or null past either end.
 */
fun List<LibraryShelf>.step(key: String?, step: Int): LibraryShelf? =
  getOrNull(indexOfFirst { it.key == key }.coerceAtLeast(0) + step)

/** A shelf on its tab, with how many games it holds and how many are on this device. */
@Immutable
data class ShelfSummary(val shelf: LibraryShelf, val games: Int, val onDevice: Int) {
  /** What a screen reader says for the shelf. */
  val description: String
    get() = "${shelf.title}, ${gameCount(games)}, $onDevice on this device"
}

/**
 * The [shelves] with their counts. [romPlatforms] gives each game's platform by ROM id,
 * [collectionRoms] each collection's games by its kind and id, and [states] says which games are on
 * the device.
 */
fun shelfSummaries(
  shelves: List<LibraryShelf>,
  romPlatforms: Map<Long, Long>,
  collectionRoms: Map<Pair<CollectionKind, Long>, Set<Long>>,
  states: Map<Long, DeviceState>,
): List<ShelfSummary> {
  val onDevice = states.filterValues { it == DeviceState.OnDevice }.keys
  val onDeviceByPlatform = onDevice.mapNotNull(romPlatforms::get).groupingBy { it }.eachCount()
  return shelves.map { shelf ->
    when (shelf) {
      is LibraryShelf.OfPlatform ->
        ShelfSummary(shelf, shelf.platform.romCount, onDeviceByPlatform[shelf.platform.id] ?: 0)
      is LibraryShelf.OfCollection -> {
        val roms = collectionRoms[shelf.collection.kind to shelf.collection.id].orEmpty()
        ShelfSummary(shelf, shelf.collection.romCount, roms.count { it in onDevice })
      }
    }
  }
}

/**
 * The platforms of [collection]'s games, in [platforms]' order, which a choice of app for the
 * collection's games is made for one at a time.
 */
fun collectionPlatforms(
  collection: Collection,
  platforms: List<Platform>,
  romPlatforms: Map<Long, Long>,
  collectionRoms: Map<Pair<CollectionKind, Long>, Set<Long>>,
): List<Platform> {
  val ids =
    collectionRoms[collection.kind to collection.id].orEmpty().mapNotNullTo(mutableSetOf()) {
      romPlatforms[it]
    }
  return platforms.filter { it.id in ids }
}

/** The games the On device tab shows from [roms]: those on the device or on their way. */
fun List<Rom>.onDevice(states: Map<Long, DeviceState>): List<Rom> = filter { it.id in states }

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
