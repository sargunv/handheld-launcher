package dev.sargunv.handheld.data.cache

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.Query
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import dev.sargunv.handheld.data.cache.db.Collection as CollectionRow
import dev.sargunv.handheld.data.cache.db.Collection_rom as CollectionRomRow
import dev.sargunv.handheld.data.cache.db.LibraryDatabase
import dev.sargunv.handheld.data.cache.db.Platform as PlatformRow
import dev.sargunv.handheld.data.cache.db.Recently_played
import dev.sargunv.handheld.data.cache.db.Rom as RomRow
import dev.sargunv.handheld.data.cache.db.Rom_file
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.RomFile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The library as this device last saw it: platforms, ROMs and collections, kept in a SQLite
 * database so browsing works offline. [LibrarySync] fills it from the server.
 *
 * The flows emit the current contents, then again whenever a sync changes them.
 */
class LibraryCache
private constructor(
  private val driver: SqlDriver,
  private val queryContext: CoroutineContext,
) : AutoCloseable {
  private val database =
    LibraryDatabase(
      driver,
      collectionAdapter = CollectionRow.Adapter(kindAdapter = EnumColumnAdapter()),
      collection_romAdapter =
        CollectionRomRow.Adapter(collection_kindAdapter = EnumColumnAdapter()),
      romAdapter =
        RomRow.Adapter(
          alternative_namesAdapter = StringListAdapter,
          genresAdapter = StringListAdapter,
          regionsAdapter = StringListAdapter,
          release_dateAdapter = InstantAdapter,
          updated_atAdapter = InstantAdapter,
          created_atAdapter = InstantAdapter,
        ),
      rom_fileAdapter = Rom_file.Adapter(categoryAdapter = EnumColumnAdapter()),
      recently_playedAdapter = Recently_played.Adapter(last_playedAdapter = InstantAdapter),
    )
  private val queries = database.libraryQueries

  override fun close() = driver.close()

  fun platforms(): Flow<List<Platform>> =
    queries.platforms().asFlow().map { query ->
      transaction { query.awaitAsList().map { it.toDomain() } }
    }

  suspend fun platform(id: Long): Platform? = transaction {
    queries.platform(id).awaitAsOneOrNull()?.toDomain()
  }

  fun collections(): Flow<List<Collection>> =
    queries.collections().asFlow().map { query ->
      transaction { query.awaitAsList().map { it.toDomain() } }
    }

  /** The collections [romId] is in, in the order [collections] lists them. */
  suspend fun collectionsOf(romId: Long): List<Collection> = transaction {
    queries.collectionsOfRom(romId).awaitAsList().map { it.toDomain() }
  }

  /** Every ROM in the library, in title order. */
  fun allRoms(): Flow<List<Rom>> =
    queries.allRoms().asFlow().map { query ->
      transaction { withFiles(query.awaitAsList(), queries.allFiles().awaitAsList()) }
    }

  /** The ROMs on a platform, in title order. */
  fun romsOnPlatform(platformId: Long): Flow<List<Rom>> =
    queries.romsOnPlatform(platformId).asFlow().map { query ->
      transaction {
        withFiles(query.awaitAsList(), queries.filesOnPlatform(platformId).awaitAsList())
      }
    }

  /** The ROMs in a collection, in title order. */
  fun romsInCollection(kind: CollectionKind, id: Long): Flow<List<Rom>> =
    queries.romsInCollection(kind, id).asFlow().map { query ->
      transaction {
        withFiles(query.awaitAsList(), queries.filesInCollection(kind, id).awaitAsList())
      }
    }

  /** The ROMs the user played most recently, latest first. */
  fun recentlyPlayed(limit: Int): Flow<List<Rom>> =
    queries.recentlyPlayed(limit.toLong()).asFlow().map { query ->
      transaction { romsWithFiles(query) }
    }

  /** The ROMs added to the server most recently, newest first. */
  fun recentlyAdded(limit: Int): Flow<List<Rom>> =
    queries.recentlyAdded(limit.toLong()).asFlow().map { query ->
      transaction { romsWithFiles(query) }
    }

  suspend fun rom(id: Long): Rom? = transaction {
    queries
      .rom(id)
      .awaitAsOneOrNull()
      ?.toDomain(queries.filesOfRom(id).awaitAsList().map { it.toDomain() })
  }

  /**
   * The ROMs whose titles, alternative names or file names have words starting with each word of
   * [text], best matches first. Accents and case don't matter.
   */
  suspend fun search(text: String, limit: Int = 50): List<Rom> {
    val query = ftsQuery(text) ?: return emptyList()
    return transaction {
      val roms = queries.search(query, limit.toLong()).awaitAsList()
      withFiles(roms, queries.filesOfRoms(roms.map { it.id }).awaitAsList())
    }
  }

  suspend fun romCount(): Long = transaction { queries.romCount().awaitAsOne() }

  internal suspend fun replacePlatforms(platforms: List<Platform>) = transaction {
    queries.deletePlatforms()
    for (platform in platforms) {
      queries.insertPlatform(
        PlatformRow(
          id = platform.id,
          slug = platform.slug,
          fs_slug = platform.fsSlug,
          name = platform.name,
          rom_count = platform.romCount.toLong(),
        ),
      )
    }
  }

  internal suspend fun upsertRoms(roms: List<Rom>) = transaction {
    for (rom in roms) {
      queries.upsertRom(rom.toRow())
      queries.deleteFilesOfRom(rom.id)
      for (file in rom.files) {
        queries.insertFile(Rom_file(file.id, rom.id, file.fileName, file.sizeBytes, file.category))
      }
    }
  }

  internal suspend fun romIds(): Set<Long> = transaction { queries.romIds().awaitAsList().toSet() }

  /** Removes the ROMs whose ids aren't in [ids], and returns how many it removed. */
  internal suspend fun retainRoms(ids: Set<Long>): Int = transaction {
    val removed = queries.romIds().awaitAsList().filterNot { it in ids }
    for (id in removed) {
      queries.deleteRom(id)
      queries.deleteFilesOfRom(id)
    }
    removed.size
  }

  internal suspend fun replaceCollections(collections: List<Pair<Collection, List<Long>>>) =
    transaction {
      queries.deleteCollections()
      queries.deleteCollectionRoms()
      for ((collection, romIds) in collections) {
        queries.insertCollection(
          CollectionRow(
            kind = collection.kind,
            id = collection.id,
            name = collection.name,
            description = collection.description,
            rom_count = collection.romCount.toLong(),
            is_favorite = collection.isFavorite,
          ),
        )
        for (romId in romIds) {
          queries.insertCollectionRom(CollectionRomRow(collection.kind, collection.id, romId))
        }
      }
    }

  /** Replaces the recently played ROMs with [lastPlayed], ROM ids to when the user played them. */
  internal suspend fun replaceRecentlyPlayed(lastPlayed: Map<Long, Instant>) = transaction {
    queries.deleteRecentlyPlayed()
    for ((romId, time) in lastPlayed) queries.insertRecentlyPlayed(Recently_played(romId, time))
  }

  internal suspend fun syncValue(key: String): String? = transaction {
    queries.syncValue(key).awaitAsOneOrNull()
  }

  internal suspend fun setSyncValue(key: String, value: String) = transaction {
    queries.setSyncValue(key, value)
  }

  /** Empties the cache. */
  internal suspend fun clear() = transaction {
    queries.deletePlatforms()
    queries.deleteAllRoms()
    queries.deleteAllFiles()
    queries.deleteCollections()
    queries.deleteCollectionRoms()
    queries.deleteRecentlyPlayed()
    queries.deleteSyncState()
  }

  /** The ROMs [query] selects, with their files. */
  private suspend fun romsWithFiles(query: Query<RomRow>): List<Rom> {
    val roms = query.awaitAsList()
    return withFiles(roms, queries.filesOfRoms(roms.map { it.id }).awaitAsList())
  }

  /** Runs [block] in a transaction, so it sees or makes one consistent state. */
  private suspend fun <T> transaction(block: suspend () -> T): T =
    withContext(queryContext) { database.transactionWithResult { block() } }

  companion object {
    /**
     * Opens the cache in the SQLite database at [path], creating it when it doesn't exist, or in
     * memory when [path] is null.
     *
     * @param queryContext where queries run, since the driver blocks the thread it runs on.
     */
    fun open(path: String?, queryContext: CoroutineContext = Dispatchers.IO): LibraryCache {
      val driver =
        AndroidxSqliteDriver(
          // The bundled SQLite has FTS5 on every platform. Android's own SQLite doesn't promise it.
          driver = BundledSQLiteDriver(),
          databaseType =
            if (path == null) AndroidxSqliteDatabaseType.Memory
            else AndroidxSqliteDatabaseType.File(path),
          schema = LibraryDatabase.Schema,
        )
      return LibraryCache(driver, queryContext)
    }
  }
}

