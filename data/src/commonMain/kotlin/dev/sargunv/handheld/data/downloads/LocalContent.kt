package dev.sargunv.handheld.data.downloads

import dev.sargunv.handheld.data.JsonFile
import dev.sargunv.handheld.domain.Platform
import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.RomFile
import dev.sargunv.handheld.domain.storage.DiscSets
import dev.sargunv.handheld.domain.storage.GameLayout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A file in a platform's folder that no game of the library is named for or matched by content. A
 * disc set copied in loose is one: its sheet, with the files it lists as [otherFiles].
 */
internal data class LocalFile(
  val path: String,
  /** The platform whose folder holds the file. */
  val platformId: Long,
  /** The size of the file itself. */
  val sizeBytes: Long,
  /** When the file last changed, in milliseconds since the epoch. */
  val modifiedMillis: Long = 0,
  /** The other files of a disc set, which the file lists. */
  val otherFiles: List<LocalPart> = emptyList(),
) {
  /** The size of the file and its [otherFiles]. */
  val totalBytes: Long
    get() = sizeBytes + otherFiles.sumOf { it.sizeBytes }
}

/** One of the other files of a disc set, as a scan found it. */
internal data class LocalPart(val path: String, val sizeBytes: Long)

/**
 * What the latest scan of the platforms' folders found besides the games named as RomM names them.
 */
internal data class LocalScan(
  /** The library's games found by content in a file of another name, by ROM id: the file's path. */
  val matched: Map<Long, String> = emptyMap(),
  /** The files that may be games and that no game of the library matched, by name or content. */
  val unmatched: List<LocalFile> = emptyList(),
)

/**
 * What a file holds, as far as a scan read it, in lowercase hex. Each is null when the scan didn't
 * read it, so a file the scan can't match has none.
 */
@Serializable
internal data class FileContent(
  /** The CRC-32 of the file, or of the one file in a zip. */
  val crc: String? = null,
  /** The SHA-1 of the file, when the scan read it whole. */
  val sha1: String? = null,
  /** The SHA-1 a CHD's header records for the disc it holds. */
  val chdSha1: String? = null,
)

/** A file's content as a scan read it, while the file keeps its size and modified time. */
@Serializable
private data class CachedContent(
  val path: String,
  val sizeBytes: Long,
  val modified: Long,
  val content: FileContent,
)

/**
 * Finds the library's games in the platforms' folders by their content, in files of other names,
 * such as games the player copied in themselves. A single-file ROM matches a file in its platform's
 * folder with the same content: RomM's CRC-32 (and SHA-1, when it has one) for a file, the CRC-32
 * in a zip's central directory for a zip of one file, and the SHA-1 in the header of a CHD. RomM
 * hashes what a zip holds, so a zip matches the file it holds and the other way around.
 *
 * Reading a zip or a CHD reads only its directory or header, so any size is quick. A scan reads
 * another file whole only when it could be a game: when it's no bigger than [wholeFileLimit], or
 * the size of one of its platform's games. What it read is kept in [cacheFile] while the file keeps
 * its size and modified time, so a scan reads each file once.
 */
