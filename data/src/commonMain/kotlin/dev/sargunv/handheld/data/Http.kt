package dev.sargunv.handheld.data

import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json

/**
 * The app's one HTTP client, on [engine]. It returns error responses instead of throwing, decodes
 * JSON bodies leniently, and gives up on a connection that stays silent for a minute.
 */
fun httpClient(engine: HttpClientEngine = OkHttp.create()): HttpClient =
  HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
      connectTimeoutMillis = 15_000
      socketTimeoutMillis = 60_000
    }
    install(ContentEncoding) {
      gzip()
      deflate()
    }
    install(ContentNegotiation) {
      json(
        Json {
          ignoreUnknownKeys = true
          explicitNulls = false
          coerceInputValues = true
        },
      )
    }
  }

/** The body as [T], or else the exception [fail] makes of why it isn't JSON that decodes as one. */
internal suspend inline fun <reified T> HttpResponse.decode(fail: (Exception) -> Exception): T =
  try {
    body<T>()
  } catch (e: ContentConvertException) {
    throw fail(e)
  } catch (e: NoTransformationFoundException) {
    throw fail(e)
  }

/**
 * Writes the rest of this channel to [file], after what it holds when [append], telling [onBytes]
 * how many bytes it has written so far. Returns that count.
 */
internal suspend fun ByteReadChannel.writeTo(
  file: File,
  append: Boolean = false,
  onBytes: (Long) -> Unit = {},
): Long {
  var written = 0L
  FileOutputStream(file, append).use { output ->
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val count = readAvailable(buffer)
      if (count < 0) break
      output.write(buffer, 0, count)
      written += count
      onBytes(written)
    }
  }
  return written
}
