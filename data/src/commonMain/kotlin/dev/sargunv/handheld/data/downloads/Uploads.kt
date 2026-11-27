package dev.sargunv.handheld.data.downloads

import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.auth.UploadAccess
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommSession
import dev.sargunv.handheld.domain.LocalGame
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** An upload of [game] to RomM at its [phase], begun when the sign-in could do as [access] says. */
data class Upload(val game: LocalGame, val access: UploadAccess, val phase: UploadPhase) {
  val isRunning: Boolean
    get() = phase !is UploadPhase.Ended
}

sealed interface UploadPhase {
  /** Sending the file, [percent] of it once the server says how much it has. */
  data class Sending(val percent: Int?) : UploadPhase

  /** The server has the whole file, so the upload can't stop any more. */
  data object Finishing : UploadPhase

  data class Ended(val outcome: UploadOutcome) : UploadPhase
}

/** How an upload ended: with the file on the server, as an [UploadResult] says, or not. */
sealed interface UploadOutcome {
  data object Stopped : UploadOutcome

  /** The server refused the upload, or couldn't be reached. */
  data class Failed(val cause: RommException) : UploadOutcome

  /** The game's file couldn't be read. */
  data class ReadFailed(val cause: IOException) : UploadOutcome
}

/**
 * Uploads local games to the server [romm] is signed in to, one at a time, in [scope], so an upload
 * goes on when the screen that started it goes away. Once a game is uploaded, the library syncs,
 * unless the device has signed out of the server meanwhile.
 */
class UploadManager
internal constructor(private val romm: RommSession, private val scope: CoroutineScope) {
  private val latest = MutableStateFlow<Upload?>(null)

  /** The running upload, or the one that ended last until its outcome is [seen]. */
  val upload: StateFlow<Upload?> = latest.asStateFlow()

  private var job: Job? = null

  /** Uploads [game], which the sign-in may do as [access] says, unless an upload is running. */
  fun start(game: LocalGame, access: UploadAccess) {
    if (latest.value?.isRunning == true) return
    val client = romm.account?.client ?: return
    val started = Upload(game, access, UploadPhase.Sending(null))
    fun show(phase: UploadPhase) {
      latest.value = started.copy(phase = phase)
    }
    // Undispatched, so the upload is in its try before this returns, and any stop ends it.
    job =
      scope.launch(start = CoroutineStart.UNDISPATCHED) {
        latest.value = started
        val outcome =
          try {
            uploadLocalGame(
              client,
              game,
              onFinishing = { show(UploadPhase.Finishing) },
              onProgress = { sent, total ->
                if (sent < total) show(UploadPhase.Sending((sent * 100 / total).toInt()))
              },
            )
          } catch (e: CancellationException) {
            show(UploadPhase.Ended(UploadOutcome.Stopped))
            throw e
          } catch (e: RommException) {
            log.w(e) { "Upload of ${game.path} failed" }
            UploadOutcome.Failed(e)
          } catch (e: IOException) {
            log.w(e) { "Reading ${game.path} for upload failed" }
            UploadOutcome.ReadFailed(e)
          }
        show(UploadPhase.Ended(outcome))
        if (outcome is UploadResult && romm.account?.client == client) romm.syncNow()
      }
  }

  /** Stops the upload while it's sending, and the server drops what it received. */
  fun stop() {
    if (latest.value?.phase is UploadPhase.Sending) job?.cancel()
  }

  /** Marks the outcome of [upload], which has ended, as shown, so it shows no more. */
  fun seen(upload: Upload) {
    if (latest.value == upload && !upload.isRunning) latest.value = null
  }
}

/** How an upload of a local game to RomM ended, once the file is on the server. */
enum class UploadResult : UploadOutcome {
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
 * name, in chunks of at most [chunkSize] bytes, and asks the server to scan the platform so the
 * game joins its library. [onProgress] hears the bytes sent of the total after each chunk, and
 * [onFinishing] when the last has arrived and the server puts the file together.
 *
 * Until then, a canceled or failed upload cancels it on the server too, which drops what it
 * received. From then on the file is the server's: the upload finishes even when canceled, and a
 * scan that can't be followed leaves the game for the server's next one.
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
  onFinishing: () -> Unit = {},
  onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
): UploadResult =
  withContext(Dispatchers.IO) {
    val path = Path(game.path)
    val total = SystemFileSystem.metadataOrNull(path)?.size ?: game.sizeBytes
    val chunks = ((total + chunkSize - 1) / chunkSize).toInt()
    // The server sizes every chunk but the last alike, as it splits the total among them.
    val chunkLength = if (chunks == 0) 0L else (total + chunks - 1) / chunks
    // Once asked, the server holds an upload until it's canceled, so the start is waited for.
    val uploadId =
      withContext(NonCancellable) {
        client.startUpload(game.platformId, game.fileName, total, chunks)
      }
    try {
      ensureActive()
      SystemFileSystem.source(path).buffered().use { source ->
        var sent = 0L
        for (index in 0 until chunks) {
          val bytes = source.readByteArray(minOf(chunkLength, total - sent).toInt())
          client.uploadChunk(uploadId, index, bytes)
          sent += bytes.size
          onProgress(sent, total)
        }
      }
      onFinishing()
      withContext(NonCancellable) { client.completeUpload(uploadId) }
    } catch (e: Throwable) {
      withContext(NonCancellable) {
        try {
          client.cancelUpload(uploadId)
        } catch (e: RommException) {
          log.w(e) { "Couldn't cancel upload $uploadId" }
        }
      }
      throw e
    }
    try {
      scan(client, game.platformId, scanPoll, scanTimeout)
    } catch (e: RommException) {
      log.w(e) { "Couldn't follow the scan of platform ${game.platformId}" }
      UploadResult.AwaitingScan
    }
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
internal const val UPLOAD_CHUNK_SIZE = 8 * 1024 * 1024

private val log = Logger.withTag("Uploads")
