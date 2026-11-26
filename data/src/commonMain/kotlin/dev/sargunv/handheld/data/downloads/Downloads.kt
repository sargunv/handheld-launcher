package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.cache.toDomain
import dev.sargunv.handheld.data.cores.JsonFile
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommInstallStatus
import dev.sargunv.handheld.data.romm.RommRom
import dev.sargunv.handheld.data.romm.RommRomFile
import dev.sargunv.handheld.data.romm.platformHttpEngine
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.storage.GameLayout
import io.ktor.client.engine.HttpClientEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * @param library finds a platform's folder name.
 * @param storage lists the storage targets in the order to fill them, the device's own first.
 * @param scope runs the downloads, for as long as it's active.
 * @param engine makes the HTTP engine for each of the server's clients.
 */
class DownloadManager(
  private val queueFile: Path,
  private val library: LibraryCache,
  private val credentials: CredentialStore,
  private val storage: () -> List<StorageTarget>,
  scope: CoroutineScope,
  private val engine: () -> HttpClientEngine = ::platformHttpEngine,
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

  /** The queue, in the order it downloads. */
  val queue: StateFlow<List<Download>> = entries.asStateFlow()

  init {
    scope.launch {
      while (true) {
        val next = entries.first { list -> list.any { it.state == DownloadState.Queued } }
        val download = next.first { it.state == DownloadState.Queued }
        val job = mutex.withLock {
          scope.launch { run(download) }.also { running = download.romId to it }
        }
        job.join()
        mutex.withLock { running = null }
      }
    }
  }

  /**
   * Adds [rom] to the end of the queue, unless it's in the queue or on the device already. Returns
   * whether it was added.
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

  /** Stops a download and takes it out of the queue, deleting what it downloaded. */
  suspend fun cancel(romId: Long) {
    val job = mutex.withLock { running?.takeIf { it.first == romId }?.second }
    job?.cancel()
    job?.join()
    val download = entries.value.find { it.romId == romId } ?: return
    withContext(Dispatchers.IO) { download.path?.let { deleteRecursively(Path("$it.part")) } }
    edit { list -> list.filterNot { it.romId == romId } }
    report(download, RommInstallStatus.Cancelled)
  }

  /** Whether [rom] is on the device, or how far its download has got. */
  fun status(rom: Rom): Flow<RomStatus> = flow {
    val folder = library.platform(rom.platformId)?.fsSlug
    emitAll(
      entries
        .map { list -> list.find { it.romId == rom.id } }
        .distinctUntilChanged()
        .map { download ->
          when (download?.state) {
            null ->
              folder?.let { locate(rom, it) }?.let { RomStatus.OnDevice(it.toString()) }
                ?: RomStatus.NotOnDevice
            DownloadState.Queued -> RomStatus.Queued
            DownloadState.Downloading ->
              RomStatus.Downloading(download.bytesDownloaded, download.totalBytes)
            DownloadState.Failed -> RomStatus.Failed(download.error.orEmpty())
          }
        },
    )
  }
    .flowOn(Dispatchers.IO)

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
            if (it.romId == rom.id && it.installRequestId == null) {
              it.copy(installRequestId = request.id)
            } else it
          }
        }
        if (queued) continue
        if (!enqueue(rom, remote.platformFsSlug, request.fileIds, request.id)) {
          client.updateInstallRequest(deviceId, request.id, RommInstallStatus.AlreadyInstalled)
        }
      }
    }
  }

  /** Where [rom] is on the device, if it's on any storage target. */
  private fun locate(rom: Rom, platformFolder: String): Path? {
    val name = GameLayout.romName(rom.fileName, rom.files.map { it.fileName })
    return storage()
      .map { Path(GameLayout.platformFolder(it.root, platformFolder), name) }
      .firstOrNull { SystemFileSystem.exists(it) }
  }

  private suspend fun run(download: Download) {
    setState(download.romId) { it.copy(state = DownloadState.Downloading, error = null) }
    try {
      val outcome = withContext(Dispatchers.IO) { fetch(download) }
      // A request from the server may have joined the download while it ran.
      val current = entries.value.find { it.romId == download.romId } ?: download
      edit { list -> list.filterNot { it.romId == download.romId } }
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
      val name = GameLayout.romName(rom.fsName, rom.files.map { it.fileName })
      val totalBytes = files.sumOf { it.fileSizeBytes }

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
      val targets = files.map { if (multiFile) Path(staging, relativePath(rom, it)) else staging }
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
        val paths = files.map { relativePath(rom, it) }
        GameLayout.playlist(PlatformSlug(rom.platformSlug), paths)?.let {
          SystemFileSystem.sink(Path(staging, "${name.removeSuffix(".m3u")}.m3u")).buffered().use {
            sink ->
            sink.writeString(it.joinToString("\n", postfix = "\n"))
          }
        }
      }
      if (SystemFileSystem.exists(target)) return RommInstallStatus.AlreadyInstalled
      SystemFileSystem.atomicMove(staging, target)
      RommInstallStatus.Done
    }
  }

  /** The root of the first storage target with room for [bytes]. */
  private fun pickStorage(bytes: Long): String {
    val targets = storage()
    if (targets.isEmpty()) throw DownloadException("There's no storage to download to")
    val spaces = targets.map { it to freeBytes(it.root) }
    spaces
      .firstOrNull { (_, free) -> free >= bytes }
      ?.let {
        return it.first.root
      }
    val (target, free) = spaces.maxBy { it.second }
    throw notEnoughSpace(target, bytes, free)
  }

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
    withContext(Dispatchers.IO + NonCancellable) { file.write(after) }
    entries.value = after
    true
  }
}

/** A download that can't go on until something changes, such as freeing space. */
class DownloadException(message: String) : Exception(message)

private fun notEnoughSpace(target: StorageTarget, needed: Long, free: Long) =
  DownloadException(
    "Not enough space on ${target.label}: needs ${formatSize(needed)}, ${formatSize(free)} free",
  )

/**
 * Where [file] goes within the folder of a ROM with several files: its path below the ROM's folder
 * on the server. A name that would climb out of the folder fails the download.
 */
private fun relativePath(rom: RommRom, file: RommRomFile): String {
  val romFolder = rom.files.firstOrNull { it.isTopLevel }?.filePath ?: file.filePath
  val subfolder = file.filePath.removePrefix(romFolder).trim('/')
  val path = if (subfolder.isEmpty()) file.fileName else "$subfolder/${file.fileName}"
  if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
    throw DownloadException("The server named a file that leaves the game's folder: $path")
  }
  return path
}

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
