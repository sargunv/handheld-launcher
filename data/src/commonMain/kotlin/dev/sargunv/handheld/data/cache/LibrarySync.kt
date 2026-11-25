package dev.sargunv.handheld.data.cache

import dev.sargunv.handheld.data.romm.RomQuery
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommCollection
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommPlatform
import dev.sargunv.handheld.data.romm.RommRom
import dev.sargunv.handheld.data.romm.RommRomFile
import dev.sargunv.handheld.data.romm.RommSmartCollection
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.RomFile
import dev.sargunv.handheld.domain.RomFileCategory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What a [LibrarySync] did. */
data class LibrarySyncResult(
  val platforms: Int,
  /** The ROMs the server sent, which is every ROM on a full sync and the changed ones otherwise. */
  val romsFetched: Int,
  /** The ROMs that were gone from the server. */
  val romsRemoved: Int,
  /** The ROMs in the cache afterwards. */
  val roms: Long,
  val collections: Int,
  /** Whether the sync fetched only the ROMs that changed since the last one. */
  val incremental: Boolean,
)

/** Refreshes a [LibraryCache] from a RomM server. */
class LibrarySync(
  private val client: RommClient,
  private val cache: LibraryCache,
  private val pageSize: Int = 200,
) {
  /**
   * Brings the cache up to date with the server. Platforms and collections are small, so they're
   * fetched whole every time. ROMs are fetched whole the first time, or when [full] is true, and
   * afterwards only those the server changed since the last sync. The server's list of ROM ids then
   * tells which ROMs are gone, and which ones the cache lacks, which are fetched one by one.
   *
   * A sync that fails leaves the cache usable: what it wrote stays, and the next sync fetches the
   * same changes again.
   *
   * @throws RommException when a request fails.
   */
  suspend fun sync(full: Boolean = false): LibrarySyncResult {
    val server = client.baseUrl.toString()
    if (cache.syncValue(SERVER_KEY) != server) {
      cache.clear()
      cache.setSyncValue(SERVER_KEY, server)
    }
    val cursor = if (full) null else cache.syncValue(ROM_CURSOR_KEY)?.let(Instant::parse)

    val platforms = client.platforms().map { it.toDomain() }
    cache.replacePlatforms(platforms)

    var newest = cursor
    var fetched = 0
    val query = RomQuery(updatedAfter = cursor?.minus(CURSOR_OVERLAP), withFiles = true)
    client.allRoms(query, pageSize).collect { page ->
      cache.upsertRoms(page.items.map { it.toDomain(client) })
      fetched += page.items.size
      val pageNewest = page.items.maxOf { it.updatedAt }
      newest = newest?.let { maxOf(it, pageNewest) } ?: pageNewest
    }
    val serverIds = client.romIds().toSet()
    val removed = cache.retainRoms(serverIds)
    // The server can list a ROM the walk didn't return: one the user unhid, which keeps its old
    // update time, or one that paging by offset skipped.
    for (id in serverIds - cache.romIds()) {
      val rom =
        try {
          client.rom(id)
        } catch (e: RommException.NotFound) {
          continue
        }
      cache.upsertRoms(listOf(rom.toDomain(client)))
      fetched++
    }

    val collections =
      client.collections().map { it.toDomain() to it.romIds } +
        client.smartCollections().map { it.toDomain() to it.romIds }
    cache.replaceCollections(collections)

    newest?.let { cache.setSyncValue(ROM_CURSOR_KEY, it.toString()) }
    return LibrarySyncResult(
      platforms = platforms.size,
      romsFetched = fetched,
      romsRemoved = removed,
      roms = cache.romCount(),
      collections = collections.size,
      incremental = cursor != null,
    )
  }

  private companion object {
    const val SERVER_KEY = "server"
    const val ROM_CURSOR_KEY = "rom_cursor"

    /**
     * How far before the newest change already seen an incremental sync starts. A change can become
     * visible after changes with later timestamps, such as when a long scan commits, and fetching a
     * ROM twice is harmless.
     */
    val CURSOR_OVERLAP = 10.minutes
  }
}

internal fun RommPlatform.toDomain() =
  Platform(id = id, slug = slug, fsSlug = fsSlug, name = displayName, romCount = romCount)

internal fun RommRom.toDomain(client: RommClient): Rom {
  val title = name?.takeIf { it.isNotBlank() } ?: fsNameNoTags
  return Rom(
    id = id,
    platformId = platformId,
    platformSlug = platformSlug,
    title = title,
    sortKey = nameSortKey?.takeIf { it.isNotBlank() } ?: title.lowercase(),
    alternativeNames = alternativeNames,
    fileName = fsName,
    sizeBytes = fsSizeBytes,
    summary = summary,
    genres = metadatum?.genres.orEmpty(),
    regions = regions,
    releaseDate = metadatum?.firstReleaseDate?.let(Instant::fromEpochMilliseconds),
    coverSmallUrl = coverUrl(pathCoverSmall, client),
    coverLargeUrl = coverUrl(pathCoverLarge, client),
    hasMultipleFiles = hasMultipleFiles,
    files = files.map { it.toDomain() },
    updatedAt = updatedAt,
  )
}

/** The cover RomM keeps, else the metadata source's. RomM sends an empty string for none. */
private fun RommRom.coverUrl(path: String?, client: RommClient): String? =
  path?.takeIf { it.isNotBlank() }?.let(client::resolve) ?: urlCover?.takeIf { it.isNotBlank() }

internal fun RommRomFile.toDomain() =
  RomFile(
    id = id,
    fileName = fileName,
    sizeBytes = fileSizeBytes,
    category = fileCategory(category),
  )

internal fun RommCollection.toDomain() =
  Collection(CollectionKind.Regular, id, name, description, romCount, isFavorite)

internal fun RommSmartCollection.toDomain() =
  Collection(CollectionKind.Smart, id, name, description, romCount, isFavorite = false)

private fun fileCategory(value: String?): RomFileCategory? =
  when (value) {
    "game" -> RomFileCategory.Game
    "dlc" -> RomFileCategory.Dlc
    "hack" -> RomFileCategory.Hack
    "manual" -> RomFileCategory.Manual
    "walkthrough" -> RomFileCategory.Walkthrough
    "patch" -> RomFileCategory.Patch
    "update" -> RomFileCategory.Update
    "mod" -> RomFileCategory.Mod
    "demo" -> RomFileCategory.Demo
    "translation" -> RomFileCategory.Translation
    "prototype" -> RomFileCategory.Prototype
    "cheat" -> RomFileCategory.Cheat
    "soundtrack" -> RomFileCategory.Soundtrack
    "screenshot" -> RomFileCategory.Screenshot
    else -> null
  }
