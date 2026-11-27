package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.storage.GameLayout
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** What became of a local game's file, as of the latest scan of the platforms' folders. */
sealed interface LocalGameState {
  /** The file matches none of the library's games, so it's [game]. */
  data class Local(val game: LocalGame) : LocalGameState

  /** The file is the ROM [romId]'s now, by its name or its content. */
  data class Matched(val romId: Long) : LocalGameState

  /** The file is gone, or isn't a game. */
  data object Gone : LocalGameState
}

/** The outcome of [DeviceGames.remove]. */
sealed interface Removal {
  data object Removed : Removal

  data object NotOnDevice : Removal

  /** The game's folder holds files that aren't the game's, named [names], so it stayed. */
  data class HoldsOtherFiles(val names: List<String>) : Removal

  /** Deleting failed, for [reason] if known, which may have left part of the game. */
  data class Failed(val reason: String?) : Removal
}

/**
 * The games in the platforms' folders on the [storage] targets, in the layout [GameLayout]
 * describes. A game of the library is on the device when its platform's folder holds it by the name
 * the server gives it, or holds a file of another name with its content, such as one the player
 * copied in themselves, which [ContentScanner] finds in the background. Such a file stays as it is,
 * and the game plays, syncs saves and is removed through it as through a download. A file that
 * matches none of the library's games is a local game.
 *
 * The folders are listed again, and scanned by content, when the library changes and on [rescan].
 *
 * @param storage lists the mounted storage targets, the device's own first. Finding games looks on
 *   all of them.
 * @param scope runs the scans, for as long as it's active.
 * @param contentCacheFile keeps what the scan for games by content read from each file.
 */
