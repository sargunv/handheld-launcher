package dev.sargunv.handheld.data.achievements

import dev.sargunv.handheld.data.romm.platformHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.parameters
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A client for RetroAchievements' API, which the app reaches directly, not through RomM: it signs
 * in, and it sends the requests the built-in session's rcheevos makes.
 *
 * RetroAchievements asks every client to name itself in the User-Agent, so [userAgent] goes with
 * each request: `HandheldLauncher/<version> rcheevos/<version>`, the way RetroArch names itself.
 * The client closes [engine] when it's closed.
 */
class RetroAchievementsClient(
  private val userAgent: String,
  private val engine: HttpClientEngine = platformHttpEngine(),
  private val endpoint: String = DEFAULT_ENDPOINT,
) : AutoCloseable {
  private val http =
    HttpClient(engine) {
      expectSuccess = false
      install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
      }
    }

  override fun close() {
    http.close()
    engine.close()
  }

  /**
   * Signs in with [username] and [password], and returns the account with the token that stands in
   * for the password from then on. The password goes only to RetroAchievements, and isn't kept.
   *
   * @throws RetroAchievementsException when the server can't be reached or turns the sign-in down.
   */
  suspend fun signIn(username: String, password: String): RetroAchievementsAccount {
    val text = network {
      http
        .post(endpoint) {
          header(HttpHeaders.UserAgent, userAgent)
          setBody(
            FormDataContent(
              parameters {
                append("r", "login2")
                append("u", username)
                append("p", password)
              },
            ),
          )
        }
        .bodyAsText()
    }
    val response =
      try {
        json.decodeFromString(LoginResponse.serializer(), text)
      } catch (e: SerializationException) {
        throw RetroAchievementsException("RetroAchievements sent a response the app can't read", e)
      } catch (e: IllegalArgumentException) {
        throw RetroAchievementsException("RetroAchievements sent a response the app can't read", e)
      }
    val user = response.user
    val token = response.token
    if (!response.success || user == null || token == null) {
      throw RetroAchievementsException(
        response.error ?: "RetroAchievements didn't accept the sign-in",
      )
    }
    return RetroAchievementsAccount(username = user, token = token)
  }

  /**
   * Sends one of the session's requests as it is, and returns the server's status and body, or null
   * when the server couldn't be reached. A request with [postData] is a POST of it, as
   * [contentType]; one without is a GET.
   */
  suspend fun send(url: String, postData: String?, contentType: String?): Response? =
    try {
      val response =
        if (postData == null) {
          http.get(url) { header(HttpHeaders.UserAgent, userAgent) }
        } else {
          http.post(url) {
            header(HttpHeaders.UserAgent, userAgent)
            setBody(
              ByteArrayContent(
                postData.encodeToByteArray(),
                contentType?.let(ContentType::parse) ?: ContentType.Application.FormUrlEncoded,
              ),
            )
          }
        }
      Response(response.status.value, response.bodyAsBytes())
    } catch (_: IOException) {
      null
    }

  /** A response's HTTP status and body. */
  class Response(val status: Int, val body: ByteArray)

  private suspend fun <T> network(block: suspend () -> T): T =
    try {
      block()
    } catch (e: IOException) {
      throw RetroAchievementsException("Couldn't reach RetroAchievements: ${e.message}", e)
    }

  @Serializable
  private data class LoginResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("User") val user: String? = null,
    @SerialName("Token") val token: String? = null,
    @SerialName("Error") val error: String? = null,
  )

  companion object {
    /** RetroAchievements' API, as rcheevos calls it. */
    const val DEFAULT_ENDPOINT = "https://retroachievements.org/dorequest.php"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The User-Agent RetroAchievements asks for: the app's name and version, then rcheevos' clause,
     * such as `HandheldLauncher/0.1.0 rcheevos/12.5`.
     */
    fun userAgent(appVersion: String, rcheevosClause: String): String =
      "HandheldLauncher/$appVersion $rcheevosClause"
  }
}

/** A request to RetroAchievements that failed, with why in plain words as its message. */
class RetroAchievementsException(message: String, cause: Throwable? = null) :
  Exception(message, cause)
