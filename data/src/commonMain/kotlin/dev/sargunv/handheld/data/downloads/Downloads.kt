package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.cache.toDomain
import dev.sargunv.handheld.data.cores.JsonFile
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommInstallStatus
import dev.sargunv.handheld.data.romm.RommRomFile
import dev.sargunv.handheld.data.romm.platformHttpEngine
import dev.sargunv.handheld.domain.LocalGame
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.storage.GameLayout
import io.ktor.client.engine.HttpClientEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer

/** Storage that games can go to, such as the device's shared storage or an SD card. */
data class StorageTarget(
  /** The storage's root folder, such as `/storage/emulated/0`. */
  val root: String,
  /** The name to show, such as `Internal shared storage`. */
  val label: String,
  /**
   * What names this storage in [StorageSettings], the same each time it's mounted, such as an SD
   * card's volume UUID.
   */
  val id: String = root,
)

/** How much room a [StorageTarget] has left. */
data class StorageSpace(val target: StorageTarget, val freeBytes: Long)

/** A game in the download queue. */
@Serializable
data class Download(
  val romId: Long,
  val title: String,
  val platformSlug: String,
  /** The name of the platform's folder, which the game goes in. */
  val platformFolder: String,
  /** The size of the game on the server. */
  val sizeBytes: Long,
  val state: DownloadState = DownloadState.Queued,
  /** Why the download failed, when it did. */
  val error: String? = null,
  /** The root of the storage the game goes to, picked when the download first starts. */
  val storageRoot: String? = null,
  /** Where the game goes, picked with [storageRoot]. */
  val path: String? = null,
  /** The ROM's files to download, or all of them when empty. */
  val fileIds: List<Long> = emptyList(),
  /** The server's request to install the game, when it was sent from RomM's web page. */
  val installRequestId: String? = null,
  /** The address of the server the game is from, as signed in to when it was queued. */
  val server: String,
  /** How much has arrived, while it downloads. */
  @Transient val bytesDownloaded: Long = 0,
  /** The size of the files being downloaded, once the download knows it. */
  @Transient val totalBytes: Long = sizeBytes,
)

enum class DownloadState {
  Queued,
  Downloading,
  Failed,
}

private fun Download.status(): RomStatus =
  when (state) {
    DownloadState.Queued -> RomStatus.Queued
    DownloadState.Downloading -> RomStatus.Downloading(bytesDownloaded, totalBytes)
    DownloadState.Failed -> RomStatus.Failed(error.orEmpty())
  }

/** Whether a ROM is on the device, or how far it's got. */
sealed interface RomStatus {
  data object NotOnDevice : RomStatus

  data object Queued : RomStatus

  data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : RomStatus

  /** The download failed, and waits in the queue for a retry. */
  data class Failed(val error: String) : RomStatus

  /** The ROM is at [path]: its file, or the folder that holds its files. */
  data class OnDevice(val path: String) : RomStatus
}

/** What became of a local game's file, as of the latest scan of the platforms' folders. */
sealed interface LocalGameState {
  /** The file matches none of the library's games, so it's [game]. */
  data class Local(val game: LocalGame) : LocalGameState

  /** The file is the ROM [romId]'s now, by its name or its content. */
  data class Matched(val romId: Long) : LocalGameState

  /** The file is gone, or isn't a game. */
  data object Gone : LocalGameState
}

/**
 * The download queue: it downloads games from the signed-in server one at a time, into the layout
 * [GameLayout] describes on one of the [storage] targets, and keeps the queue in [queueFile] so a
 * restart picks up where it stopped. A game downloads next to where it goes, with a `.part` suffix,
 * and moves into place once every file has arrived, so a game in its place is always whole. A game
 * already in place is never replaced.
 *
 * A file that resumed from an earlier attempt is checked against the server's hashes, and
 * downloaded again from the start when it doesn't match.
 *
 * A game is on the device when its platform's folder holds it by the name the server gives it, or
 * holds a file of another name with its content, such as one the player copied in themselves, which
 * [ContentScanner] finds in the background. Such a file stays as it is, and the game plays, syncs
 * saves and is removed through it as through a download.
 *
 * @param library finds a platform's folder name.
 * @param storage lists the mounted storage targets, the device's own first. Finding games on the
 *   device looks on all of them.
 * @param storageSettings which of them new downloads may go to, and in what order.
 * @param scope runs the downloads, for as long as it's active.
 * @param engine makes the HTTP engine for each of the server's clients.
 * @param canWrite whether the app may write to the storage targets now. Downloads wait while it's
 *   false, such as before the user grants access, rather than failing.
 * @param contentCacheFile keeps what the scan for games by content read from each file.
 */
