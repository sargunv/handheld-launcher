package dev.sargunv.handheld.ui.library

import dev.sargunv.handheld.data.downloads.RomStatus
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class LibraryModelTest {
  private val gb = Platform(1, "gb", "gb", "Game Boy", 3)
  private val nes = Platform(2, "nes", "nes", "NES", 2)
  private val empty = Platform(3, "n64", "n64", "Nintendo 64", 0)

  private fun rom(id: Long, sortKey: String, addedDay: Int = 1) =
    Rom(
      id = id,
      platformId = 1,
      platformSlug = "gb",
      title = sortKey,
      sortKey = sortKey,
      alternativeNames = emptyList(),
      fileName = "$sortKey.gb",
      sizeBytes = 1,
      summary = null,
      genres = emptyList(),
      regions = emptyList(),
      releaseDate = null,
      coverSmallUrl = null,
      coverLargeUrl = null,
      hasMultipleFiles = false,
      files = emptyList(),
      updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
      createdAt = Instant.parse("2025-01-${addedDay.toString().padStart(2, '0')}T00:00:00Z"),
    )

  private val couch = Collection(CollectionKind.Regular, 1, "Couch", "", 3, isFavorite = false)
  private val emptyCollection =
    Collection(CollectionKind.Smart, 1, "Nothing", "", 0, isFavorite = false)

  @Test
  fun listsThePlatformsWithGamesAndEveryCollection() {
    assertEquals(
      listOf("platform:1", "platform:2"),
      platformShelves(listOf(gb, nes, empty), emptyList()).map { it.key },
    )
    // Home lists every collection, so the tab has each one Home opens.
    assertEquals(
      listOf("collection:Regular:1", "collection:Smart:1"),
      collectionShelves(listOf(couch, emptyCollection)).map { it.key },
    )
  }

  @Test
  fun stepsBetweenShelvesAndStopsAtTheEnds() {
    val shelves = platformShelves(listOf(gb, nes), emptyList())
    assertEquals("platform:2", shelves.step("platform:1", 1)?.key)
    assertNull(shelves.step("platform:2", 1))
    assertNull(shelves.step("platform:1", -1))
    // A shelf that went away counts as the first one.
    assertEquals("platform:1", shelves.shelfAt("platform:9")?.key)
    assertEquals("platform:2", shelves.step("platform:9", 1)?.key)
    assertNull(emptyList<LibraryShelf>().shelfAt(null))
  }

  @Test
  fun countsEachShelfsGamesOnTheDevice() {
    val shelves = platformShelves(listOf(gb, nes), emptyList()) + collectionShelves(listOf(couch))
    val romPlatforms = mapOf(1L to 1L, 2L to 1L, 3L to 2L, 4L to 2L)
    val collectionRoms = mapOf((CollectionKind.Regular to 1L) to setOf(1L, 2L, 4L))
    val statuses =
      mapOf(
        1L to RomStatus.OnDevice("/ROMs/gb/a.gb"),
        3L to RomStatus.OnDevice("/ROMs/nes/c.nes"),
        4L to RomStatus.Downloading(0, 100),
      )
    assertEquals(
      listOf(
        Triple("platform:1", 3, 1),
        Triple("platform:2", 2, 1),
        Triple("collection:Regular:1", 3, 1),
      ),
      shelfSummaries(shelves, romPlatforms, collectionRoms, statuses, emptyList()).map {
        Triple(it.shelf.key, it.games, it.onDevice)
      },
    )
  }

  @Test
  fun aCollectionsPlatformsAreThoseOfItsGames() {
    val romPlatforms = mapOf(1L to 1L, 2L to 1L, 3L to 2L)
    assertEquals(
      listOf(gb, nes),
      collectionPlatforms(
        couch,
        listOf(gb, nes, empty),
        romPlatforms,
        mapOf((CollectionKind.Regular to 1L) to setOf(3L, 1L)),
      ),
    )
    assertEquals(
      listOf(nes),
      collectionPlatforms(
        couch,
        listOf(gb, nes),
        romPlatforms,
        mapOf((CollectionKind.Regular to 1L) to setOf(3L)),
      ),
    )
  }

  @Test
  fun onDeviceKeepsTheGamesOnTheDeviceOrOnTheirWay() {
    val roms = listOf(rom(1, "a"), rom(2, "b"), rom(3, "c"))
    val statuses =
      mapOf(
        1L to RomStatus.OnDevice("/ROMs/gb/a.gb"),
        2L to RomStatus.Failed("Offline"),
        3L to RomStatus.Downloading(50, 100),
      )
    assertEquals(listOf(1L, 3L), roms.onDevice(statuses).map { it.id })
  }

  @Test
  fun sortsByNameOrNewestFirst() {
    val roms = listOf(rom(1, "bravo", 3), rom(2, "Alpha", 1), rom(3, "charlie", 2))
    assertEquals(
      listOf(2L, 1L, 3L),
      roms.shown(LibraryFilter.All, LibrarySort.Name, emptyMap()).map { it.id },
    )
    assertEquals(
      listOf(1L, 3L, 2L),
      roms.shown(LibraryFilter.All, LibrarySort.RecentlyAdded, emptyMap()).map { it.id },
    )
  }

  @Test
  fun filtersByWhetherAGameIsOnTheDevice() {
    val roms = listOf(rom(1, "a"), rom(2, "b"), rom(3, "c"))
    val statuses =
      mapOf(1L to RomStatus.OnDevice("/ROMs/gb/a.gb"), 2L to RomStatus.Downloading(50, 100))
    assertEquals(
      listOf(1L),
      roms.shown(LibraryFilter.OnDevice, LibrarySort.Name, statuses).map { it.id },
    )
    // A game still downloading isn't on the device yet.
    assertEquals(
      listOf(2L, 3L),
      roms.shown(LibraryFilter.NotOnDevice, LibrarySort.Name, statuses).map { it.id },
    )
  }

  @Test
  fun indexesEachLetterAtItsFirstGame() {
    val roms = listOf(rom(1, "1942"), rom(2, "alpha"), rom(3, "Avenger"), rom(4, "zeta"))
    assertEquals(mapOf('#' to 0, 'A' to 1, 'Z' to 3), jumpIndex(roms))
    assertEquals('#', jumpLetter(rom(5, "")))
  }
}
