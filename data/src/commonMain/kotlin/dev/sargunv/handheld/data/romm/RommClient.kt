package dev.sargunv.handheld.data.romm

import dev.sargunv.handheld.data.httpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.http.parseQueryString
import io.ktor.http.takeFrom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * A client for a RomM server's API.
 *
 * Every call throws [RommException] when the request fails. The client closes [engine] when it's
 * closed.
 *
 * @param baseUrl the server's address, such as `https://romm.example.com`, which may include a path
 *   when the server is served under one.
 * @param token a client API token, sent as a bearer token. Without one, only the calls that need no
 *   sign-in work.
 */
class RommClient
internal constructor(
  baseUrl: String,
  private val token: String? = null,
  private val engine: HttpClientEngine = OkHttp.create(),
) : AutoCloseable {
  /** The server's address, without a trailing slash. */
  val baseUrl: Url = Url(baseUrl.trimEnd('/'))

  private val http =
    httpClient(engine) {
      install(ContentEncoding) {
        gzip()
        deflate()
      }
    }

  override fun close() {
    http.close()
    engine.close()
  }

  /**
   * For a server at a plain `http://` address, its `https://` address when it redirects there, or
   * null when it doesn't. It asks without credentials and follows no redirect, so nothing secret
   * goes out before the address is known to be encrypted.
   */
  internal suspend fun httpsAddress(): String? {
    if (baseUrl.protocol != URLProtocol.HTTP) return null
    val response = network {
      httpClient(engine) { followRedirects = false }
        .use { noRedirects ->
          noRedirects.request {
            method = HttpMethod.Get
            url {
              takeFrom(baseUrl)
              appendPathSegments("api", "heartbeat")
            }
          }
        }
    }
    val location = response.headers[HttpHeaders.Location] ?: return null
    if (response.status.value !in 300..399) return null
    val target = URLBuilder(baseUrl.toString()).takeFrom(location).build()
    if (target.protocol != URLProtocol.HTTPS) return null
    val path = target.encodedPath.removeSuffix("/api/heartbeat").trimEnd('/')
    return URLBuilder(target)
      .apply {
        encodedPath = path
        parameters.clear()
        fragment = ""
      }
      .buildString()
  }

  /** The user the token belongs to. */
  internal suspend fun me(): RommUser =
    get<RommUser?>("users", "me") ?: throw RommException.Unauthorized(null)

  internal suspend fun platforms(): List<RommPlatform> = get("platforms")

  /**
   * Every ROM with its files, or only those the server changed after [updatedAfter], a page at a
   * time. Paging by offset can skip or repeat a ROM when the server's ROMs change during the walk.
   */
  internal fun allRoms(updatedAfter: Instant?, pageSize: Int = 200): Flow<RommPage<RommRom>> =
    flow {
      var offset = 0
      var total: Int? = null
      while (true) {
        val page =
          romPage(pageSize, offset, withTotal = total == null) {
            updatedAfter?.let { append("updated_after", it.toString()) }
            append("order_by", "id")
            append("order_dir", "asc")
            append("with_files", "true")
          }
        total = total ?: page.total
        if (page.items.isEmpty()) break
        emit(page)
        offset += page.items.size
        if (total != null && offset >= total) break
      }
    }

  /** The [limit] ROMs the user played last, most recent first, without their files. */
  internal suspend fun recentlyPlayed(limit: Int): List<RommRom> =
    romPage(limit, offset = 0, withTotal = false) {
        append("last_played", "true")
        append("order_by", "last_played")
        append("order_dir", "desc")
        append("with_files", "false")
      }
      .items

  private suspend fun romPage(
    limit: Int,
    offset: Int,
    withTotal: Boolean,
    filters: ParametersBuilder.() -> Unit,
  ) =
    get<RommPage<RommRom>>("roms") {
      filters()
      append("with_total", withTotal.toString())
      // The indexes back the web page's virtual scroll, and cost a scan of the whole result.
      append("with_char_index", "false")
      append("with_rom_id_index", "false")
      append("with_filter_values", "false")
      append("limit", limit.toString())
      append("offset", offset.toString())
    }

  /** The ids of every ROM the user can see. */
  internal suspend fun romIds(): List<Long> = get("roms", "identifiers")

  internal suspend fun rom(id: Long): RommRom = get("roms", id.toString())

  internal suspend fun collections(): List<RommCollection> = get("collections")

  internal suspend fun smartCollections(): List<RommSmartCollection> = get("collections", "smart")

  /** The firmware the server has for a platform. */
  internal suspend fun firmware(platformId: Long): List<RommFirmware> =
    get("firmware") { append("platform_id", platformId.toString()) }

  /** The user's saves of a ROM. */
  suspend fun saves(romId: Long): List<RommSave> =
    get("saves") { append("rom_id", romId.toString()) }

  /** The install requests for a device that are pending or that it has taken, oldest first. */
  internal suspend fun installRequests(deviceId: String): List<RommInstallRequest> =
    get("devices", deviceId, "installs")

  /**
   * Takes every pending install request for a device, and returns all the requests it has taken,
   * oldest first. The device then reports how each ended with [updateInstallRequest].
   */
  internal suspend fun claimInstallRequests(deviceId: String): List<RommInstallRequest> =
    send(HttpMethod.Post, listOf("devices", deviceId, "installs", "claim"))

  /** Reports how an install request the device took ended, which ends it on the server. */
  internal suspend fun updateInstallRequest(
    deviceId: String,
    requestId: String,
    status: RommInstallStatus,
    reason: String? = null,
  ): RommInstallRequest =
    send(HttpMethod.Put, listOf("devices", deviceId, "installs", requestId)) {
      // The server keeps at most 500 characters of the reason.
      jsonBody(RommInstallUpdate(status, reason?.take(500)))
    }

  /** Cancels an install request that's pending or taken, which ends it on the server. */
  internal suspend fun cancelInstallRequest(
    deviceId: String,
    requestId: String,
  ): RommInstallRequest =
    send(HttpMethod.Delete, listOf("devices", deviceId, "installs", requestId))

  /** Declares what this device can do, such as taking games sent from RomM's web page. */
  internal suspend fun updateDeviceCapabilities(
    deviceId: String,
    capabilities: Map<String, Boolean>,
  ) {
    request(HttpMethod.Put, listOf("devices", deviceId)) {
      jsonBody(RommDeviceCapabilities(capabilities))
    }
  }

  /**
   * Downloads one file of a ROM to [target], resuming from what [target] already holds. RomM lists
   * each file's hashes, for checking the result: a resume doesn't detect a file that changed on the
   * server in between.
   */
  internal suspend fun downloadRomFile(
    fileId: Long,
    fileName: String,
    target: Path,
    onProgress: (DownloadProgress) -> Unit = {},
  ): Long =
    http.download(target, onProgress) {
      endpoint(HttpMethod.Get, listOf("roms", fileId.toString(), "files", "content", fileName))
    }

  /** Downloads a firmware file to [target], resuming from what [target] already holds. */
  internal suspend fun downloadFirmware(firmwareId: Long, fileName: String, target: Path): Long =
    http.download(target, onProgress = {}) {
      endpoint(HttpMethod.Get, listOf("firmware", firmwareId.toString(), "content", fileName))
    }

  /** Downloads a save's file to [target], which must be empty or missing. */
  internal suspend fun downloadSave(saveId: Long, target: Path): Long =
    http.download(target, onProgress = {}) {
      endpoint(HttpMethod.Get, listOf("saves", saveId.toString(), "content"))
    }

  /**
   * Uploads [content] as a save of the ROM [romId], named [fileName].
   *
   * A save with a [slot] is the slot's newest version, and the server keeps the older ones. A save
   * with no slot stands on its own; one with the same name and [emulator] is replaced.
   *
   * Without [overwrite], the server may keep an earlier save in the slot with the same bytes
   * instead of the upload, even one from another emulator, and return that save.
   */
  internal suspend fun uploadSave(
    romId: Long,
    fileName: String,
    content: ByteArray,
    emulator: String? = null,
    slot: String? = null,
    overwrite: Boolean = false,
  ): RommSave {
    val form =
      MultiPartFormDataContent(
        formData {
          append(
            "saveFile",
            content,
            Headers.build {
              append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
              append(
                HttpHeaders.ContentDisposition,
                ContentDisposition.File.withParameter(
                    ContentDisposition.Parameters.FileName,
                    fileName,
                  )
                  .toString(),
              )
            },
          )
        },
      )
    return send(
      HttpMethod.Post,
      listOf("saves"),
      params = {
        append("rom_id", romId.toString())
        emulator?.let { append("emulator", it) }
        slot?.let { append("slot", it) }
        if (overwrite) append("overwrite", "true")
      },
    ) {
      setBody(form)
    }
  }

  /**
   * Starts uploading a game file named [fileName], of [totalSize] bytes in [totalChunks] chunks,
   * into the folder of the platform [platformId], and returns the upload's id. The server refuses a
   * file whose name its platform's folder already holds. Needs the `roms.write` scope.
   */
  internal suspend fun startUpload(
    platformId: Long,
    fileName: String,
    totalSize: Long,
    totalChunks: Int,
  ): String =
    send<UploadStarted>(HttpMethod.Post, listOf("roms", "upload", "start")) {
        header("x-upload-platform", platformId.toString())
        // A header carries only Latin-1, so the body names the file as it is.
        header(
          "x-upload-filename",
          fileName.map { if (it.code in 0x20..0x7e) it else '_' }.joinToString(""),
        )
        header("x-upload-total-size", totalSize.toString())
        header("x-upload-total-chunks", totalChunks.toString())
        jsonBody(UploadTarget(fileName))
      }
      .uploadId

  /** Sends chunk [index] of the upload [uploadId]. */
  internal suspend fun uploadChunk(uploadId: String, index: Int, bytes: ByteArray) {
    request(HttpMethod.Put, listOf("roms", "upload", uploadId)) {
      header("x-chunk-index", index.toString())
      contentType(ContentType.Application.OctetStream)
      setBody(bytes)
    }
  }

  /** Puts the upload [uploadId]'s chunks together into the game's file on the server. */
  internal suspend fun completeUpload(uploadId: String) {
    request(HttpMethod.Post, listOf("roms", "upload", uploadId, "complete"))
  }

  /** Stops the upload [uploadId], and the server drops what it received. */
  internal suspend fun cancelUpload(uploadId: String) {
    request(HttpMethod.Post, listOf("roms", "upload", uploadId, "cancel"))
  }

  /**
   * Asks the server for a quick scan of the platform [platformId], which adds the games new in its
   * folder to the library, and returns the scan task's id. Needs the `tasks.run` scope.
   */
  internal suspend fun scanPlatform(platformId: Long): String =
    send<ScanTask>(HttpMethod.Post, listOf("tasks", "scan")) {
        jsonBody(ScanRequest(listOf(platformId)))
      }
      .taskId

  /** The status of the server's task [taskId], such as `queued`, `started` or `finished`. */
  internal suspend fun taskStatus(taskId: String): String = get<ScanTask>("tasks", taskId).status

  /**
   * Starts a device sign-in. Show the user [DeviceCode.userCode] and [DeviceCode.verificationUrl],
   * then call [awaitDeviceAuth]. Needs no token.
   */
  internal suspend fun startDeviceAuth(request: DeviceAuthRequest): DeviceCode {
    val response =
      send<DeviceAuthInitResponse>(HttpMethod.Post, listOf("auth", "device", "init")) {
        jsonBody(request)
      }
    return DeviceCode(
      deviceCode = response.deviceCode,
      userCode = response.userCode,
      verificationUrl = resolve(response.verificationPathComplete),
      expiresIn = response.expiresIn.seconds,
      interval = response.interval.seconds,
    )
  }

  /**
   * Polls until the user approves [code] on the server, and returns the token it issues.
   *
   * @throws RommException.DeviceAuthDenied when the user denies it.
   * @throws RommException.DeviceAuthExpired when the code expires first.
   */
  internal suspend fun awaitDeviceAuth(code: DeviceCode): DeviceToken {
    var interval = code.interval
    var waited = Duration.ZERO
    while (waited < code.expiresIn) {
      delay(interval)
      waited += interval
      val poll =
        try {
          pollDeviceAuth(code.deviceCode)
        } catch (e: RommException.RateLimited) {
          DeviceAuthPoll.SlowDown
        }
      when (poll) {
        DeviceAuthPoll.Pending -> {}
        // RFC 8628 asks a client told to slow down to wait 5 seconds longer from then on.
        DeviceAuthPoll.SlowDown -> interval += 5.seconds
        DeviceAuthPoll.Denied -> throw RommException.DeviceAuthDenied()
        DeviceAuthPoll.Expired -> throw RommException.DeviceAuthExpired()
        is DeviceAuthPoll.Approved -> return poll.token
      }
    }
    throw RommException.DeviceAuthExpired()
  }

  private suspend fun pollDeviceAuth(deviceCode: String): DeviceAuthPoll {
    val (response, text) =
      exchange(HttpMethod.Post, listOf("auth", "device", "token")) {
        jsonBody(DeviceAuthPollRequest(deviceCode))
      }
    if (response.status.isSuccess()) {
      return DeviceAuthPoll.Approved(decode(DeviceToken.serializer(), text))
    }
    if (response.status.value == 400) {
      when (detailOf(text)) {
        "authorization_pending" -> return DeviceAuthPoll.Pending
        "slow_down" -> return DeviceAuthPoll.SlowDown
        "access_denied" -> return DeviceAuthPoll.Denied
        "expired_token" -> return DeviceAuthPoll.Expired
      }
    }
    throw errorFor(response, text)
  }

  /**
   * The absolute URL of a path the server returned, such as a cover's. RomM returns paths from its
   * own root, which is [baseUrl]'s path when the server is served under one.
   */
  internal fun resolve(path: String): String {
    val query = path.substringAfter('?', "")
    return URLBuilder(baseUrl)
      .apply {
        appendPathSegments(path.substringBefore('?').split('/').filter { it.isNotEmpty() })
        if (query.isNotEmpty()) parameters.appendAll(parseQueryString(query))
      }
      .buildString()
  }

  private suspend inline fun <reified T> get(
    vararg path: String,
    noinline params: ParametersBuilder.() -> Unit = {},
  ): T = send(HttpMethod.Get, path.toList(), params)

  /**
   * Sends a request to the API's [path], and decodes the response's body off the caller's thread,
   * since a page of ROMs is large.
   */
  private suspend inline fun <reified T> send(
    method: HttpMethod,
    path: List<String>,
    noinline params: ParametersBuilder.() -> Unit = {},
    noinline configure: HttpRequestBuilder.() -> Unit = {},
  ): T =
    withContext(Dispatchers.Default) {
      decode(serializer<T>(), request(method, path, params, configure))
    }

  /** Sends a request to the API's [path], and returns the response's body. */
  private suspend fun request(
    method: HttpMethod,
    path: List<String>,
    params: ParametersBuilder.() -> Unit = {},
    configure: HttpRequestBuilder.() -> Unit = {},
  ): String {
    val (response, text) = exchange(method, path, params, configure)
    if (!response.status.isSuccess()) throw errorFor(response, text)
    return text
  }

  /** Sends a request to the API's [path], and returns the response with its body, even an error. */
  private suspend fun exchange(
    method: HttpMethod,
    path: List<String>,
    params: ParametersBuilder.() -> Unit = {},
    configure: HttpRequestBuilder.() -> Unit = {},
  ): Pair<HttpResponse, String> = network {
    val response = http.request {
      endpoint(method, path, params)
      configure()
    }
    response to response.bodyAsText()
  }

  private inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) {
    contentType(ContentType.Application.Json)
    setBody(RommJson.encodeToString(body))
  }

  private fun HttpRequestBuilder.endpoint(
    method: HttpMethod,
    path: List<String>,
    params: ParametersBuilder.() -> Unit = {},
  ) {
    this.method = method
    url {
      takeFrom(baseUrl)
      appendPathSegments(listOf("api") + path, encodeSlash = true)
      parameters.apply(params)
    }
    token?.let { bearerAuth(it) }
  }
}

