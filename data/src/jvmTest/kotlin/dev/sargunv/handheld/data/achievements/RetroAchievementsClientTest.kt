package dev.sargunv.handheld.data.achievements

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class RetroAchievementsClientTest {
  private val requests = mutableListOf<Pair<HttpRequestData, String>>()

  private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String) =
    RetroAchievementsClient(
      RetroAchievementsClient.userAgent("1.2.3", "rcheevos/12.5"),
      MockEngine { request ->
        requests += request to request.body.toByteArray().decodeToString()
        respond(body, status)
      },
    )

  @Test
  fun signsInForAToken() = runTest {
    val account =
      client(
          body =
            """{"Success":true,"User":"Player","Token":"ra_token","Score":5,"SoftcoreScore":0,
            "Messages":0,"Permissions":1,"AccountType":"Registered"}""",
        )
        .signIn("player", "pa\$\$word")
    assertEquals(RetroAchievementsAccount("Player", "ra_token"), account)
    val (request, body) = requests.single()
    assertEquals("https://retroachievements.org/dorequest.php", request.url.toString())
    assertEquals(HttpMethod.Post, request.method)
    assertEquals("HandheldLauncher/1.2.3 rcheevos/12.5", request.headers[HttpHeaders.UserAgent])
    val form = parseQueryString(body)
    assertEquals("login2", form["r"])
    assertEquals("player", form["u"])
    assertEquals("pa\$\$word", form["p"])
  }

  @Test
  fun saysWhyASignInFailed() = runTest {
    val error =
      assertFailsWith<RetroAchievementsException> {
        client(
            HttpStatusCode.Unauthorized,
            """{"Success":false,"Status":401,"Code":"invalid_credentials",
            "Error":"Invalid user/password combination. Please try again."}""",
          )
          .signIn("player", "wrong")
      }
    assertEquals("Invalid user/password combination. Please try again.", error.message)
  }

  @Test
  fun sendsTheSessionsRequestsAsTheyAre() = runTest {
    val response =
      client(HttpStatusCode.OK, """{"Success":true}""")
        .send(
          "https://retroachievements.org/dorequest.php",
          "r=ping&u=Player&t=ra_token&g=1",
          "application/x-www-form-urlencoded",
        )
    assertEquals(200, response?.status)
    assertEquals("""{"Success":true}""", response?.body?.decodeToString())
    val (request, body) = requests.single()
    assertEquals("r=ping&u=Player&t=ra_token&g=1", body)
    assertEquals("HandheldLauncher/1.2.3 rcheevos/12.5", request.headers[HttpHeaders.UserAgent])
  }

  @Test
  fun anUnreachableServerHasNoResponse() = runTest {
    val client =
      RetroAchievementsClient(
        "HandheldLauncher/1 rcheevos/12.5",
        MockEngine { throw IOException("offline") },
      )
    assertNull(client.send("https://retroachievements.org/dorequest.php", "r=ping", null))
    assertFailsWith<RetroAchievementsException> { client.signIn("player", "password") }
  }
}