/** An FTS5 query that matches rows with a word starting with each word of [text]. */
internal fun ftsQuery(text: String): String? =
  text
    // Combining marks belong to their letter, as they do for the index's tokenizer.
    .split(Regex("""[^\p{L}\p{M}\p{N}]+"""))
    .filter { it.isNotEmpty() }
    .joinToString(" ") { "\"$it\"*" }
    .ifEmpty { null }

private fun withFiles(roms: List<RomRow>, files: List<Rom_file>): List<Rom> {
  val filesByRom = files.groupBy({ it.rom_id }) { it.toDomain() }
  return roms.map { it.toDomain(filesByRom[it.id].orEmpty()) }
}

private fun Rom_file.toDomain() = RomFile(id, file_name, size_bytes, category)

private fun PlatformRow.toDomain() = Platform(id, slug, fs_slug, name, rom_count.toInt())

private fun CollectionRow.toDomain() =
  Collection(kind, id, name, description, rom_count.toInt(), is_favorite)

private fun RomRow.toDomain(files: List<RomFile>) =
  Rom(
    id = id,
    platformId = platform_id,
    platformSlug = platform_slug,
    title = title,
    sortKey = sort_key,
    fileName = file_name,
    sizeBytes = size_bytes,
    summary = summary,
    alternativeNames = alternative_names,
    genres = genres,
    regions = regions,
    releaseDate = release_date,
    coverSmallUrl = cover_small_url,
    coverLargeUrl = cover_large_url,
    hasMultipleFiles = has_multiple_files,
    files = files,
    updatedAt = updated_at,
    createdAt = created_at,
  )