/** The JSON format of RomM's API, which tolerates fields and values this client doesn't know. */
internal val RommJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  coerceInputValues = true
}

@Serializable
private data class DeviceAuthInitResponse(
  @SerialName("device_code") val deviceCode: String,
  @SerialName("user_code") val userCode: String,
  @SerialName("verification_path_complete") val verificationPathComplete: String,
  @SerialName("expires_in") val expiresIn: Int,
  val interval: Int,
)

@Serializable
private data class DeviceAuthPollRequest(
  @SerialName("device_code") val deviceCode: String,
)

private sealed interface DeviceAuthPoll {
  data object Pending : DeviceAuthPoll

  data object SlowDown : DeviceAuthPoll

  data object Denied : DeviceAuthPoll

  data object Expired : DeviceAuthPoll

  data class Approved(val token: DeviceToken) : DeviceAuthPoll
}

internal suspend fun <T> network(block: suspend () -> T): T =
  try {
    block()
  } catch (e: IOException) {
    throw RommException.Unreachable(e)
  }

internal fun <T> decode(serializer: KSerializer<T>, text: String): T =
  try {
    RommJson.decodeFromString(serializer, text)
  } catch (e: IllegalArgumentException) {
    throw RommException.InvalidResponse("Unreadable response", e)
  }

