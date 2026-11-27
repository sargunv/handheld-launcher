package dev.sargunv.handheld.data.downloads

import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.JsonFile
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.cache.toDomain
import dev.sargunv.handheld.data.romm.RommAccount
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommInstallStatus
import dev.sargunv.handheld.data.romm.RommRomFile
import dev.sargunv.handheld.data.romm.RommState
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.storage.GameLayout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
import kotlinx.serialization.builtins.ListSerializer

/** A game in the download queue. */
@Serializable
data class Download(
  val romId: Long,
  val title: String,
  /** The name of the platform's folder, which the game goes in. */
  val platformFolder: String,
  /** The size of the game on the server. */
  val sizeBytes: Long,
  val state: DownloadState = DownloadState.Queued,
  /** Why the download failed, when it did. */
  val error: String? = null,
  /** The root of the storage the game goes to, picked when the download first starts. */
  val storageRoot: String? = null,
  /** The ROM's files to download, or all of them when empty. */
  val fileIds: List<Long> = emptyList(),
  /** The server's request to install the game, when it was sent from RomM's web page. */
  val installRequestId: String? = null,
  /** The address of the server the game is from, as signed in to when it was queued. */
  val server: String,
) {
  /** Whether the download is waiting or running, rather than failed. */
  val isActive: Boolean
    get() = state != DownloadState.Failed

  /** How far the download has got, by its entry in [progress], or not at all yet. */
  fun progressIn(progress: Map<Long, DownloadProgress>): DownloadProgress =
    progress[romId] ?: DownloadProgress(0, sizeBytes)
}

enum class DownloadState {
  Queued,
  Downloading,
  Failed,
}

/** How far a running download has got. */
data class DownloadProgress(
  val bytesDownloaded: Long,
  /** The size of the files being downloaded. */
  val totalBytes: Long,
)

private fun Download.status(progress: Map<Long, DownloadProgress>): RomStatus =
  when (state) {
    DownloadState.Queued -> RomStatus.Queued
    DownloadState.Downloading ->
      progressIn(progress).let { RomStatus.Downloading(it.bytesDownloaded, it.totalBytes) }
    DownloadState.Failed -> RomStatus.Failed(error)
  }

/** Whether a ROM is on the device, or how far it's got. */
sealed interface RomStatus {
  data object NotOnDevice : RomStatus

  data object Queued : RomStatus

  data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : RomStatus

  /** The download failed, and waits in the queue for a retry. */
  data class Failed(val error: String?) : RomStatus

  /** The ROM is at [path]: its file, or the folder that holds its files. */
  data class OnDevice(val path: String) : RomStatus
}

/**
 * The download queue: it downloads games from the signed-in server one at a time, into the layout
 * [GameLayout] describes on one of the [storage] targets, and keeps the queue in [queueFile] so a
 * restart picks up where it stopped. A game downloads into the staging folder of its storage
 * target, under its ROM id, and moves into place once every file has arrived, so a game in its
 * place is always whole. A game already in place is never replaced. As the queue starts, it deletes
 * what's staged for games it doesn't hold.
 *
 * A file that resumed from an earlier attempt is checked against the server's hashes, and
 * downloaded again from the start when it doesn't match.
 *
 * While the device is signed in with a device sign-in, the queue takes the games sent to it from
 * RomM's web page, at the sign-in and every minute after.
 *
 * @param library finds a platform's folder name.
 * @param games the games on the device, which a game already there isn't queued over.
 * @param romm the sign-in, whose client downloads the games.
 * @param storage lists the mounted storage targets, the device's own first.
 * @param storageSettings which of them new downloads may go to, and in what order.
 * @param scope runs the downloads, for as long as it's active.
 * @param canWrite whether the app may write to the storage targets now. Downloads wait while it's
 *   false, such as before the user grants access, rather than failing.
 */