class DeviceGames(
  private val library: LibraryCache,
  private val storage: () -> List<StorageTarget>,
  scope: CoroutineScope,
  contentCacheFile: Path,
) {
  /**
   * A listing of the platforms' folders: where each of the library's games on the device is, by ROM
   * id, with the latest scan by content, null until the first one finishes.
   */
  private class Listing(
    val platforms: List<Platform>,
    val roms: Map<Long, String>,
    val scan: LocalScan?,
  ) {
    /** Each file or folder of the library's games as the ROM it is: of several, the lowest id. */
    val adoptions: List<Adoption> =
      roms.entries.groupBy({ it.value }, { it.key }).map { (path, ids) ->
        Adoption(GameKey.Local(path), GameKey.Romm(ids.min()))
      }
  }

  private val scanner = ContentScanner(contentCacheFile)
  private val listing = MutableStateFlow<Listing?>(null)

  /** Held while a listing is made and shown, so the one made last shows last. */
  private val listingLock = Mutex()

  private val rescans =
    MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  init {
    scope.launch(Dispatchers.IO) {
      combine(library.platforms(), library.allRoms(), rescans.onStart { emit(Unit) }) {
          platforms,
          roms,
          _ ->
          platforms to roms
        }
        // A newer change cancels the scan under way, which keeps what it read so far.
        .collectLatest { (platforms, roms) ->
          // The games by name show at once, before the slower scan by content.
          show(platforms, roms)
          show(platforms, roms, scanner.scan(storage(), platforms, roms))
        }
    }
  }

  /** The library's games on the device, by ROM id: where each one is, its file or its folder. */
  internal val onDevice: Flow<Map<Long, String>> =
    listing.filterNotNull().map { it.roms }.distinctUntilChanged()

  /**
   * Every file or folder on the device that's a game of the library, as the ROM it is, as of the
   * latest listing. A file that was a local game hands what's kept for it to that ROM.
   */
  val adoptions: Flow<List<Adoption>> =
    listing.filterNotNull().map { it.adoptions }.distinctUntilChanged()

  /**
   * Lists the platforms' folders again, for games put there or taken away, such as by a download or
   * outside the app, and scans them by content after. It returns once the listing shows.
   */
  suspend fun rescan() {
    withContext(Dispatchers.IO) { show(library.platforms().first(), library.allRoms().first()) }
    rescans.tryEmit(Unit)
  }

  /**
   * The local games: the files in the platforms' folders that match none of the library's games, by
   * name or by content, as of the latest scan.
   */
  fun localGames(): Flow<List<LocalGame>> =
    listing.mapNotNull { it?.localGames() }.distinctUntilChanged()

  /**
   * What became of the local game whose file is at [path]: still local, a game of the library now,
   * such as after the server gained it, or gone. Null until the first scan has finished.
   */
  fun localGame(path: String): Flow<LocalGameState?> =
    listing
      .map { listing ->
        val local = listing?.localGames() ?: return@map null
        local.find { it.path == path }?.let(LocalGameState::Local)
          ?: listing.adoptions
            .find { it.local.path == path }
            ?.let { LocalGameState.Matched(it.rom.romId) }
          ?: LocalGameState.Gone
      }
      .distinctUntilChanged()

  private fun Listing.localGames(): List<LocalGame>? {
    val slugs = platforms.associate { it.id to it.slug }
    return scan?.unmatched?.mapNotNull { file ->
      val slug = slugs[file.platformId] ?: return@mapNotNull null
      LocalGame(
        path = file.path,
        platformId = file.platformId,
        platformSlug = slug,
        sizeBytes = file.totalBytes,
        addedAt = Instant.fromEpochMilliseconds(file.modifiedMillis),
        otherFiles = file.otherFiles.map { it.path },
      )
    }
  }

  /**
   * Deletes [game]'s file from the device, and for a disc set, the files it lists. Only files the
   * latest scan found matching nothing, at the sizes it found, are deleted, so no other file is
   * taken for one. The sheet goes last, so a removal that fails partway leaves the game to remove
   * again.
   */
  suspend fun remove(game: LocalGame): Removal =
    // Once begun, a removal finishes even if the screen that asked goes away.
    withContext(NonCancellable + Dispatchers.IO) {
      try {
        val unmatched = listing.value?.scan?.unmatched.orEmpty()
        val found = unmatched.find { it.path == game.path && it.totalBytes == game.sizeBytes }
        // A track another local game's sheet also lists stays, for that game.
        val shared =
          unmatched
            .filter { it.path != game.path }
            .flatMapTo(mutableSetOf()) { other -> other.otherFiles.map { it.path } + other.path }
        val parts =
          found
            ?.let {
              it.otherFiles.filter { part -> part.path !in shared } +
                LocalPart(it.path, it.sizeBytes)
            }
            .orEmpty()
            .map { Path(it.path) to it.sizeBytes }
        val unchanged =
          parts.isNotEmpty() &&
            parts.all { (path, size) ->
              val metadata = SystemFileSystem.metadataOrNull(path)
              metadata?.isRegularFile == true && metadata.size == size
            }
        if (!unchanged) return@withContext Removal.NotOnDevice
        for ((path, _) in parts) SystemFileSystem.delete(path)
        Removal.Removed
      } catch (e: IOException) {
        Removal.Failed(e.message)
      } finally {
        rescan()
      }
    }

  /**
   * Deletes [rom]'s own files from the device: its file, or for a ROM of several files, its folder.
   * A folder that holds anything besides the files the server lists for the game and the playlist
   * its download wrote, such as a save an emulator keeps beside the game, stays as it is. So does
   * one that holds more files of a name than the game has.
   */
  suspend fun remove(rom: Rom): Removal =
    // Once begun, a removal finishes even if the screen that asked goes away.
    withContext(NonCancellable + Dispatchers.IO) {
      try {
        val folder = library.platform(rom.platformId)?.fsSlug
        val path =
          folder?.let { locateByName(rom, it) }
            ?: matchedFile(rom.id)?.also {
              // A file matched by content is read again first, in case it changed since the scan.
              if (!scanner.stillMatches(it, rom)) return@withContext Removal.NotOnDevice
            }
            ?: return@withContext Removal.NotOnDevice
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
          // A file is the game's while no more files have its name than the game has, so a stray
          // file that shares a name with one of the game's isn't taken for it.
          val expected =
            (rom.files.map { it.fileName } + playlistName(path.name)).groupingBy { it }.eachCount()
          val found = filesIn(path).groupingBy { it.name }.eachCount()
          val others = found.filter { (name, count) -> count > (expected[name] ?: 0) }.keys
          if (others.isNotEmpty()) return@withContext Removal.HoldsOtherFiles(others.sorted())
          deleteRecursively(path)
        } else {
          SystemFileSystem.delete(path)
        }
        Removal.Removed
      } catch (e: IOException) {
        Removal.Failed(e.message)
      } finally {
        // Even a removal that failed partway may have deleted some of the game.
        rescan()
      }
    }

  /**
   * Where [rom] is on the device: in its [platformFolder] on a storage target by its server name,
   * else in the file the latest scan matched by content.
   */
  internal fun locate(rom: Rom, platformFolder: String): Path? =
    locateByName(rom, platformFolder) ?: matchedFile(rom.id)

  private fun locateByName(rom: Rom, platformFolder: String): Path? {
    val name = GameLayout.romName(rom) ?: return null
    return storage()
      .map { Path(GameLayout.platformFolder(it.root, platformFolder), name) }
      .firstOrNull { SystemFileSystem.exists(it) }
  }

  /** The file [scan] matched to the ROM [romId] by content, while it's still there. */
  private fun matchedFile(romId: Long, scan: LocalScan? = listing.value?.scan): Path? =
    scan?.matched?.get(romId)?.let(::Path)?.takeIf {
      SystemFileSystem.metadataOrNull(it)?.isRegularFile == true
    }

  /** Lists the platforms' folders and shows what they hold, with [scan] or else the latest one. */
  private suspend fun show(platforms: List<Platform>, roms: List<Rom>, scan: LocalScan? = null) =
    listingLock.withLock {
      val latest = scan ?: listing.value?.scan
      listing.value = Listing(platforms, locateAll(platforms, roms, latest), latest)
    }

  /** Where each of [roms] is on the device, by id. Each platform's folder is listed once. */
  private fun locateAll(
    platforms: List<Platform>,
    roms: List<Rom>,
    scan: LocalScan?,
  ): Map<Long, String> {
    val targets = storage()
    val listed = platforms.map { it.fsSlug }.distinct().associateWith { entriesIn(targets, it) }
    val folders = platforms.associate { it.id to listed.getValue(it.fsSlug) }
    return buildMap {
      for (rom in roms) {
        val entries = folders[rom.platformId] ?: continue
        val name = GameLayout.romName(rom) ?: continue
        val path = entries[name] ?: matchedFile(rom.id, scan) ?: continue
        put(rom.id, path.toString())
      }
    }
  }
}

/**
 * What [platformFolder] holds on the [targets], by name. Of two entries of one name, the earlier
 * target's.
 */
private fun entriesIn(targets: List<StorageTarget>, platformFolder: String): Map<String, Path> =
  buildMap {
    for (target in targets) {
      for (entry in entriesOf(Path(GameLayout.platformFolder(target.root, platformFolder)))) {
        getOrPut(entry.name) { entry }
      }
    }
  }

/** Every file in [folder] and its subfolders. */
private fun filesIn(folder: Path): List<Path> =
  SystemFileSystem.list(folder).flatMap {
    if (SystemFileSystem.metadataOrNull(it)?.isDirectory == true) filesIn(it) else listOf(it)
  }
