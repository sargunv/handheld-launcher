package dev.sargunv.handheld.data.cache

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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Refreshes a [LibraryCache] from a RomM server, for the server's user [userId]. The cache holds
 * one account's library, so a sync for another account, on this server or another, starts from an
 * empty cache.
 */
internal class LibrarySync(
  private val client: RommClient,
  private val cache: LibraryCache,
  userId: Long,
  private val pageSize: Int = 200,
) {
  private val account = "${client.baseUrl} $userId"

  /** Whether the cache holds nothing of the account's library yet. */
  suspend fun isFirst(): Boolean = cache.syncValue(ACCOUNT_KEY) != account || cache.romCount() == 0L

  /**
   * Brings the cache up to date with the server. Platforms and collections are small, so they're
   * fetched whole every time. ROMs are fetched whole the first time, and afterwards only those the
   * server changed since the last sync. The server's list of ROM ids then tells which ROMs are
   * gone, and which ones the cache lacks, which are fetched one by one. The user's recently played
   * ROMs are fetched whole too, since playing doesn't change a ROM.
   *
   * A sync that fails leaves the cache usable: what it wrote stays, and the next sync fetches the
   * same changes again.
   *
   * @throws RommException when a request fails.
   */
  suspend fun sync() {
    if (cache.syncValue(ACCOUNT_KEY) != account) {
      cache.clear()
      cache.setSyncValue(ACCOUNT_KEY, account)
    }
    val cursor = cache.syncValue(ROM_CURSOR_KEY)?.let(Instant::parse)

    val platforms = client.platforms().map { it.toDomain() }
    cache.replacePlatforms(platforms)

    var newest = cursor
    client.allRoms(cursor?.minus(CURSOR_OVERLAP), pageSize).collect { page ->
      cache.upsertRoms(page.items.map { it.toDomain(client) })
      val pageNewest = page.items.maxOf { it.updatedAt }
      newest = newest?.let { maxOf(it, pageNewest) } ?: pageNewest
    }
    val serverIds = client.romIds().toSet()
    cache.retainRoms(serverIds)
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
    }

    val collections =
      client.collections().map { it.toDomain() to it.romIds } +
        client.smartCollections().map { it.toDomain() to it.romIds }
    cache.replaceCollections(collections)

    val played = client.recentlyPlayed(RECENTLY_PLAYED_LIMIT)
    cache.replaceRecentlyPlayed(
      played.mapNotNull { rom -> rom.romUser?.lastPlayed?.let { rom.id to it } }.toMap(),
    )

    newest?.let { cache.setSyncValue(ROM_CURSOR_KEY, it.toString()) }
  }

  private companion object {
    const val ACCOUNT_KEY = "account"
    const val ROM_CURSOR_KEY = "rom_cursor"

    /**
     * How far before the newest change already seen an incremental sync starts. A change can become
     * visible after changes with later timestamps, such as when a long scan commits, and fetching a
     * ROM twice is harmless.
     */
    val CURSOR_OVERLAP = 10.minutes

    /** How many of the user's most recently played ROMs a sync keeps. */
    const val RECENTLY_PLAYED_LIMIT = 20
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
    fileName = fsName,
    sizeBytes = fsSizeBytes,
    summary = summary,
    genres = metadatum?.genres.orEmpty(),
    releaseDate = metadatum?.firstReleaseDate?.let(Instant::fromEpochMilliseconds),
    coverSmallUrl = coverUrl(pathCoverSmall, client),
    coverLargeUrl = coverUrl(pathCoverLarge, client),
    files = files.map { it.toDomain() },
    createdAt = createdAt,
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
    crcHash = normalHash(crcHash)?.padStart(8, '0'),
    md5Hash = normalHash(md5Hash),
    sha1Hash = normalHash(sha1Hash),
    chdSha1Hash = normalHash(chdSha1Hash),
  )

/** A hash as RomM sends it, in lowercase, or null for none, which RomM may send as empty. */
private fun normalHash(hash: String?): String? = hash?.trim()?.lowercase()?.ifEmpty { null }

internal fun RommCollection.toDomain() =
  Collection(CollectionKind.Regular, id, name, description, romCount, isFavorite)

internal fun RommSmartCollection.toDomain() =
  Collection(CollectionKind.Smart, id, name, description, romCount, isFavorite = false)
