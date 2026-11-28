package dev.sargunv.handheld.data.romm

import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.writeTo
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import java.io.File
import java.io.IOException

/**
 * Downloads the response to [request] into [target], asking for only the bytes past what [target]
 * already holds. A server that ignores the range sends the whole file, which replaces [target]'s
 * contents. [onProgress] hears the bytes [target] holds, and the whole file's size when the server
 * said.
 *
 * When the connection breaks, [target] keeps what arrived, and the next call resumes from there.
 *
 * @return the bytes [target] already held, which this download kept and didn't fetch again.
 */
internal suspend fun HttpClient.download(
  target: File,
  onProgress: (bytes: Long, total: Long?) -> Unit,
  request: HttpRequestBuilder.() -> Unit,
): Long {
  val existing = target.length()
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
            response.writeTo(target, startAt = existing, range.second, onProgress)
          }
          HttpStatusCode.OK -> response.writeTo(target, 0, response.contentLength(), onProgress)
          HttpStatusCode.RequestedRangeNotSatisfiable -> {
            // The range starts at or past the end: the target is complete, or it's longer than the
            // file is now.
            val total = parseUnsatisfiedRangeTotal(response.headers[HttpHeaders.ContentRange])
            when {
              total == existing -> existing
              existing > 0 -> null
              else -> throw errorFor(response)
            }
          }
          else -> throw errorFor(response)
        }
      }
  }
  if (result != null) return result
  target.delete()
  return download(target, onProgress, request)
}

/** Writes the body to [target] after its first [startAt] bytes, and returns [startAt]. */
private suspend fun HttpResponse.writeTo(
  target: File,
  startAt: Long,
  totalBytes: Long?,
  onProgress: (Long, Long?) -> Unit,
): Long {
  onProgress(startAt, totalBytes)
  val written =
    bodyAsChannel().writeTo(target, append = startAt > 0) { onProgress(startAt + it, totalBytes) }
  if (totalBytes != null && startAt + written != totalBytes) {
    log.w { "${request.url}: cut short at ${startAt + written} of $totalBytes bytes" }
    throw IOException("Download cut short")
  }
  return startAt
}

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