class DownloadManager(
  private val queueFile: Path,
  private val library: LibraryCache,
  private val credentials: CredentialStore,
  private val storage: () -> List<StorageTarget>,
  scope: CoroutineScope,
  private val engine: () -> HttpClientEngine = ::platformHttpEngine,
  private val canWrite: Flow<Boolean> = flowOf(true),
  private val storageSettings: () -> StorageSettings = { StorageSettings() },
  contentCacheFile: Path = Path(queueFile.parent ?: Path("."), "local-content.json"),
) {
  private val file = JsonFile(queueFile, ListSerializer(Download.serializer()))
  private val mutex = Mutex()
  private val entries =
    MutableStateFlow(
      // A download that was running when the app stopped starts again from where it got.
      file.read().orEmpty().map {
        if (it.state == DownloadState.Downloading) it.copy(state = DownloadState.Queued) else it
      },
    )
  private var running: Pair<Long, Job>? = null

  /** Counts the changes to which games are on the device, so the checks for them run again. */
  private val installs = MutableStateFlow(0)

  /** The queue, in the order it downloads. */
  val queue: StateFlow<List<Download>> = entries.asStateFlow()

  private val scanner = ContentScanner(contentCacheFile)
  private val scanned = MutableStateFlow(LocalScan())

  /** Whether a scan has finished since the manager started, so [scanned] says what's there. */
  private val scanDone = MutableStateFlow(false)

  /**
   * What the latest scan found in the platforms' folders besides the games by their server names:
   * games matched by content, and files that matched nothing. A scan runs when the library changes,
   * when a download finishes or a game is removed, and on [recheck].
   */
  val localScan: StateFlow<LocalScan> = scanned.asStateFlow()

  /** Counts the sign-ins and sign-outs, so the queue waits for the sign-in its games need. */
  private val signIns = MutableStateFlow(0)

  /** The next queued game of [server], the one signed in to, or null when there's none. */
  private fun nextFor(server: String?, list: List<Download>): Download? = list.firstOrNull {
    it.state == DownloadState.Queued && it.server == server
  }

  /** Deletes the staged downloads nothing resumes, once, as the manager starts. */
  internal val sweep: Job = scope.launch { sweepStaging() }

  init {
    scope.launch { sweepAsTheLibraryGrows() }
  }

  /**
   * Sweeps again as the library changes after [sweep]: a first sync fills it a page at a time, so
   * the games of later pages are swept as they come.
   */
  @OptIn(FlowPreview::class)
  private suspend fun sweepAsTheLibraryGrows() {
    sweep.join()
    library.allRoms().drop(1).debounce(1.seconds).collect { sweepStaging(it) }
  }

  init {
    scope.launch {
      while (true) {
        val seen = entries.value to signIns.value
        // A game waits while signed out, or signed in to another server than its own.
        if (nextFor(credentials.load()?.baseUrl, seen.first) == null) {
          combine(entries, signIns, ::Pair).first { it != seen }
          continue
        }
        canWrite.first { it }
        val server = credentials.load()?.baseUrl
        // Pick under the lock, so a game canceled meanwhile isn't started.
        val job =
          mutex.withLock {
            val download = nextFor(server, entries.value)
            download?.let { scope.launch { run(it) }.also { job -> running = it.romId to job } }
          } ?: continue
        job.join()
        mutex.withLock { running = null }
      }
    }
    scope.launch {
      combine(library.platforms(), library.allRoms(), installs) { platforms, roms, _ ->
          platforms to roms
        }
        // A newer change cancels the scan under way, which keeps what it read so far.
        .collectLatest { (platforms, roms) ->
          scanned.value = withContext(Dispatchers.IO) { scanner.scan(storage(), platforms, roms) }
          scanDone.value = true
        }
    }
  }

  /**
   * Deletes the downloads left staged with nothing to resume them, such as when the app's data was
   * cleared during a download: each `<name>.part` in a platform's folder on a storage target whose
   * name, less the suffix, is that of a game of the library in that folder, and that no download in
   * the queue may resume. Nothing else is touched, so other apps' files stay. It waits for the
   * library to have games.
   */
  private suspend fun sweepStaging(of: List<Rom>? = null) {
    canWrite.first { it }
    // With app data cleared, the library is empty until the first sync fills it.
    val roms = of ?: library.allRoms().first { it.isNotEmpty() }
    val platforms = library.platforms().first()
    val folders = platforms.associate { it.id to it.fsSlug }
    // The queue changes only under the lock, so no download starts meanwhile.
    mutex.withLock {
      val queued = entries.value
      val romsById = roms.associateBy { it.id }
      val kept = buildSet {
        for (download in queued) {
          download.path?.let { add("$it.part") }
          val name = romsById[download.romId]?.let(GameLayout::romName) ?: continue
          for (target in storage()) {
            add(
              "${Path(GameLayout.platformFolder(target.root, download.platformFolder), name)}.part",
            )
          }
        }
      }
      val names = roms.groupBy({ folders[it.platformId] }, GameLayout::romName)
      withContext(Dispatchers.IO) {
        for (target in storage()) {
          for ((folder, games) in names) {
            if (folder == null) continue
            val parts = games.filterNotNull().mapTo(mutableSetOf()) { "$it.part" }
            val dir = Path(GameLayout.platformFolder(target.root, folder))
            val entries =
              try {
                if (SystemFileSystem.metadataOrNull(dir)?.isDirectory == true) {
                  SystemFileSystem.list(dir)
                } else emptyList()
              } catch (e: IOException) {
                emptyList()
              }
            for (entry in entries) {
              if (entry.name !in parts || entry.toString() in kept) continue
              try {
                deleteRecursively(entry)
              } catch (e: IOException) {
                // The next start tries again.
              }
            }
          }
        }
      }
    }
  }

  /**
   * Checks the platforms' folders again, for games put there or taken away outside the app, such as
   * when the app comes back to the front.
   */
  fun recheck() = installs.update { it + 1 }

  /**
   * The local games: the files in the platforms' folders that match none of the library's games, by
   * name or by content, as of the latest scan. Files that aren't games, such as saves, pictures and
   * notes, are left out.
   */
  fun localGames(): Flow<List<LocalGame>> =
    combine(scanned, library.platforms(), ::localGamesOf).distinctUntilChanged()

  private fun localGamesOf(scan: LocalScan, platforms: List<Platform>): List<LocalGame> {
    val slugs = platforms.associate { it.id to it.slug }
    return scan.unmatched.mapNotNull { file ->
      val slug = slugs[file.platformId] ?: return@mapNotNull null
      if (!isGameFile(file.path)) return@mapNotNull null
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
   * What became of the local game whose file is at [path]: still local, a game of the library now,
   * such as after the server gained it, or gone. Null until the first scan has finished.
   */
  fun localGame(path: String): Flow<LocalGameState?> =
    combine(scanned, scanDone, library.platforms(), library.allRoms()) { scan, done, platforms, roms
        ->
        if (!done) return@combine null
        localGamesOf(scan, platforms)
          .find { it.path == path }
          ?.let {
            return@combine LocalGameState.Local(it)
          }
        // Of several games on the file, the lowest id, as the device's games pick.
        val ids =
          scan.matched.filterValues { it == path }.keys +
            listOfNotNull(matchedByName(Path(path), platforms, roms)?.id)
        ids.minOrNull()?.let(LocalGameState::Matched) ?: LocalGameState.Gone
      }
      .distinctUntilChanged()
      .flowOn(Dispatchers.IO)

  /** The ROM of [roms] named as [file] is, in its platform's folder on a storage target. */
  private fun matchedByName(file: Path, platforms: List<Platform>, roms: List<Rom>): Rom? {
    val folder = file.parent ?: return null
    val sharing = platforms.filter { it.fsSlug == folder.name }.mapTo(mutableSetOf()) { it.id }
    if (storage().none { GameLayout.platformFolder(it.root, folder.name) == folder.toString() }) {
      return null
    }
    if (SystemFileSystem.metadataOrNull(file) == null) return null
    return roms.firstOrNull { it.platformId in sharing && GameLayout.romName(it) == file.name }
  }

  /**
   * Deletes [game]'s file from the device, and for a disc set, the files it lists. Only files the
   * latest scan found matching nothing, at the sizes it found, are deleted, so no other file is
   * taken for one. The playlist or descriptor goes last, so a removal that fails partway leaves the
   * game to remove again.
   */
  suspend fun remove(game: LocalGame): Removal =
    // Once begun, a removal finishes even if the screen that asked goes away.
    withContext(NonCancellable + Dispatchers.IO) {
      val unmatched = scanned.value.unmatched
      val found = unmatched.find { it.path == game.path && it.totalBytes == game.sizeBytes }
      // A track another local game's playlist or descriptor also lists stays, for that game.
      val shared =
        unmatched
          .filter { it.path != game.path }
          .flatMapTo(mutableSetOf()) { other -> other.otherFiles.map { it.path } + other.path }
      val parts =
        found
          ?.let {
            it.otherFiles.filter { part -> part.path !in shared } + LocalPart(it.path, it.sizeBytes)
          }
          .orEmpty()
          .map {
            Path(it.path) to it.sizeBytes
          }
      val unchanged =
        parts.isNotEmpty() &&
          parts.all { (path, size) ->
            val metadata = SystemFileSystem.metadataOrNull(path)
            metadata?.isRegularFile == true && metadata.size == size
          }
      if (!unchanged) {
        installs.update { it + 1 }
        return@withContext Removal.NotOnDevice
      }
      try {
        for ((path, _) in parts) SystemFileSystem.delete(path)
        Removal.Removed
      } catch (e: IOException) {
        Removal.Failed(e.message ?: "The game's files couldn't be deleted")
      } finally {
        installs.update { it + 1 }
      }
    }

  /**
   * Adds [rom] to the end of the queue, unless it's in the queue or on the device already, or the
   * device is signed out. Returns whether it was added.
   */
  suspend fun enqueue(rom: Rom): Boolean {
    val folder = library.platform(rom.platformId)?.fsSlug ?: return false
    return enqueue(rom, folder, emptyList(), null)
  }

  private suspend fun enqueue(
    rom: Rom,
    platformFolder: String,
    fileIds: List<Long>,
    installRequestId: String?,
  ): Boolean {
    if (withContext(Dispatchers.IO) { locate(rom, platformFolder) } != null) return false
    val server = credentials.load()?.baseUrl ?: return false
    return edit { list ->
      if (list.any { it.romId == rom.id }) return@edit list
      list +
        Download(
          romId = rom.id,
          title = rom.title,
          platformSlug = rom.platformSlug,
          platformFolder = platformFolder,
          sizeBytes =
            if (fileIds.isEmpty()) rom.sizeBytes
            else rom.files.filter { it.id in fileIds }.sumOf { it.sizeBytes },
          fileIds = fileIds,
          installRequestId = installRequestId,
          server = server,
        )
    }
  }

  /**
   * Tells the queue the device signed in or out. After a sign-in to [server], the games another
   * server queued leave the queue, without telling either server, since their ids are that
   * server's; the rest download again.
   */
  suspend fun signedIn(server: String?) {
    if (server != null) {
      for (download in entries.value) {
        if (download.server != server) cancel(download.romId, report = false)
      }
    }
    signIns.value++
  }

  /** Queues a failed download again, where it was in the queue. */
  suspend fun retry(romId: Long) {
    edit { list ->
      list.map {
        if (it.romId == romId && it.state == DownloadState.Failed) {
          it.copy(state = DownloadState.Queued, error = null)
        } else it
      }
    }
  }

  /**
   * Stops a download and takes it out of the queue, deleting what it downloaded. With [report], the
   * server that asked for the game, if one did, hears that it was canceled.
   */
  suspend fun cancel(romId: Long, report: Boolean = true) =
    // Once begun, a cancel finishes even if the screen that asked goes away.
    withContext(NonCancellable) {
      val job = mutex.withLock { running?.takeIf { it.first == romId }?.second }
      job?.cancel()
      job?.join()
      val download = entries.value.find { it.romId == romId } ?: return@withContext
      withContext(Dispatchers.IO) { download.path?.let { deleteRecursively(Path("$it.part")) } }
      edit { list -> list.filterNot { it.romId == romId } }
      if (report) report(download, RommInstallStatus.Cancelled)
    }

  /** Whether [rom] is on the device, or how far its download has got. */
  fun status(rom: Rom): Flow<RomStatus> = flow {
    val folder = library.platform(rom.platformId)?.fsSlug
    emitAll(
      combine(entries.map { list -> list.find { it.romId == rom.id } }, installs, scanned) {
          download,
          _,
          _ ->
          download
        }
        .map { download ->
          download?.status()
            ?: folder?.let { locate(rom, it) }?.let { RomStatus.OnDevice(it.toString()) }
            ?: RomStatus.NotOnDevice
        }
        .distinctUntilChanged(),
    )
  }
    .flowOn(Dispatchers.IO)

  /**
   * The library's games that are on the device or in the queue, by ROM id. A game missing here is
   * only on the server. It checks the platforms' folders again when the library changes, when a
   * download finishes or a game is removed through this queue, on [recheck], and when a scan finds
   * games by content.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun statuses(): Flow<Map<Long, RomStatus>> {
    val installed =
      combine(library.platforms(), library.allRoms(), installs, scanned) { platforms, roms, _, _ ->
          platforms to roms
        }
        .mapLatest { (platforms, roms) -> installedRoms(platforms, roms) }
        .flowOn(Dispatchers.IO)
    return combine(installed, entries) { onDevice, queue ->
      onDevice + queue.associate { it.romId to it.status() }
    }
  }

  /**
   * The [roms] in their platform's folder on any storage target, by id: by their server names, else
   * in a file the scan matched by content.
   */
  private fun installedRoms(
    platforms: List<Platform>,
    roms: List<Rom>,
  ): Map<Long, RomStatus.OnDevice> {
    val folders = platforms.associate { it.id to it.fsSlug }
    val targets = storage()
    // Each folder is listed once, however many games it holds.
    val listed = mutableMapOf<String, Set<String>>()
    fun namesIn(folder: String) =
      listed.getOrPut(folder) {
        val path = Path(folder)
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
          SystemFileSystem.list(path).mapTo(mutableSetOf()) { it.name }
        } else emptySet()
      }
    return buildMap {
      for (rom in roms) {
        val platformFolder = folders[rom.platformId] ?: continue
        val name = GameLayout.romName(rom) ?: continue
        val folder =
          targets
            .map { GameLayout.platformFolder(it.root, platformFolder) }
            .firstOrNull { name in namesIn(it) }
        if (folder != null) {
          put(rom.id, RomStatus.OnDevice(Path(folder, name).toString()))
        } else {
          matchedFile(rom.id)?.let { put(rom.id, RomStatus.OnDevice(it.toString())) }
        }
      }
    }
  }

  /** The file the scan matched to the ROM [romId] by content, while it's still there. */
  private fun matchedFile(romId: Long): Path? =
    scanned.value.matched[romId]?.let(::Path)?.takeIf {
      SystemFileSystem.metadataOrNull(it)?.isRegularFile == true
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
      val folder = library.platform(rom.platformId)?.fsSlug
      val path =
        folder?.let { locateByName(rom, it) }
          ?: matchedFile(rom.id)?.also {
            // A file matched by content is read again first, in case it changed since the scan.
            if (!scanner.stillMatches(it, rom)) {
              installs.update { count -> count + 1 }
              return@withContext Removal.NotOnDevice
            }
          }
          ?: return@withContext Removal.NotOnDevice
      try {
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
        Removal.Failed(e.message ?: "The game's files couldn't be deleted")
      } finally {
        // Even a removal that failed partway may have deleted some of the game.
        installs.update { it + 1 }
      }
    }

  /** Every file in [folder] and its subfolders. */
  private fun filesIn(folder: Path): List<Path> =
    SystemFileSystem.list(folder).flatMap {
      if (SystemFileSystem.metadataOrNull(it)?.isDirectory == true) filesIn(it) else listOf(it)
    }

  /** The room each storage target has left. */
  suspend fun storageSpace(): List<StorageSpace> =
    withContext(Dispatchers.IO) { storage().map { StorageSpace(it, freeBytes(it.root)) } }

  /**
   * Takes the games sent to this device from RomM's web page and queues them. A game that's on the
   * device already is reported to the server as installed.
   *
   * @throws RommException when the server can't be reached.
   */
  suspend fun takeInstallRequests() {
    val credentials = credentials.load() ?: return
    val deviceId = credentials.deviceId ?: return
    client(credentials).use { client ->
      val listed = client.installRequests(deviceId)
      val taken =
        if (listed.any { it.status == RommInstallStatus.Pending }) {
          client.claimInstallRequests(deviceId)
        } else listed
      for (request in taken.filter { it.status == RommInstallStatus.Taken }) {
        if (entries.value.any { it.installRequestId == request.id }) continue
        val remote = client.rom(request.romId)
        val rom = remote.toDomain(client)
        val queued = edit { list ->
          list.map {
            when {
              it.romId != rom.id -> it
              // Sent again after it failed: this request takes it over and tries again.
              it.state == DownloadState.Failed ->
                it.copy(
                  installRequestId = request.id,
                  fileIds = request.fileIds,
                  state = DownloadState.Queued,
                  error = null,
                )
              it.installRequestId == null -> it.copy(installRequestId = request.id)
              else -> it
            }
          }
        }
        if (queued) continue
        if (!enqueue(rom, remote.platformFsSlug, request.fileIds, request.id)) {
          client.updateInstallRequest(deviceId, request.id, RommInstallStatus.AlreadyInstalled)
        }
      }
    }
  }

  /**
   * Where [rom] is on the device, if it's on any storage target: by its server name, else in a file
   * the scan matched by content.
   */
  private fun locate(rom: Rom, platformFolder: String): Path? =
    locateByName(rom, platformFolder) ?: matchedFile(rom.id)

  /** Where [rom] is on the device by its server name, if it's on any storage target. */
  private fun locateByName(rom: Rom, platformFolder: String): Path? {
    val name = GameLayout.romName(rom) ?: return null
    return storage()
      .map { Path(GameLayout.platformFolder(it.root, platformFolder), name) }
      .firstOrNull { SystemFileSystem.exists(it) }
  }

  private suspend fun run(download: Download) {
    setState(download.romId) { it.copy(state = DownloadState.Downloading, error = null) }
    try {
      val outcome = withContext(Dispatchers.IO) { fetch(download) }
      // The game is in place, so the checks for it see it before it leaves the queue.
      installs.update { it + 1 }
      // A request from the server may have joined the download while it ran.
      var current = download
      edit { list ->
        list.find { it.romId == download.romId }?.let { current = it }
        list.filterNot { it.romId == download.romId }
      }
      report(current, outcome)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val error = e.message ?: "The download failed"
      edit { list ->
        list.map {
          if (it.romId == download.romId) it.copy(state = DownloadState.Failed, error = error)
          else it
        }
      }
      report(
        entries.value.find { it.romId == download.romId } ?: download,
        RommInstallStatus.Failed,
        error,
      )
    }
  }

  /** Downloads [download]'s files and moves them into place. Returns how the install ended. */
  private suspend fun fetch(download: Download): RommInstallStatus {
    val credentials =
      credentials.load() ?: throw DownloadException("Sign in to a server to download")
    return client(credentials).use { client ->
      val rom = client.rom(download.romId)
      val files =
        if (download.fileIds.isEmpty()) rom.files
        else rom.files.filter { it.id in download.fileIds }
      if (files.isEmpty()) throw DownloadException("The server has no files for this game")
      val name =
        GameLayout.romName(rom.toDomain(client))
          ?: throw DownloadException("The game's name on the server isn't a file name")
      val totalBytes = files.sumOf { it.fileSizeBytes }
      // The scan found the game in a file of another name meanwhile.
      if (matchedFile(download.romId) != null) return RommInstallStatus.AlreadyInstalled

      val (root, target) =
        if (download.storageRoot != null && download.path != null) {
          download.storageRoot to Path(download.path)
        } else {
          val root = pickStorage(totalBytes)
          val target = Path(GameLayout.platformFolder(root, download.platformFolder), name)
          edit { list ->
            list.map {
              if (it.romId == download.romId) {
                it.copy(storageRoot = root, path = target.toString())
              } else it
            }
          }
          root to target
        }
      if (SystemFileSystem.exists(target)) return RommInstallStatus.AlreadyInstalled
      val staging = Path("$target.part")
      val multiFile = rom.files.size > 1
      val targets = files.map {
        if (multiFile) Path(staging, relativePath(rom.files, it)) else staging
      }
      val needed = totalBytes - targets.sumOf { SystemFileSystem.metadataOrNull(it)?.size ?: 0L }
      val storage = storage().find { it.root == root } ?: StorageTarget(root, root)
      val free = freeBytes(root)
      if (needed > free) throw notEnoughSpace(storage, needed, free)

      setState(download.romId) { it.copy(totalBytes = totalBytes) }
      var done = 0L
      for ((file, path) in files.zip(targets)) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        downloadFile(client, file, path) { bytes ->
          setState(download.romId) { it.copy(bytesDownloaded = done + bytes) }
        }
        done += file.fileSizeBytes
      }
      if (multiFile) {
        val paths = files.map { relativePath(rom.files, it) }
        GameLayout.playlist(PlatformSlug(rom.platformSlug), paths)?.let {
          SystemFileSystem.sink(Path(staging, playlistName(name))).buffered().use { sink ->
            sink.writeString(it.joinToString("\n", postfix = "\n"))
          }
        }
      }
      if (SystemFileSystem.exists(target)) return RommInstallStatus.AlreadyInstalled
      SystemFileSystem.atomicMove(staging, target)
      RommInstallStatus.Done
    }
  }

  /** The root of the first storage target that's on with room for [bytes]. */
  private fun pickStorage(bytes: Long): String =
    chooseStorage(storage(), storageSettings(), bytes) { freeBytes(it.root) }.root

  /**
   * Downloads [file] to [path], resuming what's there. A resumed file that doesn't match the
   * server's hashes downloads again from the start.
   */
  private suspend fun downloadFile(
    client: RommClient,
    file: RommRomFile,
    path: Path,
    onBytes: (Long) -> Unit,
  ) {
    val result =
      client.downloadRomFile(file.id, file.fileName, path) { onBytes(it.bytesDownloaded) }
    if (
      result.resumedFrom > 0 &&
        matchesServerHashes(path, file.md5Hash, file.sha1Hash, file.crcHash) == false
    ) {
      SystemFileSystem.delete(path)
      client.downloadRomFile(file.id, file.fileName, path) { onBytes(it.bytesDownloaded) }
    }
  }

  private fun deleteRecursively(path: Path) {
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
      SystemFileSystem.list(path).forEach(::deleteRecursively)
    }
    SystemFileSystem.delete(path, mustExist = false)
  }

  private suspend fun report(
    download: Download,
    status: RommInstallStatus,
    reason: String? = null,
  ) {
    val requestId = download.installRequestId ?: return
    val credentials = credentials.load() ?: return
    val deviceId = credentials.deviceId ?: return
    try {
      client(credentials).use {
        // A device reports how a request ended, but cancels one through its own endpoint.
        if (status == RommInstallStatus.Cancelled) it.cancelInstallRequest(deviceId, requestId)
        else it.updateInstallRequest(deviceId, requestId, status, reason)
      }
    } catch (e: RommException) {
      // The request stays taken, and the next poll finds the game on the device or queues it.
    }
  }

  private fun client(credentials: RommCredentials) =
    RommClient(credentials.baseUrl, credentials.token, engine())

  /** Changes the queue without storing it, for what a restart doesn't need. */
  private fun setState(romId: Long, change: (Download) -> Download) = entries.update { list ->
    list.map { if (it.romId == romId) change(it) else it }
  }

  /** Changes the queue and stores it. Returns whether it changed. */
  private suspend fun edit(change: (List<Download>) -> List<Download>): Boolean = mutex.withLock {
    val before = entries.value
    val after = change(before)
    if (after == before) return@withLock false
    // Once stored, the change shows, even when the caller is cancelled meanwhile.
    withContext(NonCancellable) {
      withContext(Dispatchers.IO) { file.write(after) }
      entries.value = after
    }
    true
  }
}