private fun Rom.toRow() =
  RomRow(
    id = id,
    platform_id = platformId,
    platform_slug = platformSlug,
    title = title,
    sort_key = sortKey,
    file_name = fileName,
    size_bytes = sizeBytes,
    summary = summary,
    alternative_names = alternativeNames,
    genres = genres,
    regions = regions,
    release_date = releaseDate,
    cover_small_url = coverSmallUrl,
    cover_large_url = coverLargeUrl,
    has_multiple_files = hasMultipleFiles,
    updated_at = updatedAt,
    created_at = createdAt,
  )

/**
 * Stores a list of strings in one column, separated by a control character that names don't hold
 * and that the search index's tokenizer treats as a word break.
 */
private object StringListAdapter : ColumnAdapter<List<String>, String> {
  private const val SEPARATOR = '\u001F'

  override fun decode(databaseValue: String) =
    if (databaseValue.isEmpty()) emptyList() else databaseValue.split(SEPARATOR)

  override fun encode(value: List<String>) = value.joinToString(SEPARATOR.toString())
}

/** Stores an instant as milliseconds since the epoch. */
private object InstantAdapter : ColumnAdapter<Instant, Long> {
  override fun decode(databaseValue: Long) = Instant.fromEpochMilliseconds(databaseValue)

  override fun encode(value: Instant) = value.toEpochMilliseconds()
}
