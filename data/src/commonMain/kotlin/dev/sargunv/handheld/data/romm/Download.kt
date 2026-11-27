package dev.sargunv.handheld.data.romm

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** How far a download has come. */
internal data class DownloadProgress(
  /** The bytes the target holds, counting any it held before the download resumed. */
  val bytesDownloaded: Long,
  /** The size of the whole file, when the server said. */
  val totalBytes: Long?,
)

/**
 * Downloads the response to [request] into [target], asking for only the bytes past what [target]
 * already holds. A server that ignores the range sends the whole file, which replaces [target]'s
 * contents.
 *
 * When the connection breaks, [target] keeps what arrived, and the next call resumes from there.
 *
 * @return the bytes [target] already held, which this download kept and didn't fetch again.
 */
internal suspend fun HttpClient.download(
  target: Path,
  onProgress: (DownloadProgress) -> Unit,
  request: HttpRequestBuilder.() -> Unit,
): Long {
  val existing = SystemFileSystem.metadataOrNull(target)?.size ?: 0L
  val result = network {
    prepareRequest {
      request()
      // The range counts the bytes on the wire, so they have to be the file's own bytes.
      header(HttpHeaders.AcceptEncoding, "identity")
      if (existing > 0) header(HttpHeaders.Range, "bytes=$existing-")
    }
      .execute { response ->
        when (response.status) {
          HttpStatusCode.PartialContent -> {
            val contentRange = response.headers[HttpHeaders.ContentRange]
            val range = parseContentRange(contentRange)
            if (range?.first != existing) {
              log.w { "${response.request.url}: asked for bytes from $existing, got $contentRange" }
              throw RommException.InvalidResponse("Bad range from server")
            }
            response.writeTo(target, append = true, startAt = existing, range.second, onProgress)
          }
          HttpStatusCode.OK ->
            response.writeTo(target, false, 0, response.contentLength(), onProgress)
          HttpStatusCode.RequestedRangeNotSatisfiable -> {
            // The range starts at or past the end: the target is complete, or it's longer than the
            // file is now.
            val total = parseUnsatisfiedRangeTotal(response.headers[HttpHeaders.ContentRange])
            when {
              total == existing -> existing
              existing > 0 -> null
              else -> throw errorFor(response, response.bodyAsText())
            }
          }
          else -> throw errorFor(response, response.bodyAsText())
        }
      }
  }
  if (result != null) return result
  SystemFileSystem.delete(target, mustExist = false)
  return download(target, onProgress, request)
}

private suspend fun HttpResponse.writeTo(
  target: Path,
  append: Boolean,
  startAt: Long,
  totalBytes: Long?,
  onProgress: (DownloadProgress) -> Unit,
): Long {
  val channel = bodyAsChannel()
  var downloaded = startAt
  onProgress(DownloadProgress(downloaded, totalBytes))
  SystemFileSystem.sink(target, append).buffered().use { sink ->
    while (!channel.isClosedForRead) {
      val chunk = channel.readRemaining(CHUNK_SIZE)
      val count = chunk.transferTo(sink)
      if (count > 0) {
        downloaded += count
        onProgress(DownloadProgress(downloaded, totalBytes))
      }
    }
  }
  if (totalBytes != null && downloaded != totalBytes) {
    log.w { "${request.url}: cut short at $downloaded of $totalBytes bytes" }
    throw IOException("Download cut short")
  }
  return if (append) startAt else 0
}

private const val CHUNK_SIZE = 64L * 1024

private val log = Logger.withTag("Download")

/** The first byte and the total size in a `Content-Range: bytes first-last/total` header. */
internal fun parseContentRange(header: String?): Pair<Long, Long?>? {
  val match = ContentRange.matchEntire(header?.trim() ?: return null)
  val (first, _, total) = match?.destructured ?: return null
  return first.toLong() to total.toLongOrNull()
}

/** The total size in the `Content-Range` header of a response to an unsatisfiable range. */
private fun parseUnsatisfiedRangeTotal(header: String?): Long? =
  UnsatisfiedContentRange.matchEntire(header?.trim() ?: return null)?.groupValues?.get(1)?.toLong()

private val ContentRange = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")

private val UnsatisfiedContentRange = Regex("""bytes \*/(\d+)""")