internal class ContentScanner(
  cacheFile: Path,
  private val wholeFileLimit: Long = WHOLE_FILE_LIMIT,
) {
  private val file = JsonFile(cacheFile, ListSerializer(CachedContent.serializer()))
  private val mutex = Mutex()
  private var cache: MutableMap<String, CachedContent>? = null
  /** Whether the cache has changes the file lacks. */
  private var dirty = false

  /**
   * Scans each platform's folder on each of the [targets] for files that aren't [roms] by name, and
   * matches them to [roms] by content. A ROM on the device by name isn't matched again. A disc set
   * copied in loose is one file, its sheet, and the files it lists aren't read. Files that aren't
   * games by [isGameFile] aren't read or listed.
   */
  suspend fun scan(
    targets: List<StorageTarget>,
    platforms: List<Platform>,
    roms: List<Rom>,
  ): LocalScan = mutex.withLock {
    val cache = cache ?: loadCache().also { cache = it }
    val seen = mutableSetOf<String>()
    val matched = mutableMapOf<Long, String>()
    val unmatched = mutableListOf<LocalFile>()
    val romsByPlatform = roms.groupBy { it.platformId }
    // Platforms that share a folder share its files.
    for ((folderName, sharing) in platforms.groupBy { it.fsSlug }) {
      val folderRoms = sharing.flatMap { romsByPlatform[it.id].orEmpty() }
      val names = folderRoms.mapNotNullTo(mutableSetOf(), GameLayout::romName)
      val entries = targets.flatMap {
        entriesOf(Path(GameLayout.platformFolder(it.root, folderName)))
      }
      val present = entries.mapTo(mutableSetOf()) { it.name }
      // Each target's files in name order, the targets in the order they fill.
      val loose = entries.filterNot { it.name in names || !isGameFile(it.name) }
      val candidates = folderRoms.filter {
        it.files.size == 1 && GameLayout.romName(it) !in present
      }
      val sizes = candidates.mapTo(mutableSetOf()) { it.files.single().sizeBytes }
      // A set's files are read only where that's quick, or where one could be a game by its size.
      // A set with a file that's a game of the library is its files, each on its own.
      val allSets = discSets(loose)
      val games = mutableSetOf<Path>()
      for (part in allSets.values.flatten()) {
        // What it read stays in the cache.
        seen += part.toString()
        val content = quickContentOf(part, sizes, cache)
        if (content != null && candidates.any { content.matches(it.files.single()) }) games += part
      }
      val sets = allSets.filterValues { parts -> parts.none { it in games } }
      val listed = sets.values.flatten().toSet()
      val files = loose.filterNot { it in listed }
      for (path in files) {
        currentCoroutineContext().ensureActive()
        val metadata = SystemFileSystem.metadataOrNull(path)
        if (metadata == null || !metadata.isRegularFile) continue
        val content =
          try {
            contentOf(path, metadata.size, sizes, cache)
          } catch (e: IOException) {
            // A file the app can't read, which a later scan tries again.
            null
          }
        seen += path.toString()
        val found = content?.let { c -> candidates.filter { c.matches(it.files.single()) } }
        if (found.isNullOrEmpty()) {
          val others =
            sets[path].orEmpty().mapNotNull { part ->
              SystemFileSystem.metadataOrNull(part)
                ?.takeIf { it.isRegularFile }
                ?.let { LocalPart(part.toString(), it.size) }
            }
          unmatched +=
            LocalFile(
              path.toString(),
              sharing.first().id,
              metadata.size,
              lastModified(path),
              others,
            )
        } else {
          for (rom in found) matched.getOrPut(rom.id) { path.toString() }
        }
      }
    }
    // What a scan no longer sees, such as a deleted file, leaves the cache.
    if (cache.keys.retainAll(seen) || dirty) {
      try {
        file.write(cache.values.toList())
        dirty = false
      } catch (e: IOException) {
        // The next scan writes it again, and meanwhile reads only what this one hasn't.
      }
    }
    LocalScan(matched, unmatched)
  }

  /**
   * Whether the file at [path] still has the content of [rom]'s one file, read from the file
   * itself: a removal trusts it, and the cache can't tell a same-size, same-time replacement apart.
   */
  suspend fun stillMatches(path: Path, rom: Rom): Boolean = mutex.withLock {
    val romFile = rom.files.singleOrNull() ?: return false
    val metadata = SystemFileSystem.metadataOrNull(path)
    if (metadata == null || !metadata.isRegularFile) return false
    val cache = cache ?: loadCache().also { cache = it }
    try {
      contentOf(path, metadata.size, setOf(romFile.sizeBytes), cache, fresh = true)
        ?.matches(romFile) == true
    } catch (e: IOException) {
      false
    }
  }

  /**
   * What the file at [path] holds, as far as it's quick to read: a zip's directory, a CHD's header,
   * or another file of one of the [sizes]. Null when it can't be read so.
   */
  private fun quickContentOf(
    path: Path,
    sizes: Set<Long>,
    cache: MutableMap<String, CachedContent>,
  ): FileContent? {
    val metadata = SystemFileSystem.metadataOrNull(path)
    if (metadata == null || !metadata.isRegularFile) return null
    return try {
      contentOf(path, metadata.size, sizes, cache, quick = true)
    } catch (e: IOException) {
      null
    }
  }

  /**
   * What the file at [path] holds, from the cache while it's unchanged, or null if it can't be a
   * game: a file bigger than [wholeFileLimit], or with [quick] any file, is read whole only when
   * it's one of the [sizes]. With [fresh], it reads the file even if the cache has it.
   */
  private fun contentOf(
    path: Path,
    size: Long,
    sizes: Set<Long>,
    cache: MutableMap<String, CachedContent>,
    fresh: Boolean = false,
    quick: Boolean = false,
  ): FileContent? {
    val modified = lastModified(path)
    cache[path.toString()]
      ?.takeIf { !fresh && it.sizeBytes == size && it.modified == modified }
      ?.let {
        return it.content
      }
    val content =
      when (path.name.substringAfterLast('.', "").lowercase()) {
        "zip" -> FileContent(crc = oneFileZipCrc(path))
        "chd" -> FileContent(chdSha1 = chdSha1(path, size))
        else -> {
          if ((quick || size > wholeFileLimit) && size !in sizes) return null
          val (crc, sha1) = crcAndSha1(path)
          FileContent(crc = crc, sha1 = sha1)
        }
      }
    cache[path.toString()] = CachedContent(path.toString(), size, modified, content)
    dirty = true
    return content
  }

  private suspend fun loadCache(): MutableMap<String, CachedContent> =
    file.read().orEmpty().associateByTo(mutableMapOf()) { it.path }

  companion object {
    /**
     * The largest file a scan reads whole without knowing a game of its size: 64 MiB, the size of
     * the largest cartridges (N64), so a disc image is read only when its size could be a game's.
     */
    const val WHOLE_FILE_LIMIT = 64L * 1024 * 1024
  }
}

