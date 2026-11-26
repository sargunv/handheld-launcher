package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.domain.LocalGame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** How an upload of a local game to RomM ended, once the file is on the server. */
enum class UploadResult {
  /** The server scanned the game's platform, so the game is in its library now. */
  Added,

  /**
   * The server has the file, but didn't scan it into its library: the account may not run scans, or
   * the scan didn't finish in time. It joins the library at the server's next scan.
   */
  AwaitingScan,
}

/**
 * Uploads [game]'s file to the server [client] reaches, into its platform's folder under the same
 * name, in chunks of [chunkSize] bytes, and asks the server to scan the platform so the game joins
 * its library. [onProgress] hears the bytes sent of the total after each chunk.
 *
 * A canceled or failed upload cancels it on the server too, which drops what it received.
 *
 * @throws RommException when the server refuses the upload, such as when the account may not upload
 *   games or the platform's folder already holds a file of that name, or can't be reached.
 * @throws kotlinx.io.IOException when the file can't be read.
 */
suspend fun uploadLocalGame(
  client: RommClient,
  game: LocalGame,
  chunkSize: Int = UPLOAD_CHUNK_SIZE,
  scanPoll: Duration = 2.seconds,
  scanTimeout: Duration = 3.minutes,
  onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
): UploadResult =
  withContext(Dispatchers.IO) {
    val path = Path(game.path)
    val total = SystemFileSystem.metadataOrNull(path)?.size ?: game.sizeBytes
    val chunks = ((total + chunkSize - 1) / chunkSize).toInt()
    val uploadId = client.startUpload(game.platformId, game.fileName, total, chunks)
    try {
      SystemFileSystem.source(path).buffered().use { source ->
        var sent = 0L
        for (index in 0 until chunks) {
          val bytes = source.readByteArray(minOf(chunkSize.toLong(), total - sent).toInt())
          client.uploadChunk(uploadId, index, bytes)
          sent += bytes.size
          onProgress(sent, total)
        }
      }
      client.completeUpload(uploadId)
    } catch (e: Throwable) {
      withContext(NonCancellable) {
        try {
          client.cancelUpload(uploadId)
        } catch (_: RommException) {
          // The server drops an upload left unfinished in time.
        }
      }
      throw e
    }
    scan(client, game.platformId, scanPoll, scanTimeout)
  }

/** Scans the platform [platformId] on the server, and waits a while for the scan to finish. */
private suspend fun scan(
  client: RommClient,
  platformId: Long,
  poll: Duration,
  timeout: Duration,
): UploadResult {
  val task =
    try {
      client.scanPlatform(platformId)
    } catch (_: RommException.Status) {
      // The account may upload but not run scans.
      return UploadResult.AwaitingScan
    }
  suspend fun statusOf(task: String) =
    try {
      client.taskStatus(task)
    } catch (_: RommException.NotFound) {
      // The server forgets a task some time after it ends.
      "finished"
    }
  val status =
    withTimeoutOrNull(timeout) {
      var status = statusOf(task)
      while (status !in ScanEnded) {
        delay(poll)
        status = statusOf(task)
      }
      status
    }
  return if (status == "finished") UploadResult.Added else UploadResult.AwaitingScan
}

/** The statuses of a server task that has ended, one way or another. */
private val ScanEnded = setOf("finished", "failed", "stopped", "canceled")

/**
 * The size of each chunk of an upload: 8 MiB, well under the server's 64 MiB limit, so a chunk that
 * fails costs little to send again.
 */
const val UPLOAD_CHUNK_SIZE = 8 * 1024 * 1024
