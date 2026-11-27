package dev.sargunv.handheld.data.romm

import dev.sargunv.handheld.data.Fixtures
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class RommClientTest {
  @Test
  fun sendsTheTokenToTheServersApi() = runTest {
    val server = MockServer("$TEST_SERVER/romm/") { json("[]") }
    server.client.platforms()

    val request = server.requests.single()
    assertEquals("$TEST_SERVER/romm/api/platforms", request.url.toString())
    assertEquals("Bearer rmm_test", request.headers[HttpHeaders.Authorization])
  }

  @Test
  fun sendsNoTokenWithoutOne() = runTest {
    val server = MockServer(token = null) { json("[]") }
    server.client.platforms()
    assertNull(server.requests.single().headers[HttpHeaders.Authorization])
  }

  @Test
  fun readsTheUser() = runTest {
    val user = MockServer { json(Fixtures.me) }.client.me()
    assertEquals("player", user.username)
    assertEquals(listOf("me.read", "roms.read"), user.oauthScopes)
  }

  @Test
  fun treatsANullUserAsSignedOut() = runTest {
    assertFailsWith<RommException.Unauthorized> { MockServer { json("null") }.client.me() }
  }

  @Test
  fun readsARomWithItsFiles() = runTest {
    val server = MockServer {
      json(
        Fixtures.rom(
          7,
          fsName = "Disc Game",
          files =
            listOf(
              Fixtures.file(70, 7, "Disc Game (Disc 1).chd", 100),
              Fixtures.file(71, 7, "Disc Game (Disc 2).chd", 200),
              Fixtures.file(72, 7, "Disc Game.mystery", 5, category = "future_category"),
            ),
        ),
      )
    }
    val rom = server.client.rom(7)

    assertEquals("roms/7", server.requests.single().apiPath)
    assertEquals("Test Game 7", rom.name)
    assertTrue(rom.hasMultipleFiles)
    assertEquals(listOf(100L, 200L, 5L), rom.files.map { it.fileSizeBytes })
    assertEquals("future_category", rom.files[2].category)
    assertEquals(listOf("Platform", "Adventure"), rom.metadatum?.genres)
  }

  @Test
  fun pagesThroughEveryRom() = runTest {
    val roms = (1L..5L).map { Fixtures.rom(it) }
    val server = MockServer { request ->
      val limit = request.url.parameters["limit"]!!.toInt()
      val offset = request.url.parameters["offset"]!!.toInt()
      val total = if (request.url.parameters["with_total"] == "true") roms.size else null
      json(Fixtures.page(roms.drop(offset).take(limit), total, limit, offset))
    }
    val pages = server.client.allRoms(Instant.parse("2025-01-01T00:00:00Z"), pageSize = 2).toList()

    assertEquals(
      listOf(listOf(1L, 2L), listOf(3L, 4L), listOf(5L)),
      pages.map { page -> page.items.map { it.id } },
    )
    assertEquals(listOf("0", "2", "4"), server.requests.map { it.url.parameters["offset"] })
    assertEquals(
      listOf("true", "false", "false"),
      server.requests.map { it.url.parameters["with_total"] },
    )
    val params = server.requests.first().url.parameters
    assertEquals("2025-01-01T00:00:00Z", params["updated_after"])
    assertEquals("true", params["with_files"])
    assertEquals("id", params["order_by"])
    assertEquals("false", params["with_rom_id_index"])
  }

  @Test
  fun stopsPagingAtAnEmptyPage() = runTest {
    val server = MockServer { request ->
      val offset = request.url.parameters["offset"]!!.toInt()
      // The server lost its count, and two ROMs disappeared mid-walk.
      json(
        Fixtures.page(
          if (offset == 0) listOf(Fixtures.rom(1), Fixtures.rom(2)) else emptyList(),
          null,
          2,
          offset,
        ),
      )
    }
    val pages = server.client.allRoms(updatedAfter = null, pageSize = 2).toList()
    assertEquals(1, pages.size)
    assertEquals(2, server.requests.size)
  }

  @Test
  fun mapsErrorStatuses() = runTest {
    suspend fun failureFor(status: HttpStatusCode, body: String) =
      assertFailsWith<RommException.Status> {
        MockServer { respond(body, status) }.client.platforms()
      }

    val unauthorized = failureFor(HttpStatusCode.Unauthorized, """{"detail":"Invalid token"}""")
    assertTrue(unauthorized is RommException.Unauthorized)
    assertEquals("Invalid token", unauthorized.message)
    assertTrue(
      failureFor(HttpStatusCode.Forbidden, """{"detail":"Forbidden"}""") is RommException.Forbidden,
    )
    assertTrue(failureFor(HttpStatusCode.NotFound, "not json") is RommException.NotFound)

    val invalid =
      failureFor(
        HttpStatusCode.UnprocessableEntity,
        """{"detail":[{"loc":["query","limit"],"msg":"Input should be a valid integer","type":"int_parsing"}]}""",
      )
    assertTrue(invalid is RommException.ClientError)
    assertEquals(422, invalid.status)
    assertEquals("Input should be a valid integer", invalid.message)

    assertTrue(
      failureFor(HttpStatusCode.TooManyRequests, """{"detail":"Slow down"}""")
        is RommException.RateLimited,
    )

    val failed = failureFor(HttpStatusCode.BadGateway, "<html>Bad Gateway</html>")
    assertTrue(failed is RommException.ServerError)
    assertEquals(502, failed.status)
    assertEquals("Server error 502", failed.message)
  }

  @Test
  fun mapsAnUnreadableBody() = runTest {
    assertFailsWith<RommException.InvalidResponse> {
      MockServer { json("""[{"id": "not a number"}]""") }.client.platforms()
    }
    assertFailsWith<RommException.InvalidResponse> {
      MockServer { json("<html>") }.client.platforms()
    }
  }

  @Test
  fun mapsAConnectionFailure() = runTest {
    assertFailsWith<RommException.Unreachable> {
      MockServer { throw IOException("Connection refused") }.client.platforms()
    }
  }

  @Test
  fun mapsAnAddressTheEngineRefuses() = runTest {
    assertFailsWith<RommException.Unreachable> {
      RommClient("https://romm..example.com", "rmm_test").use { it.platforms() }
    }
  }

  @Test
  fun resolvesServerPaths() {
    val client = MockServer("$TEST_SERVER/romm") { respondError(HttpStatusCode.NotFound) }.client
    assertEquals(
      "$TEST_SERVER/romm/assets/romm/resources/roms/1/2/cover/small%20one.png?ts=2025-01-01+00%3A00%3A00",
      client.resolve("/assets/romm/resources/roms/1/2/cover/small one.png?ts=2025-01-01 00:00:00"),
    )
    assertEquals(
      "$TEST_SERVER/romm/pair/device?user_code=ABCD",
      client.resolve("/pair/device?user_code=ABCD"),
    )
  }

  @Test
  fun readsSaves() = runTest {
    val server = MockServer {
      json(
        """
        [{"id": 3, "rom_id": 7, "user_id": 1, "file_name": "game.srm",
          "file_name_no_tags": "game", "file_name_no_ext": "game", "file_extension": "srm",
          "file_path": "saves/7", "file_size_bytes": 8192, "full_path": "saves/7/game.srm",
          "download_path": "/api/saves/3/content", "missing_from_fs": false,
          "created_at": "2025-01-01T00:00:00+00:00", "updated_at": "2025-01-02T00:00:00+00:00",
          "emulator": "retroarch", "slot": "autosave", "content_hash": "abc", "screenshot": null,
          "device_syncs": [{"device_id": "d1", "device_name": "Thor",
            "last_synced_at": "2025-01-02T00:00:00+00:00", "is_untracked": false, "is_current": true}]}]
        """,
      )
    }

    val save = server.client.saves(romId = 7).single()
    assertEquals("saves", server.requests.single().apiPath)
    assertEquals("7", server.requests.single().url.parameters["rom_id"])
    assertEquals("autosave", save.slot)
    assertEquals("Thor", save.deviceSyncs.single().deviceName)
  }
}
