package dev.sargunv.handheld.data.cache

import dev.sargunv.handheld.data.Fixtures
import dev.sargunv.handheld.data.romm.MockServer
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommJson
import dev.sargunv.handheld.data.romm.RommRom
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

    /** ROM ids the server lists but no longer has, as when a ROM is deleted during a sync. */
    val unlisted = mutableSetOf<Long>()

    /** ROM ids to when the user last played them. */
    val lastPlayed = mutableMapOf<Long, String>()
    var failRoms = false

    fun romJson(id: Long) =
      Fixtures.rom(
        id,
        platformId = if (id % 2 == 0L) 2 else 1,
        name = if (id == 3L) null else "Game $id",
        fsName = "Game $id (Europe).iso",
        updatedAt = roms.getValue(id),
        createdAt = "2024-01-0${id}T00:00:00+00:00",
        lastPlayed = lastPlayed[id],
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
          if (params["last_played"] == "true") {
            val played = library.lastPlayed.entries.sortedByDescending { it.value }.map { it.key }
            return@MockServer json(
              Fixtures.page(
                played.map(library::romJson),
                played.size,
                params["limit"]!!.toInt(),
                0,
              ),
            )
          }
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
        "roms/identifiers" -> json((library.roms.keys + library.unlisted).sorted().toString())
        "collections" ->
          json("[${Fixtures.collection(1, "Favourites", listOf(1, 2), favorite = true)}]")
        "collections/smart" -> json("[${Fixtures.smartCollection(1, "Odd ones", listOf(1, 3))}]")
        else -> {
          val id = request.apiPath.removePrefix("roms/").toLongOrNull()
          if (id != null && id in library.roms) json(library.romJson(id))
          else respondError(HttpStatusCode.NotFound)
        }
      }
    }
  private val sync = LibrarySync(server.client, cache, pageSize = 2)

  /** The requests that walked the library's ROMs, leaving out the one for recently played ROMs. */
  private fun romRequests() =
    server.requests.filter { it.apiPath == "roms" && it.url.parameters["last_played"] == null }

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
    assertEquals(listOf(3L, 2L), cache.recentlyAdded(2).first().map { it.id })
  }

  @Test
  fun keepsTheRecentlyPlayed() = runTest {
    library.roms += mapOf(1L to "2025-01-01T00:00:00+00:00", 2L to "2025-01-01T00:00:00+00:00")
    library.lastPlayed[1] = "2025-02-01T00:00:00+00:00"
    library.lastPlayed[2] = "2025-03-01T00:00:00+00:00"
    sync.sync()
    assertEquals(listOf(2L, 1L), cache.recentlyPlayed(10).first().map { it.id })

    // Playing a game doesn't change the ROM, so an incremental sync still sees it.
    library.lastPlayed[1] = "2025-04-01T00:00:00+00:00"
    sync.sync()
    assertEquals(listOf(1L, 2L), cache.recentlyPlayed(10).first().map { it.id })
    val request = server.requests.last { it.url.parameters["last_played"] == "true" }
    assertEquals("last_played", request.url.parameters["order_by"])
    assertEquals("desc", request.url.parameters["order_dir"])
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
  fun fetchesRomsTheServerListsButTheCacheLacks() = runTest {
    library.roms += mapOf(1L to "2025-03-01T00:00:00+00:00")
    sync.sync()
    server.requests.clear()

    // An unhidden ROM keeps its old update time, so the window doesn't include it.
    library.roms[5] = "2025-01-01T00:00:00+00:00"
    library.unlisted += 6
    val result = sync.sync()

    assertEquals(
      listOf("roms", "roms/identifiers", "roms/5", "roms/6", "roms"),
      server.requests.map { it.apiPath }.filter { it.startsWith("roms") },
    )
    // ROM 1 again, since it's inside the window, and ROM 5.
    assertEquals(2, result.romsFetched)
    assertEquals(setOf(1L, 5L), cache.romIds())
    assertEquals("Game 5", cache.rom(5)!!.title)
  }

  @Test
  fun fallsBackToTheMetadataSourcesCover() {
    fun rom(json: String) =
      RommJson.decodeFromString(RommRom.serializer(), json).toDomain(server.client)

    val noCover = rom(Fixtures.rom(1, hasCover = false, urlCover = ""))
    assertNull(noCover.coverSmallUrl)
    assertNull(noCover.coverLargeUrl)
    val sourceCover = rom(Fixtures.rom(1, hasCover = false))
    assertEquals("https://images.example/cover/1.jpg", sourceCover.coverSmallUrl)
    assertEquals("https://images.example/cover/1.jpg", sourceCover.coverLargeUrl)
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
    assertNull(other.requests.first { it.apiPath == "roms" }.url.parameters["updated_after"])
    assertEquals(0, cache.romCount())
    assertEquals(emptyList(), cache.platforms().first())
  }
}
