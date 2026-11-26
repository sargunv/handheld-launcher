package dev.sargunv.handheld.data.cache

import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.RomFile
import dev.sargunv.handheld.domain.RomFileCategory
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class LibraryCacheTest {
  private val cache = LibraryCache.open(path = null, queryContext = EmptyCoroutineContext)

  @AfterTest fun close() = cache.close()

  private fun rom(
    id: Long,
    title: String,
    platformId: Long = 1,
    alternativeNames: List<String> = emptyList(),
    files: List<RomFile> = listOf(RomFile(id * 10, "$title.bin", 100, RomFileCategory.Game)),
    createdAt: Instant = Instant.parse("2024-06-01T00:00:00Z"),
  ) =
    Rom(
      id = id,
      platformId = platformId,
      platformSlug = "p$platformId",
      title = title,
      sortKey = title.lowercase().removePrefix("the "),
      alternativeNames = alternativeNames,
      fileName = "$title (USA).bin",
      sizeBytes = files.sumOf { it.sizeBytes },
      summary = null,
      genres = listOf("Platform", "Adventure"),
      regions = listOf("USA"),
      releaseDate = Instant.fromEpochMilliseconds(1_000_000_000_000),
      coverSmallUrl = "https://romm.test/small/$id.png",
      coverLargeUrl = null,
      hasMultipleFiles = files.size > 1,
      files = files,
      updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
      createdAt = createdAt,
    )

  @Test
  fun readsBackWhatItStored() = runTest {
    val discs =
      listOf(
        RomFile(20, "Disc Game (Disc 2).chd", 200, RomFileCategory.Game),
        RomFile(21, "Disc Game (Disc 1).chd", 100, RomFileCategory.Game),
        RomFile(22, "readme.txt", 1, null),
      )
    val roms =
      listOf(
        rom(1, "Zeta"),
        rom(2, "Disc Game", files = discs),
        rom(3, "The Alpha"),
        rom(4, "Other", 2),
      )
    cache.replacePlatforms(
      listOf(Platform(1, "p1", "p1", "Platform One", 3), Platform(2, "p2", "p2", "Another", 1)),
    )
    cache.upsertRoms(roms)

    assertEquals(listOf("Another", "Platform One"), cache.platforms().first().map { it.name })
    val shelf = cache.romsOnPlatform(1).first()
    assertEquals(listOf("The Alpha", "Disc Game", "Zeta"), shelf.map { it.title })
    assertEquals(
      listOf("Disc Game (Disc 1).chd", "Disc Game (Disc 2).chd", "readme.txt"),
      shelf[1].files.map { it.fileName },
    )
    val all = cache.allRoms().first()
    assertEquals(listOf("The Alpha", "Disc Game", "Other", "Zeta"), all.map { it.title })
    assertEquals(shelf[1], all[1])
    assertEquals(roms[1].copy(files = discs.sortedBy { it.fileName }), cache.rom(2))
    assertEquals(roms[0], cache.rom(1))
    assertNull(cache.rom(99))
    assertEquals(4, cache.romCount())
    assertEquals(mapOf(1L to 1L, 2L to 1L, 3L to 1L, 4L to 2L), cache.romPlatforms().first())
  }

  @Test
  fun replacesARomsFilesOnUpdate() = runTest {
    cache.upsertRoms(listOf(rom(1, "Game")))
    val updated =
      rom(1, "Game: Remastered", files = listOf(RomFile(11, "new.bin", 5, RomFileCategory.Game)))
    cache.upsertRoms(listOf(updated))

    assertEquals(updated, cache.rom(1))
    assertEquals(1, cache.romCount())
  }

  @Test
  fun movesAFileToAnotherRom() = runTest {
    cache.upsertRoms(listOf(rom(1, "One"), rom(2, "Two")))
    val moved = RomFile(10, "One.bin", 100, RomFileCategory.Game)
    cache.upsertRoms(listOf(rom(2, "Two", files = listOf(moved))))

    assertEquals(emptyList(), cache.rom(1)!!.files)
    assertEquals(listOf(moved), cache.rom(2)!!.files)
  }

  @Test
  fun searchesTitlesByWordPrefixes() = runTest {
    cache.upsertRoms(
      listOf(
        rom(1, "Pokémon Crystal"),
        rom(2, "Super Mario Bros. 3"),
        rom(3, "Mario Kart", alternativeNames = listOf("Super Mario Kart")),
        rom(4, "Dr. Mario"),
      ),
    )

    suspend fun titles(text: String) = cache.search(text).map { it.title }.toSet()

    assertEquals(setOf("Pokémon Crystal"), titles("pokemon"))
    assertEquals(setOf("Pokémon Crystal"), titles("POKé cry"))
    // A decomposed accent stays part of its word.
    assertEquals(setOf("Pokémon Crystal"), titles("Poke\u0301mon"))
    assertEquals(setOf("Super Mario Bros. 3", "Mario Kart", "Dr. Mario"), titles("mar"))
    assertEquals(setOf("Super Mario Bros. 3", "Mario Kart"), titles("super mario"))
    assertEquals(setOf("Super Mario Bros. 3"), titles("bros 3"))
    // A search's punctuation and FTS syntax are words, not operators.
    assertEquals(setOf("Dr. Mario"), titles("\"dr.\" (mario*"))
    assertEquals(emptySet(), titles("  -*  "))
    assertEquals(emptySet(), titles("zelda"))
    // The file name is searchable too.
    assertEquals(setOf("Pokémon Crystal"), titles("usa crystal"))
  }

  @Test
  fun ranksTitleMatchesFirst() = runTest {
    cache.upsertRoms(
      listOf(rom(1, "Kart Racer", alternativeNames = listOf("Crash")), rom(2, "Crash")),
    )
    assertEquals(listOf("Crash", "Kart Racer"), cache.search("crash").map { it.title })
  }

  @Test
  fun forgetsRemovedRoms() = runTest {
    cache.upsertRoms(listOf(rom(1, "Keep"), rom(2, "Drop"), rom(3, "Also Drop")))
    assertEquals(2, cache.retainRoms(setOf(1)))

    assertEquals(listOf("Keep"), cache.romsOnPlatform(1).first().map { it.title })
    assertEquals(emptyList(), cache.search("drop"))
    assertNull(cache.rom(2))
  }

  @Test
  fun storesCollections() = runTest {
    cache.upsertRoms(listOf(rom(1, "One"), rom(2, "Two"), rom(3, "Three")))
    cache.replaceCollections(
      listOf(
        Collection(CollectionKind.Regular, 1, "Couch", "", 2, isFavorite = false) to listOf(3L, 1L),
        Collection(CollectionKind.Regular, 2, "Favorites", "", 1, isFavorite = true) to listOf(2L),
        Collection(CollectionKind.Smart, 1, "Adventures", "", 3, isFavorite = false) to
          listOf(1L, 2L, 3L, 99L),
      ),
    )

    assertEquals(
      listOf("Favorites", "Adventures", "Couch"),
      cache.collections().first().map { it.name },
    )
    assertEquals(
      listOf("One", "Three"),
      cache.romsInCollection(CollectionKind.Regular, 1).first().map { it.title },
    )
    assertEquals(
      listOf("One", "Three", "Two"),
      cache.romsInCollection(CollectionKind.Smart, 1).first().map { it.title },
    )
    assertEquals(
      listOf(1L),
      cache.romsInCollection(CollectionKind.Smart, 1).first().first().files.map { it.id / 10 },
    )
    assertEquals(listOf("Adventures", "Couch"), cache.collectionsOf(1).map { it.name })

    cache.replaceCollections(emptyList())
    assertEquals(emptyList(), cache.collections().first())
    assertEquals(emptyList(), cache.romsInCollection(CollectionKind.Regular, 1).first())
  }

  @Test
  fun listsTheRecentlyAddedNewestFirst() = runTest {
    cache.upsertRoms(
      listOf(
        rom(1, "Old", createdAt = Instant.parse("2024-01-01T00:00:00Z")),
        rom(2, "Newest", createdAt = Instant.parse("2024-03-01T00:00:00Z")),
        rom(3, "Middle", createdAt = Instant.parse("2024-02-01T00:00:00Z")),
      ),
    )

    assertEquals(listOf("Newest", "Middle"), cache.recentlyAdded(2).first().map { it.title })
    assertEquals(listOf(2L), cache.recentlyAdded(1).first().single().files.map { it.id / 10 })
  }

  @Test
  fun listsTheRecentlyPlayedLatestFirst() = runTest {
    cache.upsertRoms(listOf(rom(1, "One"), rom(2, "Two"), rom(3, "Three")))
    cache.replaceRecentlyPlayed(
      mapOf(
        1L to Instant.parse("2025-01-01T00:00:00Z"),
        3L to Instant.parse("2025-03-01T00:00:00Z"),
        // A ROM the cache doesn't have yet stays off the list.
        99L to Instant.parse("2025-04-01T00:00:00Z"),
      ),
    )
    assertEquals(listOf("Three", "One"), cache.recentlyPlayed(10).first().map { it.title })

    cache.replaceRecentlyPlayed(mapOf(2L to Instant.parse("2025-05-01T00:00:00Z")))
    assertEquals(listOf("Two"), cache.recentlyPlayed(10).first().map { it.title })
  }

  @Test
  fun emitsAgainWhenTheLibraryChanges() = runTest {
    cache.upsertRoms(listOf(rom(1, "First")))
    val shelves = Channel<List<String>>(Channel.UNLIMITED)
    val collecting = launch {
      cache.romsOnPlatform(1).collect { shelves.send(it.map { rom -> rom.title }) }
    }

    assertEquals(listOf("First"), shelves.receive())
    cache.upsertRoms(listOf(rom(2, "Second")))
    assertEquals(listOf("First", "Second"), shelves.receive())
    collecting.cancel()
  }

  @Test
  fun clearsEverything() = runTest {
    cache.replacePlatforms(listOf(Platform(1, "p1", "p1", "One", 1)))
    cache.upsertRoms(listOf(rom(1, "Game")))
    cache.replaceRecentlyPlayed(mapOf(1L to Instant.parse("2025-01-01T00:00:00Z")))
    cache.setSyncValue("key", "value")
    cache.clear()

    assertEquals(emptyList(), cache.platforms().first())
    assertEquals(0, cache.romCount())
    assertEquals(emptyList(), cache.search("game"))
    assertEquals(emptyList(), cache.recentlyPlayed(10).first())
    assertNull(cache.syncValue("key"))
  }
}
