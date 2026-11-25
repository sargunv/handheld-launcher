package dev.sargunv.handheld.data.romm

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parseQueryString
import io.ktor.http.takeFrom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
class RommClient(
  baseUrl: String,
  private val token: String? = null,
  private val engine: HttpClientEngine = platformHttpEngine(),
) : AutoCloseable {
  /** The server's address, without a trailing slash. */
  val baseUrl: Url = Url(baseUrl.trimEnd('/'))

  private val http =
    HttpClient(engine) {
      expectSuccess = false
      install(ContentEncoding) {
        gzip()
        deflate()
      }
      install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 60_000
      }
    }

  override fun close() {
    http.close()
    engine.close()
  }

  /** The server's version and public configuration. Needs no sign-in. */
  suspend fun heartbeat(): RommHeartbeat = get(RommHeartbeat.serializer(), "heartbeat")

  /** The user the token belongs to. */
  suspend fun me(): RommUser =
    get(RommUser.serializer().nullable, "users", "me") ?: throw RommException.Unauthorized(null)

  suspend fun platforms(): List<RommPlatform> =
    get(ListSerializer(RommPlatform.serializer()), "platforms")

  /** One page of the ROMs that match [query]. */
  suspend fun roms(
    query: RomQuery = RomQuery(),
    limit: Int = 50,
    offset: Int = 0,
  ): RommPage<RommRom> = romPage(query, limit, offset, withTotal = true)

  /**
   * Every ROM that matches [query], a page at a time. Paging by offset can skip or repeat a ROM
   * when the server's ROMs change during the walk.
   */
  fun allRoms(query: RomQuery = RomQuery(), pageSize: Int = 200): Flow<RommPage<RommRom>> = flow {
    require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be in 1..$MAX_PAGE_SIZE" }
    var offset = 0
    var total: Int? = null
    while (true) {
      val page = romPage(query, pageSize, offset, withTotal = total == null)
      total = total ?: page.total
      if (page.items.isEmpty()) break
      emit(page)
      offset += page.items.size
      if (total != null && offset >= total) break
    }
  }

  private suspend fun romPage(query: RomQuery, limit: Int, offset: Int, withTotal: Boolean) =
    get(RommPage.serializer(RommRom.serializer()), "roms") {
      // An empty list would still add the name, which the server reads as an empty id.
      if (query.platformIds.isNotEmpty()) {
        appendAll("platform_ids", query.platformIds.map { it.toString() })
      }
      query.collectionId?.let { append("collection_id", it.toString()) }
      query.smartCollectionId?.let { append("smart_collection_id", it.toString()) }
      query.search?.let { append("search_term", it) }
      query.updatedAfter?.let { append("updated_after", it.toString()) }
      append("order_by", query.orderBy?.apiName ?: "")
      append("order_dir", if (query.descending) "desc" else "asc")
      append("with_files", query.withFiles.toString())
      append("with_total", withTotal.toString())
      // The indexes back the web page's virtual scroll, and cost a scan of the whole result.
      append("with_char_index", "false")
      append("with_rom_id_index", "false")
      append("with_filter_values", "false")
      append("limit", limit.toString())
      append("offset", offset.toString())
    }

  /** The ids of every ROM the user can see. */
  suspend fun romIds(): List<Long> = get(ListSerializer(Long.serializer()), "roms", "identifiers")

  suspend fun rom(id: Long): RommRom = get(RommRom.serializer(), "roms", id.toString())

  /** One ROM file, by the file's id. */
  suspend fun romFile(fileId: Long): RommRomFile =
    get(RommRomFile.serializer(), "roms", fileId.toString(), "files")

  suspend fun collections(): List<RommCollection> =
    get(ListSerializer(RommCollection.serializer()), "collections")

  suspend fun smartCollections(): List<RommSmartCollection> =
    get(ListSerializer(RommSmartCollection.serializer()), "collections", "smart")

  /** The firmware the server has, for one platform or for all of them. */
  suspend fun firmware(platformId: Long? = null): List<RommFirmware> =
    get(ListSerializer(RommFirmware.serializer()), "firmware") {
      platformId?.let { append("platform_id", it.toString()) }
    }

  /** The user's saves, optionally narrowed to a ROM, a platform or the device that wrote them. */
  suspend fun saves(
    romId: Long? = null,
    platformId: Long? = null,
    deviceId: String? = null,
  ): List<RommSave> =
    get(ListSerializer(RommSave.serializer()), "saves") {
      romId?.let { append("rom_id", it.toString()) }
      platformId?.let { append("platform_id", it.toString()) }
      deviceId?.let { append("device_id", it) }
    }

  /** The user's save states, optionally narrowed to a ROM or a platform. */
  suspend fun states(romId: Long? = null, platformId: Long? = null): List<RommState> =
    get(ListSerializer(RommState.serializer()), "states") {
      romId?.let { append("rom_id", it.toString()) }
      platformId?.let { append("platform_id", it.toString()) }
    }

  suspend fun devices(): List<RommDevice> = get(ListSerializer(RommDevice.serializer()), "devices")

  suspend fun device(id: String): RommDevice = get(RommDevice.serializer(), "devices", id)

  suspend fun syncSessions(
    deviceId: String? = null,
    limit: Int = 50,
    offset: Int = 0,
  ): List<RommSyncSession> =
    get(ListSerializer(RommSyncSession.serializer()), "sync", "sessions") {
      deviceId?.let { append("device_id", it) }
      append("limit", limit.toString())
      append("offset", offset.toString())
    }

  suspend fun syncSession(id: Long): RommSyncSession =
    get(RommSyncSession.serializer(), "sync", "sessions", id.toString())

  /**
   * Downloads a ROM to [target], resuming from what [target] already holds.
   *
   * A ROM with several files downloads as a zip named [fileName], with an `.m3u` playlist when it's
   * a multi-disc game. RomM lists each file's hashes, for checking the result: a resume doesn't
   * detect a file that changed on the server in between.
   *
   * @param fileName the ROM's file name, or the zip's name for a ROM with several files.
   * @param fileIds the files to include, or all of them when empty.
   */
  suspend fun downloadRom(
    romId: Long,
    fileName: String,
    target: Path,
    fileIds: List<Long> = emptyList(),
    onProgress: (DownloadProgress) -> Unit = {},
  ): DownloadResult =
    http.download(target, onProgress) {
      endpoint(HttpMethod.Get, listOf("roms", romId.toString(), "content", fileName)) {
        if (fileIds.isNotEmpty()) append("file_ids", fileIds.joinToString(","))
      }
    }

  /** Downloads a firmware file to [target], resuming from what [target] already holds. */
  suspend fun downloadFirmware(
    firmwareId: Long,
    fileName: String,
    target: Path,
    onProgress: (DownloadProgress) -> Unit = {},
  ): DownloadResult =
    http.download(target, onProgress) {
      endpoint(HttpMethod.Get, listOf("firmware", firmwareId.toString(), "content", fileName))
    }

  /**
   * Starts a device sign-in. Show the user [DeviceCode.userCode] and [DeviceCode.verificationUrl],
   * then call [awaitDeviceAuth]. Needs no token.
   */
  suspend fun startDeviceAuth(request: DeviceAuthRequest): DeviceCode {
    val response =
      send(
        DeviceAuthInitResponse.serializer(),
        HttpMethod.Post,
        listOf("auth", "device", "init"),
        body = RommJson.encodeToString(DeviceAuthRequest.serializer(), request),
      )
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
  suspend fun awaitDeviceAuth(code: DeviceCode): DeviceToken {
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
    val body =
      RommJson.encodeToString(DeviceAuthPollRequest.serializer(), DeviceAuthPollRequest(deviceCode))
    val (response, text) = exchange(HttpMethod.Post, listOf("auth", "device", "token"), body)
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
  fun resolve(path: String): String {
    val query = path.substringAfter('?', "")
    return URLBuilder(baseUrl)
      .apply {
        appendPathSegments(path.substringBefore('?').split('/').filter { it.isNotEmpty() })
        if (query.isNotEmpty()) parameters.appendAll(parseQueryString(query))
      }
      .buildString()
  }

  private suspend fun <T> get(
    serializer: KSerializer<T>,
    vararg path: String,
    params: ParametersBuilder.() -> Unit = {},
  ): T = send(serializer, HttpMethod.Get, path.toList(), params = params)

  private suspend fun <T> send(
    serializer: KSerializer<T>,
    method: HttpMethod,
    path: List<String>,
    body: String? = null,
    params: ParametersBuilder.() -> Unit = {},
  ): T {
    val (response, text) = exchange(method, path, body, params)
    if (!response.status.isSuccess()) throw errorFor(response, text)
    return decode(serializer, text)
  }

  private suspend fun exchange(
    method: HttpMethod,
    path: List<String>,
    body: String? = null,
    params: ParametersBuilder.() -> Unit = {},
  ): Pair<HttpResponse, String> = network {
    val response = http.request {
      endpoint(method, path, params)
      if (body != null) {
        contentType(ContentType.Application.Json)
        setBody(body)
      }
    }
    response to response.bodyAsText()
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

  companion object {
    /** The largest page `GET /api/roms` serves. */
    const val MAX_PAGE_SIZE = 10_000
  }
}

/** Filters and order for listing ROMs. */
data class RomQuery(
  /** ROMs on any of these platforms, or on all platforms when empty. */
  val platformIds: List<Long> = emptyList(),
  val collectionId: Long? = null,
  val smartCollectionId: Long? = null,
  /** ROMs whose names match these words. */
  val search: String? = null,
  /** ROMs the server changed after this time. */
  val updatedAfter: Instant? = null,
  /** Whether to include each ROM's files. */
  val withFiles: Boolean = false,
  /** The order, or null for the server's default, which ranks a search by relevance. */
  val orderBy: RomOrder? = RomOrder.Id,
  val descending: Boolean = false,
)

enum class RomOrder(internal val apiName: String) {
  Id("id"),
  Name("name"),
  UpdatedAt("updated_at"),
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
    throw RommException.InvalidResponse("The server sent a response this client can't read", e)
  }

/** The exception for an error response with body [text]. */
internal fun errorFor(response: HttpResponse, text: String): RommException {
  val detail = detailOf(text)
  return when (val status = response.status.value) {
    401 -> RommException.Unauthorized(detail)
    403 -> RommException.Forbidden(detail)
    404 -> RommException.NotFound(detail)
    429 ->
      RommException.RateLimited(
        detail,
        response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds,
      )
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
