package dev.sargunv.handheld.data.achievements

import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.decode
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.parameters
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A client for RetroAchievements' API, which the app reaches directly, not through RomM: it signs
 * in, and it sends the requests the built-in session's rcheevos makes.
 *
 * RetroAchievements asks every client to name itself in the User-Agent, so [userAgent] goes with
 * each request: `HandheldLauncher/<version> rcheevos/<version>`, the way RetroArch names itself.
 */
class RetroAchievementsClient(private val userAgent: String, private val http: HttpClient) {
  /**
   * Signs in with [username] and [password], and returns the account with the token that stands in
   * for the password from then on. The password goes only to RetroAchievements, and isn't kept.
   *
   * @throws RetroAchievementsException when the server can't be reached or turns the sign-in down.
   */
  suspend fun signIn(username: String, password: String): RetroAchievementsAccount {
    val response = network {
      http.post(ENDPOINT) {
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
    }
    val login =
      response.decode<LoginResponse> { RetroAchievementsException("Unreadable response", it) }
    val user = login.user
    val token = login.token
    if (!login.success || user == null || token == null)
      throw RetroAchievementsException(login.error)
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
    } catch (e: IOException) {
      log.w(e) { "Request to RetroAchievements failed" }
      null
    }

  /** A response's HTTP status and body. */
  class Response(val status: Int, val body: ByteArray)

  private suspend fun <T> network(block: suspend () -> T): T =
    try {
      block()
    } catch (e: IOException) {
      throw RetroAchievementsException("RetroAchievements unreachable", e)
    }

  @Serializable
  private data class LoginResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("User") val user: String? = null,
    @SerialName("Token") val token: String? = null,
    @SerialName("Error") val error: String? = null,
  )

  companion object {
    private val log = Logger.withTag("RetroAchievementsClient")

    /** RetroAchievements' API, as rcheevos calls it. */
    private const val ENDPOINT = "https://retroachievements.org/dorequest.php"

    /**
     * The User-Agent RetroAchievements asks for: the app's name and version, then rcheevos' clause,
     * such as `HandheldLauncher/0.1.0 rcheevos/12.5`.
     */
    fun userAgent(appVersion: String, rcheevosClause: String): String =
      "HandheldLauncher/$appVersion $rcheevosClause"
  }
}

/** A request to RetroAchievements that failed, with why as its message when that's known. */
class RetroAchievementsException(message: String?, cause: Throwable? = null) :
  Exception(message, cause)
