package dev.sargunv.handheld.data.romm

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

class DeviceAuthTest {
  private val request =
    DeviceAuthRequest(
      clientDeviceIdentifier = "install-1",
      name = "Thor",
      client = "handheld",
      platform = "android",
      requestedScopes = listOf("roms.read"),
    )

  private val code =
    DeviceCode(
      deviceCode = "secret",
      userCode = "ABCD2345",
      verificationUrl = "$TEST_SERVER/pair/device?user_code=ABCD2345",
      expiresIn = 10.minutes,
      interval = 5.seconds,
    )

  /** A server whose token endpoint answers each poll with the next of [answers]. */
  private fun serverAnswering(vararg answers: Pair<HttpStatusCode, String>): MockServer {
    val remaining = ArrayDeque(answers.toList())
    return MockServer(token = null) { request ->
      if (request.apiPath != "auth/device/token")
        return@MockServer respondError(HttpStatusCode.NotFound)
      val (status, body) = remaining.removeFirst()
      if (status == HttpStatusCode.TooManyRequests) {
        respond(body, status, headersOf(HttpHeaders.RetryAfter, "60"))
      } else {
        json(body, status)
      }
    }
  }

  private fun detail(value: String) = HttpStatusCode.BadRequest to """{"detail":"$value"}"""

  private val approved =
    HttpStatusCode.OK to
      """{"access_token": "rmm_issued", "device_id": "d1", "scopes": ["roms.read"], "expires_at": null}"""

  @Test
  fun startsAPairing() = runTest {
    val server =
      MockServer(token = null) {
        json(
          """
          {"device_code": "secret", "user_code": "ABCD2345", "verification_path": "/pair/device",
           "verification_path_complete": "/pair/device?user_code=ABCD2345",
           "expires_in": 600, "interval": 5}
          """,
          HttpStatusCode.Created,
        )
      }
    val started = server.client.startDeviceAuth(request)

    assertEquals(code, started)
    val sent = server.requests.single()
    assertEquals("auth/device/init", sent.apiPath)
    assertNull(sent.headers[HttpHeaders.Authorization])
    val body = (sent.body as TextContent).text
    assertTrue(""""client_device_identifier":"install-1"""" in body, body)
    assertTrue(""""requested_scopes":["roms.read"]""" in body, body)
  }

  @Test
  fun pollsUntilApproved() = runTest {
    val server =
      serverAnswering(
        detail("authorization_pending"),
        detail("slow_down"),
        detail("authorization_pending"),
        approved,
      )
    val token = server.client.awaitDeviceAuth(code)

    assertEquals("rmm_issued", token.accessToken)
    assertEquals("d1", token.deviceId)
    assertEquals(4, server.requests.size)
    assertTrue(
      server.requests.all { (it.body as TextContent).text == """{"device_code":"secret"}""" },
    )
    // Two polls 5 seconds apart, then two 10 seconds apart after the server asked to slow down.
    assertEquals(30.seconds.inWholeMilliseconds, currentTime)
  }

  @Test
  fun slowsDownWhenRateLimited() = runTest {
    val server =
      serverAnswering(
        HttpStatusCode.TooManyRequests to """{"detail":"Too many polling attempts"}""",
        approved,
      )
    server.client.awaitDeviceAuth(code)
    assertEquals(15.seconds.inWholeMilliseconds, currentTime)
  }

  @Test
  fun failsWhenDenied() = runTest {
    val server = serverAnswering(detail("authorization_pending"), detail("access_denied"))
    assertFailsWith<RommException.DeviceAuthDenied> { server.client.awaitDeviceAuth(code) }
  }

  @Test
  fun failsWhenTheServerSaysTheCodeExpired() = runTest {
    val server = serverAnswering(detail("expired_token"))
    assertFailsWith<RommException.DeviceAuthExpired> { server.client.awaitDeviceAuth(code) }
  }

  @Test
  fun stopsPollingWhenTheCodeExpires() = runTest {
    val server = serverAnswering(*Array(10) { detail("authorization_pending") })
    assertFailsWith<RommException.DeviceAuthExpired> {
      server.client.awaitDeviceAuth(code.copy(expiresIn = 12.seconds))
    }
    assertEquals(3, server.requests.size)
  }

  @Test
  fun failsOnAnUnexpectedAnswer() = runTest {
    val server = serverAnswering(HttpStatusCode.BadRequest to """{"detail":"something_else"}""")
    val error = assertFailsWith<RommException.ClientError> { server.client.awaitDeviceAuth(code) }
    assertEquals("something_else", error.detail)
  }
}
