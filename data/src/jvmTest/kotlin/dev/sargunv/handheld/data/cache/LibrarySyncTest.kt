package dev.sargunv.handheld.data.cache

import dev.sargunv.handheld.data.Fixtures
import dev.sargunv.handheld.data.romm.MockServer
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.TEST_SERVER
import dev.sargunv.handheld.data.romm.apiPath
import dev.sargunv.handheld.data.romm.json
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.RomFileCategory
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class LibrarySyncTest {
  private val cache = LibraryCache.open(path = null, queryContext = EmptyCoroutineContext)

  @AfterTest fun close() = cache.close()

  /** A fake library: ROM ids to when they changed. */
  private class Library {
    val roms = mutableMapOf<Long, String>()
    var failRoms = false

    fun romJson(id: Long) =
      Fixtures.rom(
        id,
        platformId = if (id % 2 == 0L) 2 else 1,
        name = if (id == 3L) null else "Game $id",
        fsName = "Game $id (Europe).iso",
        updatedAt = roms.getValue(id),
      )
  }

  private val library = Library()
  private val server =
    MockServer(baseUrl = "$TEST_SERVER/") { request ->
      val params = request.url.parameters
      when (request.apiPath) {
        "platforms" ->
          json(
            "[${Fixtures.platform(1, "one", "One", 2)}, ${Fixtures.platform(2, "two", "Two", 1)}]",
          )
        "roms" -> {
          if (library.failRoms) return@MockServer respondError(HttpStatusCode.InternalServerError)
          val after = params["updated_after"]?.let(Instant::parse)
          val matching =
            library.roms.filterValues { after == null || Instant.parse(it) > after }.keys.sorted()
          val limit = params["limit"]!!.toInt()
          val offset = params["offset"]!!.toInt()
          json(
            Fixtures.page(
              matching.drop(offset).take(limit).map(library::romJson),
              matching.size,
              limit,
              offset,
            ),
          )
        }
        "roms/identifiers" -> json(library.roms.keys.sorted().toString())
        "collections" ->
          json("[${Fixtures.collection(1, "Favourites", listOf(1, 2), favorite = true)}]")
        "collections/smart" -> json("[${Fixtures.smartCollection(1, "Odd ones", listOf(1, 3))}]")
        else -> respondError(HttpStatusCode.NotFound)
      }
    }
  private val sync = LibrarySync(server.client, cache, pageSize = 2)

  private fun romRequests() = server.requests.filter { it.apiPath == "roms" }

  @Test
  fun fetchesTheWholeLibraryAtFirst() = runTest {
    library.roms +=
      mapOf(
        1L to "2025-01-01T00:00:00+00:00",
        2L to "2025-01-02T00:00:00+00:00",
        3L to "2025-01-03T00:00:00+00:00",
      )
    val result = sync.sync()

    assertEquals(
      LibrarySyncResult(
        platforms = 2,
        romsFetched = 3,
        romsRemoved = 0,
        roms = 3,
        collections = 2,
        incremental = false,
      ),
      result,
    )
    assertEquals(listOf("One", "Two"), cache.platforms().first().map { it.name })
    assertEquals(listOf("Game 1", "Game 3"), cache.romsOnPlatform(1).first().map { it.title })
    assertNull(romRequests().first().url.parameters["updated_after"])
    assertEquals("true", romRequests().first().url.parameters["with_files"])

    val rom = cache.rom(1)!!
    assertEquals("one", cache.platforms().first().first().slug)
    assertEquals(
      "$TEST_SERVER/assets/romm/resources/roms/1/1/cover/small.png?ts=2025-01-01+00%3A00%3A00",
      rom.coverSmallUrl,
    )
    assertEquals(listOf("Platform", "Adventure"), rom.genres)
    assertEquals(Instant.fromEpochMilliseconds(1_000_000_000_000), rom.releaseDate)
    assertEquals(RomFileCategory.Game, rom.files.single().category)
    // A ROM no metadata source matched goes by its file name.
    assertEquals("Game 3", cache.rom(3)!!.title)

    val collections = cache.collections().first()
    assertEquals(
      listOf(CollectionKind.Regular to "Favourites", CollectionKind.Smart to "Odd ones"),
      collections.map { it.kind to it.name },
    )
    assertEquals(
      listOf(1L, 3L),
      cache.romsInCollection(CollectionKind.Smart, 1).first().map { it.id },
    )
    assertEquals(listOf(1L), cache.search("game 1").map { it.id })
  }

  @Test
  fun fetchesOnlyChangesAfterwards() = runTest {
    library.roms += mapOf(1L to "2025-01-01T00:00:00+00:00", 2L to "2025-03-01T12:00:00+00:00")
    sync.sync()
    server.requests.clear()

    library.roms[1] = "2025-03-02T00:00:00+00:00"
    library.roms[4] = "2025-03-03T00:00:00+00:00"
    library.roms.remove(2)
    val result = sync.sync()

    // The window starts a little before the newest change the first sync saw.
    assertEquals("2025-03-01T11:50:00Z", romRequests().single().url.parameters["updated_after"])
    assertEquals(
      LibrarySyncResult(
        platforms = 2,
        romsFetched = 2,
        romsRemoved = 1,
        roms = 2,
        collections = 2,
        incremental = true,
      ),
      result,
    )
    assertEquals(listOf(1L, 4L), cache.search("game").map { it.id }.sorted())

    server.requests.clear()
    sync.sync()
    assertEquals("2025-03-02T23:50:00Z", romRequests().single().url.parameters["updated_after"])
  }

  @Test
  fun fetchesEverythingWhenAskedTo() = runTest {
    library.roms += mapOf(1L to "2025-01-01T00:00:00+00:00")
    sync.sync()
    server.requests.clear()

    val result = sync.sync(full = true)
    assertNull(romRequests().single().url.parameters["updated_after"])
    assertEquals(false, result.incremental)
  }

  @Test
  fun keepsTheCacheAndCursorWhenASyncFails() = runTest {
    library.roms += mapOf(1L to "2025-01-01T00:00:00+00:00")
    sync.sync()
    library.roms[2] = "2025-02-01T00:00:00+00:00"
    library.failRoms = true

    assertFailsWith<RommException.ServerError> { sync.sync() }
    assertEquals(1, cache.romCount())

    library.failRoms = false
    server.requests.clear()
    sync.sync()
    assertEquals("2024-12-31T23:50:00Z", romRequests().single().url.parameters["updated_after"])
    assertEquals(2, cache.romCount())
  }

  @Test
  fun startsOverForAnotherServer() = runTest {
    library.roms += mapOf(1L to "2025-01-01T00:00:00+00:00")
    sync.sync()

    val other =
      MockServer(baseUrl = "https://other.test") { request ->
        when (request.apiPath) {
          "roms" -> json(Fixtures.page(emptyList(), 0, 2, 0))
          "roms/identifiers",
          "platforms",
          "collections",
          "collections/smart" -> json("[]")
          else -> respondError(HttpStatusCode.NotFound)
        }
      }
    val result = LibrarySync(other.client, cache).sync()

    assertTrue(!result.incremental)
    assertNull(other.requests.single { it.apiPath == "roms" }.url.parameters["updated_after"])
    assertEquals(0, cache.romCount())
    assertEquals(emptyList(), cache.platforms().first())
  }
}