class DownloadManager(
  queueFile: Path,
  private val library: LibraryCache,
  private val games: DeviceGames,
  private val romm: StateFlow<RommState>,
  private val storage: () -> List<StorageTarget>,
  scope: CoroutineScope,
  private val canWrite: Flow<Boolean>,
  private val storageSettings: () -> StorageSettings,
) {
  private val file = JsonFile(queueFile, ListSerializer(Download.serializer()))
  private val mutex = Mutex()
  private val entries =
    MutableStateFlow(
      // A download that was running when the app stopped starts again from where it got.
      file.readBlocking().orEmpty().map {
        if (it.state == DownloadState.Downloading) it.copy(state = DownloadState.Queued) else it
      },
    )
  private var running: Pair<Long, Job>? = null

  /** The queue, in the order it downloads. */
  val queue: StateFlow<List<Download>> = entries.asStateFlow()

  private val progressing = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())

  /** How far the running download has got, by ROM id. */
  val progress: StateFlow<Map<Long, DownloadProgress>> = progressing.asStateFlow()

  private val accounts = romm.map { it as? RommAccount }

  /** The address of the server the device is signed in to, or null while it's signed out. */
  private val signedInServer = accounts.map { it?.credentials?.baseUrl }.distinctUntilChanged()

  /**
   * The next game of [list] queued from [server], the one signed in to, or null when there's none.
   */
  private fun nextFor(list: List<Download>, server: String): Download? = list.firstOrNull {
    it.state == DownloadState.Queued && it.server == server
  }

  init {
    scope.launch {
      deleteStaleStaging()
      while (true) {
        // A game waits while signed out, signed in to another server than its own, or unable to
        // write. It downloads with the account it was picked under.
        val account =
          combine(entries, accounts, canWrite) { list, account, writable ->
              account?.takeIf { writable && nextFor(list, it.credentials.baseUrl) != null }
            }
            .filterNotNull()
            .first()
        // Pick under the lock, so a game canceled meanwhile isn't started.
        val job =
          mutex.withLock {
            nextFor(entries.value, account.credentials.baseUrl)?.let { download ->
              scope.launch { run(download, account) }.also { running = download.romId to it }
            }
          } ?: continue
        job.join()
        mutex.withLock { running = null }
      }
    }
    scope.launch {
      // At a sign-in to another server, the games of the one before leave the queue, without
      // telling either server, since their ids are that server's.
      signedInServer.filterNotNull().collect { server ->
        for (download in entries.value) {
          if (download.server != server) cancel(download.romId)
        }
      }
    }
    scope.launch {
      accounts.collectLatest { account ->
        // A token pasted from the server's web page is no device's, so nothing is sent to it.
        val deviceId = account?.credentials?.deviceId ?: return@collectLatest
        while (true) {
          try {
            takeInstallRequests(account.client, deviceId)
          } catch (e: RommException) {
            log.w(e) { "Taking the games sent to the device failed" }
          }
          delay(1.minutes)
        }
      }
    }
  }

  /**
   * Deletes what's staged for games the queue doesn't hold, such as when the app's data was cleared
   * during a download. It runs before any download starts.
   */
  private suspend fun deleteStaleStaging() {
    canWrite.first { it }
    val queued = entries.value.mapTo(mutableSetOf()) { it.romId.toString() }
    withContext(Dispatchers.IO) {
      for (target in storage()) {
        for (entry in entriesOf(stagingRoot(target.root))) {
          if (entry.name !in queued) deleteIfCan(entry)
        }
      }
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
    if (withContext(Dispatchers.IO) { games.locate(rom, platformFolder) } != null) return false
    val server = (romm.value as? RommAccount)?.credentials?.baseUrl ?: return false
    return edit { list ->
      if (list.any { it.romId == rom.id }) return@edit list
      list +
        Download(
          romId = rom.id,
          title = rom.title,
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
   * Stops a download and takes it out of the queue, deleting what it downloaded. While the device
   * is signed in to the game's server, the server hears that it was canceled, if it asked for the
   * game. A download whose game has already moved into place finishes instead.
   */
  suspend fun cancel(romId: Long) =
    // Once begun, a cancel finishes even if the screen that asked goes away.
    withContext(NonCancellable) {
      val job = mutex.withLock { running?.takeIf { it.first == romId }?.second }
      job?.cancel()
      job?.join()
      val download = entries.value.find { it.romId == romId } ?: return@withContext
      download.storageRoot?.let {
        withContext(Dispatchers.IO) { deleteIfCan(stagingFolder(it, romId)) }
      }
      edit { list -> list.filterNot { it.romId == romId } }
      val account =
        (romm.value as? RommAccount)?.takeIf { it.credentials.baseUrl == download.server }
          ?: return@withContext
      reportInstall(account, download, RommInstallStatus.Cancelled)
    }

  /**
   * Whether [rom] is on the device, or how far its download has got. A download leaves the queue
   * once [games] shows the game in its place, and the game's place is read only after the download
   * has left, so a finished download goes straight to on the device. [statuses] reads them in the
   * same order.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun status(rom: Rom): Flow<RomStatus> =
    entries
      .map { list -> list.find { it.romId == rom.id } }
      .distinctUntilChanged()
      .flatMapLatest { download ->
        if (download != null) progress.map(download::status)
        else games.onDevice.map { it[rom.id]?.let(RomStatus::OnDevice) ?: RomStatus.NotOnDevice }
      }
      .distinctUntilChanged()

  /**
   * The library's games that are on the device or in the queue, by ROM id. A game missing here is
   * only on the server.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun statuses(): Flow<Map<Long, RomStatus>> = entries.flatMapLatest { queue ->
    combine(games.onDevice, progress) { onDevice, progress ->
      onDevice.mapValues { RomStatus.OnDevice(it.value) } +
        queue.associate { it.romId to it.status(progress) }
    }
  }

  /** The room each storage target has left. */
  suspend fun storageSpace(): List<StorageSpace> =
    withContext(Dispatchers.IO) { storage().map { StorageSpace(it, freeBytes(it.root)) } }

  /**
   * Takes the games sent to the device [deviceId] from RomM's web page into the queue. A request
   * takes over the download of its game that's in the queue already without one, which starts again
   * if it failed. A game on the device already, or one another request's download has, is reported
   * to the server as installed, and the queue reports how the others end.
   *
   * @throws RommException when the server can't be reached.
   */
  private suspend fun takeInstallRequests(client: RommClient, deviceId: String) {
    val listed = client.installRequests(deviceId)
    val taken =
      if (listed.any { it.status == RommInstallStatus.Pending }) {
        client.claimInstallRequests(deviceId)
      } else listed
    for (request in taken.filter { it.status == RommInstallStatus.Taken }) {
      if (entries.value.any { it.installRequestId == request.id }) continue
      val joined = edit { list ->
        list.map {
          when {
            it.romId != request.romId -> it
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
      if (joined) continue
      val rom = client.rom(request.romId)
      if (!enqueue(rom.toDomain(client), rom.platformFsSlug, request.fileIds, request.id)) {
        client.updateInstallRequest(deviceId, request.id, RommInstallStatus.AlreadyInstalled)
      }
    }
  }

  private suspend fun run(download: Download, account: RommAccount) {
    edit { list ->
      list.map {
        if (it.romId == download.romId) it.copy(state = DownloadState.Downloading, error = null)
        else it
      }
    }
    val staged =
      try {
        withContext(Dispatchers.IO) { fetch(download, account) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return fail(download, account, e)
      }
    // From the move into place on, a cancel doesn't stop the download. Once the move lands, the
    // game is installed, and nothing fails it.
    withContext(NonCancellable) {
      val outcome =
        try {
          withContext(Dispatchers.IO) {
            staged?.moveIntoPlace() ?: RommInstallStatus.AlreadyInstalled
          }
        } catch (e: IOException) {
          return@withContext fail(download, account, e)
        }
      // The game shows on the device, and its emptied staging folder goes, before it leaves the
      // queue.
      games.rescan()
      entries.value
        .find { it.romId == download.romId }
        ?.storageRoot
        ?.let {
          withContext(Dispatchers.IO) { deleteIfCan(stagingFolder(it, download.romId)) }
        }
      // A request from the server may have joined the download while it ran.
      var current = download
      edit { list ->
        list.find { it.romId == download.romId }?.let { current = it }
        list.filterNot { it.romId == download.romId }
      }
      reportInstall(account, current, outcome)
    }
  }

  /** Marks [download] failed for [e], and tells its server, if the server asked for the game. */
  private suspend fun fail(download: Download, account: RommAccount, e: Exception) {
    val error = e.message
    edit { list ->
      list.map {
        if (it.romId == download.romId) it.copy(state = DownloadState.Failed, error = error) else it
      }
    }
    val current = entries.value.find { it.romId == download.romId } ?: download
    reportInstall(account, current, RommInstallStatus.Failed, error)
  }

  /**
   * Tells the server that sent [download] from its web page, if one did, that its install ended
   * with [status], for [reason], signed in as [account].
   */
  private suspend fun reportInstall(
    account: RommAccount,
    download: Download,
    status: RommInstallStatus,
    reason: String? = null,
  ) {
    val requestId = download.installRequestId ?: return
    val deviceId = account.credentials.deviceId ?: return
    val client = account.client
    try {
      // A device reports how a request ended, but cancels one through its own endpoint.
      if (status == RommInstallStatus.Cancelled) client.cancelInstallRequest(deviceId, requestId)
      else client.updateInstallRequest(deviceId, requestId, status, reason)
    } catch (e: RommException) {
      // The request stays taken, and the next poll finds the game on the device or queues it.
      log.w(e) { "Reporting install request $requestId as $status failed" }
    }
  }

  /** A game whose files have all arrived in [staging], to move to [target]. */
  private class Staged(val staging: Path, val target: Path) {
    /** Moves the game into place, unless a game is there already. Returns how the install ended. */
    fun moveIntoPlace(): RommInstallStatus {
      if (SystemFileSystem.exists(target)) return RommInstallStatus.AlreadyInstalled
      target.parent?.let { SystemFileSystem.createDirectories(it) }
      SystemFileSystem.atomicMove(staging, target)
      return RommInstallStatus.Done
    }
  }

  /**
   * Downloads [download]'s files with [account]'s client into its staging folder. Returns where
   * they are, or null when the game is on the device already.
   */
  private suspend fun fetch(download: Download, account: RommAccount): Staged? {
    val client = account.client
    val rom = client.rom(download.romId)
    val domainRom = rom.toDomain(client)
    // The game may have come to the device meanwhile, such as a copy the scan found.
    if (games.locate(domainRom, download.platformFolder) != null) return null
    val files =
      if (download.fileIds.isEmpty()) rom.files else rom.files.filter { it.id in download.fileIds }
    if (files.isEmpty()) throw DownloadException("No files on server")
    val name =
      GameLayout.romName(domainRom) ?: throw DownloadException("Server file name not allowed")
    val totalBytes = files.sumOf { it.fileSizeBytes }

    val root =
      download.storageRoot
        ?: pickStorage(totalBytes).also { root ->
          edit { list ->
            list.map { if (it.romId == download.romId) it.copy(storageRoot = root) else it }
          }
        }
    val target = Path(GameLayout.platformFolder(root, download.platformFolder), name)
    val staging = Path(stagingFolder(root, download.romId), name)
    val multiFile = rom.files.size > 1
    val targets = files.map {
      if (multiFile) Path(staging, relativePath(rom.files, it)) else staging
    }
    val needed = totalBytes - targets.sumOf { SystemFileSystem.metadataOrNull(it)?.size ?: 0L }
    val storage = storage().find { it.root == root } ?: StorageTarget(root, root)
    val free = freeBytes(root)
    if (needed > free) throw notEnoughSpace(storage, needed, free)

    var done = 0L
    for ((file, path) in files.zip(targets)) {
      path.parent?.let { SystemFileSystem.createDirectories(it) }
      downloadFile(client, file, path) { bytes ->
        progressing.update { it + (download.romId to DownloadProgress(done + bytes, totalBytes)) }
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
    return Staged(staging, target)
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
    val resumedFrom =
      client.downloadRomFile(file.id, file.fileName, path) { onBytes(it.bytesDownloaded) }
    if (
      resumedFrom > 0 &&
        matchesServerHashes(path, file.md5Hash, file.sha1Hash, file.crcHash) == false
    ) {
      SystemFileSystem.delete(path)
      client.downloadRomFile(file.id, file.fileName, path) { onBytes(it.bytesDownloaded) }
    }
  }

  /** Changes the queue and stores it. Returns whether it changed. */
  private suspend fun edit(change: (List<Download>) -> List<Download>): Boolean = mutex.withLock {
    val before = entries.value
    val after = change(before)
    if (after == before) return@withLock false
    // Once stored, the change shows, even when the caller is cancelled meanwhile.
    withContext(NonCancellable) {
      file.write(after)
      entries.value = after
    }
    // Only a running download has progress, and it goes once the queue shows the download's end.
    val running = after.filter { it.state == DownloadState.Downloading }.map { it.romId }
    progressing.update { it.filterKeys(running::contains) }
    true
  }
}

private val log = Logger.withTag("DownloadManager")

/** The folder on the storage target at [root] where downloads stay until they're whole. */
private fun stagingRoot(root: String) = Path(GameLayout.platformFolder(root, ".handheld-staging"))

/** The folder the ROM [romId] downloads in on the storage target at [root], under its own name. */
private fun stagingFolder(root: String, romId: Long) = Path(stagingRoot(root), romId.toString())

/** The playlist a download writes in the folder of a ROM of several files, named [folder]. */
internal fun playlistName(folder: String) = "${folder.removeSuffix(".m3u")}.m3u"

internal fun deleteRecursively(path: Path) {
  if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
    SystemFileSystem.list(path).forEach(::deleteRecursively)
  }
  SystemFileSystem.delete(path, mustExist = false)
}

/** Deletes what's staged at [path], if it can. The queue's next start deletes what's left. */
private fun deleteIfCan(path: Path) {
  try {
    deleteRecursively(path)
  } catch (e: IOException) {
    log.w(e) { "Deleting $path failed" }
  }
}

/** A download that can't go on until something changes, such as freeing space. */
internal class DownloadException(message: String) : Exception(message)

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
    throw DownloadException("Unsafe file name $path")
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