/** The exception for an error response with body [text]. */
internal fun errorFor(response: HttpResponse, text: String): RommException {
  val detail = detailOf(text)
  return when (val status = response.status.value) {
    401 -> RommException.Unauthorized(detail)
    403 -> RommException.Forbidden(detail)
    404 -> RommException.NotFound(detail)
    429 -> RommException.RateLimited(detail)
    in 500..599 -> RommException.ServerError(status, detail)
    else -> RommException.ClientError(status, detail)
  }
}

/** The `detail` of a FastAPI error body: a message, or a list of validation errors. */
private fun detailOf(text: String): String? {
  val detail =
    try {
      RommJson.parseToJsonElement(text).jsonObject["detail"]
    } catch (e: IllegalArgumentException) {
      null
    }
  return when (detail) {
    is JsonPrimitive -> detail.contentOrNull
    is JsonArray ->
      detail
        .mapNotNull { (it as? JsonObject)?.get("msg")?.jsonPrimitive?.contentOrNull }
        .joinToString("; ")
        .ifEmpty { null }
    else -> null
  }
}

/** The body of an upload's start: the file's name, which a header can't carry outside Latin-1. */
@Serializable private data class UploadTarget(val filename: String)

@Serializable private data class UploadStarted(@SerialName("upload_id") val uploadId: String)

/** A quick scan of [platforms]. */
@Serializable private data class ScanRequest(val platforms: List<Long>, val type: String = "quick")

/** A task the server runs, such as a scan. */
@Serializable
private data class ScanTask(
  @SerialName("task_id") val taskId: String,
  val status: String,
)