/** Whether [file] has the content RomM hashed for it, as far as this content was read. */
internal fun FileContent.matches(file: RomFile): Boolean {
  chdSha1?.let {
    // RomM 5.4 puts the header's SHA-1 in sha1_hash, and later versions in chd_sha1_hash.
    return file.chdSha1Hash == it ||
      (file.fileName.endsWith(".chd", ignoreCase = true) && file.sha1Hash == it)
  }
  val crc = crc ?: return false
  if (file.crcHash != null) {
    return file.crcHash == crc && (sha1 == null || file.sha1Hash == null || file.sha1Hash == sha1)
  }
  return sha1 != null && file.sha1Hash == sha1
}

/**
 * The SHA-1 a CHD's header records for the disc it holds (its `sha1`, which covers the data and its
 * metadata), or null when [header] isn't the start of a CHD of version 5.
 */
internal fun chdHeaderSha1(header: ByteArray): String? {
  if (header.size < CHD_HEADER_SIZE) return null
  if (header.copyOfRange(0, 8).decodeToString() != "MComprHD") return null
  if (header.readInt(8) < CHD_HEADER_SIZE || header.readInt(12) != 5) return null
  return header.copyOfRange(84, 104).toHexString().takeIf { it.any { c -> c != '0' } }
}

private fun chdSha1(path: Path, size: Long): String? {
  if (size < CHD_HEADER_SIZE) return null
  val header = SystemFileSystem.source(path).buffered().use { it.readByteArray(CHD_HEADER_SIZE) }
  return chdHeaderSha1(header)
}

private const val CHD_HEADER_SIZE = 124

private fun ByteArray.readInt(offset: Int): Int =
  (0 until 4).fold(0) { value, i -> (value shl 8) or (this[offset + i].toInt() and 0xff) }

/**
 * The disc sets copied in loose among [files], the entries of platforms' folders, as
 * [DiscSets.sets] finds them.
 */
private fun discSets(files: List<Path>): Map<Path, List<Path>> = buildMap {
  for (folder in files.groupBy { it.parent }.values) {
    val byName = folder.associateBy { it.name }
    for ((head, members) in
      DiscSets.sets(folder.map { it.name }) { sheetText(byName.getValue(it)) }) {
      put(byName.getValue(head), members.map(byName::getValue))
    }
  }
}

/**
 * The file that holds the header of the game an emulator opens at [path], as [DiscSets.headerFile]
 * finds it.
 */
fun headerFile(path: String): String? = DiscSets.headerFile(path) { sheetText(Path(it)) }

/** The text of the sheet at [path], as [DiscSets.sheetText] reads it, or null when it can't. */
private fun sheetText(path: Path): String? {
  val metadata = SystemFileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile } ?: return null
  return try {
    DiscSets.sheetText(metadata.size) {
      SystemFileSystem.source(path).buffered().use { it.readByteArray() }
    }
  } catch (e: IOException) {
    null
  }
}

/** Whether the file named [name] may be a game: hidden files, [NotGames] and save states aren't. */
internal fun isGameFile(name: String): Boolean {
  if (name.startsWith('.')) return false
  val extension = name.substringAfterLast('.', "").lowercase()
  return extension !in NotGames && !extension.matches(StateExtension)
}

/**
 * The extensions of files that sit beside games but aren't games: saves, pictures, patches and
 * cheats, notes and settings, and files another app is still writing. Not `.md`, a Mega Drive
 * ROM's.
 */
private val NotGames =
  ("srm sav sa1 dsv eep fla sra mpk rtc mcr mcd png jpg jpeg gif bmp webp ips bps ups xdelta cht " +
      "txt nfo pdf xml json ini cfg log db tmp bak part")
    .split(' ')
    .toSet()

/** Save states, such as `.state`, `.state1` or `.st0`, and RetroArch's `.auto`. */
private val StateExtension = Regex("""state\d*|st\d+|auto""")

/** The entries of [folder] in name order, or none when it isn't a folder that can be read. */
internal fun entriesOf(folder: Path): List<Path> =
  try {
    if (SystemFileSystem.metadataOrNull(folder)?.isDirectory == true) {
      SystemFileSystem.list(folder).sortedBy { it.name }
    } else emptyList()
  } catch (e: IOException) {
    emptyList()
  }