/** The outcome of [DownloadManager.remove]. */
sealed interface Removal {
  data object Removed : Removal

  data object NotOnDevice : Removal

  /** The game's folder holds files that aren't the game's, named [names], so it stayed. */
  data class HoldsOtherFiles(val names: List<String>) : Removal

  /** Deleting failed, for [reason], which may have left part of the game. */
  data class Failed(val reason: String) : Removal
}

/**
 * Whether the file at [path] may be a game, as far as its extension tells: saves, save states,
 * pictures, patches and notes that sit beside games aren't.
 */
internal fun isGameFile(path: String): Boolean {
  val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
  return extension !in NotGames && !extension.matches(StateExtension)
}

/**
 * The extensions of files that sit beside games but aren't games: saves, pictures, patches and
 * cheats, notes and settings, and files another app is still writing. Not `.md`, a Mega Drive
 * ROM's.
 */
private val NotGames =
  ("srm sav sa1 dsv eep fla sra mpk rtc mcr mcd png jpg jpeg gif bmp webp ips bps ups xdelta cht " +
      "txt nfo pdf xml json ini cfg log db tmp bak")
    .split(' ')
    .toSet()

/** Save states, such as `.state`, `.state1` or `.st0`, and RetroArch's `.auto`. */
private val StateExtension = Regex("""state\d*|st\d+|auto""")

