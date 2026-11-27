package dev.sargunv.handheld.ui.library

import androidx.compose.runtime.Immutable
import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.LibraryGame
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.ui.places.gameCount
import dev.sargunv.handheld.ui.places.onDeviceCount
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
 * Whether [game] is on this device, by [states] for a ROM. A local game is only on the device, so
 * it's always there.
 */
fun deviceStateOf(game: LibraryGame, states: Map<Long, DeviceState>): DeviceState? =
  when (game) {
    is Rom -> states[game.id]
    is LocalGame -> DeviceState.OnDevice
  }

/** The test tag and focus key of [game]'s cover in a grid of games. */
fun gameTag(game: LibraryGame): String =
  when (game) {
    is Rom -> "game:${game.id}"
    is LocalGame -> "game:local:${game.path}"
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

/**
 * The platforms the Platforms tab lists: those with games on the server or [localGames] on the
 * device, in the order given.
 */
fun platformShelves(
  platforms: List<Platform>,
  localGames: List<LocalGame> = emptyList(),
): List<LibraryShelf> {
  val local = localGames.mapTo(mutableSetOf()) { it.platformId }
  return platforms.filter { it.romCount > 0 || it.id in local }.map { LibraryShelf.OfPlatform(it) }
}

/**
 * The collections the Collections tab lists: all of them, in the order given, as Home lists them,
 * so each one Home opens is there.
 */
fun collectionShelves(collections: List<Collection>): List<LibraryShelf> = collections.map {
  LibraryShelf.OfCollection(it)
}

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
    get() = "${shelf.title}, ${gameCount(games)}, ${onDeviceCount(onDevice)}"
}

/**
 * The [shelves] with their counts. [romPlatforms] gives each game's platform by ROM id,
 * [collectionRoms] each collection's games by its kind and id, and [states] says which games are on
 * the device. A platform counts its [localGames] too, which are on the device.
 */
fun shelfSummaries(
  shelves: List<LibraryShelf>,
  romPlatforms: Map<Long, Long>,
  collectionRoms: Map<Pair<CollectionKind, Long>, Set<Long>>,
  states: Map<Long, DeviceState>,
  localGames: List<LocalGame> = emptyList(),
): List<ShelfSummary> {
  val onDevice = states.filterValues { it == DeviceState.OnDevice }.keys
  val onDeviceByPlatform = onDevice.mapNotNull(romPlatforms::get).groupingBy { it }.eachCount()
  val localByPlatform = localGames.groupingBy { it.platformId }.eachCount()
  return shelves.map { shelf ->
    when (shelf) {
      is LibraryShelf.OfPlatform -> {
        val local = localByPlatform[shelf.platform.id] ?: 0
        ShelfSummary(
          shelf,
          shelf.platform.romCount + local,
          (onDeviceByPlatform[shelf.platform.id] ?: 0) + local,
        )
      }
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

/**
 * The games [filter] keeps, in [sort]'s order. [states] says which ROMs are on the device; local
 * games always are. Recently added puts a local game by when its file last changed.
 */
fun <T : LibraryGame> List<T>.shown(
  filter: LibraryFilter,
  sort: LibrarySort,
  states: Map<Long, DeviceState>,
): List<T> {
  val kept =
    when (filter) {
      LibraryFilter.All -> this
      LibraryFilter.OnDevice -> filter { deviceStateOf(it, states) == DeviceState.OnDevice }
      LibraryFilter.NotOnDevice -> filter { deviceStateOf(it, states) != DeviceState.OnDevice }
    }
  return when (sort) {
    LibrarySort.Name ->
      kept.sortedWith(compareBy<T> { it.sortKey.lowercase() }.then(sameNameOrder()))
    LibrarySort.RecentlyAdded ->
      kept.sortedWith(compareByDescending<T> { it.addedAt }.then(sameNameOrder<T>().reversed()))
  }
}

/** The order of games that sort the same otherwise: ROMs by id, then local games by path. */
private fun <T : LibraryGame> sameNameOrder(): Comparator<T> =
  compareBy({ (it as? Rom)?.id ?: Long.MAX_VALUE }, { (it as? LocalGame)?.path })

/** The letter the A–Z jump files [game] under: its sort key's first letter, or # for the rest. */
fun jumpLetter(game: LibraryGame): Char {
  val first = game.sortKey.firstOrNull()?.uppercaseChar()
  return if (first != null && first in 'A'..'Z') first else '#'
}

/** Each letter's first game in [games], in the order the letters first appear. */
fun jumpIndex(games: List<LibraryGame>): Map<Char, Int> = buildMap {
  games.forEachIndexed { index, game -> getOrPut(jumpLetter(game)) { index } }
}