/** The playlist a download writes in the folder of a ROM of several files, named [folder]. */
private fun playlistName(folder: String) = "${folder.removeSuffix(".m3u")}.m3u"

/** A download that can't go on until something changes, such as freeing space. */
class DownloadException(message: String) : Exception(message)

internal fun notEnoughSpace(target: StorageTarget, needed: Long, free: Long) =
  DownloadException(
    "Not enough space on ${target.label}: needs ${formatSize(needed)}, ${formatSize(free)} free",
  )

/**
 * Where [file] goes within the folder of a ROM with several files: its path below the ROM's folder
 * on the server. A name that would climb out of the folder fails the download.
 */
internal fun relativePath(files: List<RommRomFile>, file: RommRomFile): String {
  val romFolder = files.firstOrNull { it.isTopLevel }?.filePath ?: commonFolder(files)
  val subfolder = file.filePath.removePrefix(romFolder).trim('/')
  val path = if (subfolder.isEmpty()) file.fileName else "$subfolder/${file.fileName}"
  if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
    throw DownloadException("The server named a file that leaves the game's folder: $path")
  }
  return path
}

/** The deepest folder that holds all of [files], so files nested in it keep their subfolders. */
private fun commonFolder(files: List<RommRomFile>): String =
  files
    .map { it.filePath.split('/') }
    .reduce { common, path -> common.zip(path).takeWhile { (a, b) -> a == b }.map { it.first } }
    .joinToString("/")

/** A size in bytes as people read it, such as `1.4 GB`. */
fun formatSize(bytes: Long): String {
  val units = listOf("bytes", "KB", "MB", "GB", "TB")
  var value = bytes.toDouble()
  var unit = 0
  while (value >= 1000 && unit < units.lastIndex) {
    value /= 1000
    unit++
  }
  if (unit == 0) return "$bytes bytes"
  val tenths = (value * 10).roundToLong()
  return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}
