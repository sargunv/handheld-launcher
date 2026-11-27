package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.data.hexDigest
import dev.sargunv.handheld.data.writeAtomically
import dev.sargunv.handheld.domain.saves.FolderSave
import dev.sargunv.handheld.domain.saves.SaveArea
import dev.sargunv.handheld.domain.saves.SaveFolders
import dev.sargunv.handheld.domain.saves.SaveSelection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.time.Instant
import kotlinx.io.files.Path

/**
 * A game's save that an emulator keeps as folders, or as several files in a shared folder: the
 * areas of [save] under the first of [roots], the emulator's save folders, that holds the game's
 * save, else the first that exists. It syncs as one zip.
 */
data class SaveFolderRoots(val roots: List<File>, val save: FolderSave)

/** A game's save as it is on the device: what syncs, and what it's checked by. */
internal class LocalCopy(
  /** The save's file, or its first folder. */
  val file: File,
  /** The name it goes to the server as. */
  val name: String,
  val bytes: ByteArray,
  /** Its hash, as RomM hashes a save. */
  val hash: String,
  val modifiedAt: Instant,
)

/** How [SaveSync] reads and writes a game's save on the device, whatever its shape. */
internal sealed interface SaveUnit {
  /** The save as it is now, or null when there's none. */
  fun read(): LocalCopy?

  /**
   * Whether a pull can put the server's save named [serverName] on the device when there's none,
   * and where. Null when no folder can take it.
   */
  fun target(serverName: String): File?

  /** The folders a pull needs, for [SaveSyncResult.NoFolder]. */
  fun folders(): List<String>

  /**
   * Writes the server's save [bytes] in place of the device's, at [target], which is the save's
   * [LocalCopy.file] or what [target] returned. [replaced] is the hash of the device's save the
   * history already holds. When what the write replaced differs, because the emulator wrote in the
   * meantime, [keep] gets it before it's gone.
   */
  fun write(target: File, bytes: ByteArray, replaced: String?, keep: (LocalCopy) -> Unit)
}

internal fun GameSave.unit(): SaveUnit =
  when (shape) {
    is SaveShape.Files -> FileUnit(shape.candidates)
    is SaveShape.Folders -> FolderUnit(shape.roots)
    is SaveShape.Cards -> Ps2CardUnit(shape.roots)
  }

/** A save that's one file. */
private class FileUnit(private val candidates: List<File>) : SaveUnit {
  override fun read(): LocalCopy? {
    val file = candidates.firstOrNull { it.isFile } ?: return null
    val bytes = file.readBytes()
    return LocalCopy(
      file,
      file.name,
      bytes,
      md5(bytes),
      Instant.fromEpochMilliseconds(file.lastModified()),
    )
  }

  // Folders aren't created, because they may belong to the emulator, which has to own them.
  override fun target(serverName: String): File? {
    val usable = candidates.filter {
      it.parentFile?.let { dir -> dir.isDirectory && dir.canWrite() } == true
    }
    val extension = serverName.substringAfterLast('.', "")
    return usable.firstOrNull { it.extension.equals(extension, ignoreCase = true) }
      ?: usable.firstOrNull()
  }

  override fun folders() = candidates.mapNotNull { it.parent }.distinct()

  override fun write(
    target: File,
    bytes: ByteArray,
    replaced: String?,
    keep: (LocalCopy) -> Unit,
  ) = writeAtomically(Path(target.path), bytes)
}

/**
 * A save that's folders, or several files in a shared folder. The save is every entry the areas
 * select, zipped with each area's entries under its prefix.
 *
 * A pull replaces the selected entries of every area the zip has entries for, and leaves the other
 * areas alone. It writes the zip's entries beside the area's first, then swaps them in, and puts
 * the old ones back when a swap fails.
 */
private class FolderUnit(private val roots: SaveFolderRoots) : SaveUnit {
  /** One area of the save on this device. */
  private class Place(val area: SaveArea, val dir: File) {
    fun owned(): List<File> = dir.listFiles().orEmpty().filter { owns(it) }.sortedBy { it.name }

    fun owns(entry: File) =
      !entry.name.startsWith(RESERVED) &&
        area.selection.claims(entry.name) &&
        !(area.selection is SaveSelection.Prefixed && isPspGameData(entry))
  }

  /** The root the save is in, or null when none exists. */
  private fun root(): File? {
    val existing = roots.roots.filter { it.isDirectory }
    return existing.firstOrNull { root -> places(root)?.any { it.owned().isNotEmpty() } == true }
      ?: existing.firstOrNull()
  }

  /**
   * The save's areas under [root]. Without a Switch profile to put the account save in, only the
   * areas that don't depend on one, such as the device save, or null when there are none.
   */
  private fun places(root: File): List<Place>? {
    val profile =
      if (roots.save.areas.any { SaveFolders.PROFILE in it.folder }) profile(root) else ""
    val areas =
      if (profile != null) roots.save.areas
      else
        roots.save.areas
          .filter { SaveFolders.PROFILE !in it.folder }
          .ifEmpty {
            return null
          }
    return areas.map {
      Place(it, File(root, it.folder.replace(SaveFolders.PROFILE, profile.orEmpty())))
    }
  }

  /**
   * The Switch profile whose folder holds the game's save, else the only profile. Null when there's
   * no profile yet, or several and none has the save.
   */
  private fun profile(root: File): String? {
    val users = File(root, SaveFolders.SWITCH_USERS)
    val profiles =
      users
        .listFiles { it: File -> it.isDirectory && PROFILE_ID.matches(it.name) }
        .orEmpty()
        .map { it.name }
        .filter { it != SaveFolders.SWITCH_DEVICE }
    val account = roots.save.areas.first { SaveFolders.PROFILE in it.folder }
    // A profile holds the save when its folder has something the save owns, not only the
    // emulator's own files, such as the size it keeps beside a save.
    val holding = profiles.filter {
      Place(account, File(root, account.folder.replace(SaveFolders.PROFILE, it)))
        .owned()
        .isNotEmpty()
    }
    if (holding.size > 1) {
      throw IOException("Several Switch profiles hold this save")
    }
    return holding.singleOrNull() ?: profiles.singleOrNull()
  }

  override fun read(): LocalCopy? {
    val root = root() ?: return null
    return collect(places(root) ?: return null)
  }

  /** The entries [places] select, as one zip. */
  private fun collect(places: List<Place>): LocalCopy? {
    val entries = mutableListOf<Pair<String, File>>()
    for (place in places) {
      for (owned in place.owned()) {
        owned.walkTopDown().forEach { file ->
          val name = place.area.zipPrefix + file.relativeTo(place.dir).invariantSeparatorsPath
          when {
            file.isFile -> entries += name to file
            // An empty folder may matter to the emulator, so it goes too.
            file.isDirectory && file.list()?.isEmpty() == true -> entries += "$name/" to file
          }
        }
      }
    }
    if (entries.none { it.second.isFile }) return null
    val sorted = entries.sortedWith(compareBy(CODE_POINTS) { it.first })
    val out = ByteArrayOutputStream()
    val hashes = mutableListOf<Pair<String, String>>()
    ZipOutputStream(out).use { zip ->
      for ((name, file) in sorted) {
        val entry = ZipEntry(name)
        entry.time = file.lastModified()
        zip.putNextEntry(entry)
        if (file.isFile) {
          val bytes = file.readBytes()
          zip.write(bytes)
          hashes += name to md5(bytes)
        }
        zip.closeEntry()
      }
    }
    return LocalCopy(
      places.first().dir,
      roots.save.name,
      out.toByteArray(),
      combinedHash(hashes),
      Instant.fromEpochMilliseconds(sorted.maxOf { it.second.lastModified() }),
    )
  }

  override fun target(serverName: String): File? {
    val root = roots.roots.firstOrNull { it.isDirectory && it.canWrite() } ?: return null
    return places(root)?.first()?.dir
  }

  override fun folders() = roots.roots.map { it.path }

  override fun write(
    target: File,
    bytes: ByteArray,
    replaced: String?,
    keep: (LocalCopy) -> Unit,
  ) {
    val root = roots.roots.first { target.path.startsWith(it.path) }
    val places = places(root) ?: throw IOException("Save folder unknown")
    val entries = unzip(bytes)
    if (entries.none { !it.first.endsWith("/") }) {
      throw IOException("Empty save")
    }
    // Every entry goes to an area, as one of the entries it selects, or nothing is written.
    val byPlace = linkedMapOf<Place, MutableList<Pair<String, ByteArray?>>>()
    for ((name, data) in entries) {
      // A folder above the areas, which zips may list.
      if (data == null && places.any { it.area.zipPrefix.startsWith(name) }) continue
      val place =
        places
          .filter { name.startsWith(it.area.zipPrefix) }
          .maxByOrNull { it.area.zipPrefix.length }
      val rest = place?.let { name.removePrefix(it.area.zipPrefix) }
      val top = rest?.substringBefore('/')
      if (
        place == null ||
          top.isNullOrEmpty() ||
          top.startsWith(RESERVED) ||
          !place.area.selection.claims(top)
      ) {
        throw IOException("$name isn't part of this save")
      }
      byPlace.getOrPut(place) { mutableListOf() } += rest to data
    }
    for ((place, files) in byPlace) {
      for (top in files.map { it.first.substringBefore('/') }.toSet()) {
        val there = File(place.dir, top)
        if (there.exists() && !place.owns(there)) {
          throw IOException("${there.name} isn't part of this save")
        }
      }
    }

    // Write everything beside the areas first, then swap it in.
    val staged = mutableListOf<Pair<Place, File>>()
    val stamp = System.currentTimeMillis()
    try {
      for ((place, files) in byPlace) {
        place.dir.mkdirs()
        val staging = File(place.dir, "$RESERVED$stamp-new")
        staged += place to staging
        for ((path, data) in files) {
          val file = File(staging, path)
          if (data == null) {
            file.mkdirs()
          } else {
            file.parentFile.mkdirs()
            file.writeBytes(data)
          }
        }
      }
      val swaps = staged.associate { (place, staging) ->
        place to Swap(place.dir, place.owned(), staging, File(place.dir, "$RESERVED$stamp-old"))
      }
      swapIn(swaps.values.toList())
      // The old entries are in the history already, unless the emulator changed them since.
      val moved =
        collect(places.map { swaps[it]?.let { swap -> Place(it.area, swap.aside) } ?: it })
      if (moved != null && moved.hash != replaced) keep(moved)
      swaps.values.forEach { it.aside.deleteRecursively() }
    } finally {
      for ((_, staging) in staged) staging.deleteRecursively()
    }
  }

  private companion object {
    val PROFILE_ID = Regex("[0-9A-Fa-f]{16}|[0-9A-Fa-f]{32}")
  }
}

/** What starts the names of the folders a [swapIn] stages and sets aside, which no save claims. */
internal const val RESERVED = ".handheld-"

/**
 * One folder's part of a [swapIn]: in [dir], the entries [old] go to [aside], and [staged]'s in.
 */
internal class Swap(val dir: File, val old: List<File>, val staged: File, val aside: File)

/**
 * Puts each of [swaps]' staged entries in place of its old ones, which move aside, so an emulator
 * never sees a save half written. When a move fails, every move is undone.
 */
internal fun swapIn(swaps: List<Swap>) {
  val undo = ArrayDeque<() -> Unit>()
  try {
    for (swap in swaps) {
      if (!swap.aside.mkdirs()) throw IOException("Couldn't make ${swap.aside.path}")
      undo.addFirst { swap.aside.delete() }
      for (entry in swap.old) {
        val moved = File(swap.aside, entry.name)
        Files.move(entry.toPath(), moved.toPath(), ATOMIC_MOVE)
        undo.addFirst { Files.move(moved.toPath(), entry.toPath(), ATOMIC_MOVE) }
      }
      for (entry in swap.staged.listFiles().orEmpty()) {
        val placed = File(swap.dir, entry.name)
        Files.move(entry.toPath(), placed.toPath(), ATOMIC_MOVE)
        undo.addFirst { placed.deleteRecursively() }
      }
    }
  } catch (e: IOException) {
    for (step in undo) {
      try {
        step()
      } catch (suppressed: IOException) {
        e.addSuppressed(suppressed)
      }
    }
    throw e
  }
}

/**
 * Whether [folder] in a PSP's SAVEDATA is a game's installed data rather than a save: its PARAM.SFO
 * carries neither of the keys every save's does. A folder without one, or with one that can't be
 * read, counts as a save, so it's never left out by mistake.
 */
private fun isPspGameData(folder: File): Boolean {
  val sfo = File(folder, "PARAM.SFO")
  if (!sfo.isFile) return false
  val bytes =
    try {
      sfo.readBytes()
    } catch (e: IOException) {
      return false
    }
  if (bytes.size < 4 || bytes.copyOf(4).decodeToString() != "\u0000PSF") return false
  val text = bytes.decodeToString()
  return "SAVEDATA_FILE_LIST" !in text && "SAVEDATA_PARAMS" !in text
}

/**
 * A zip's entries, with null for a folder's. Fails on an entry that would land outside the folder
 * it's unpacked into.
 */
private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray?>> {
  val entries = mutableListOf<Pair<String, ByteArray?>>()
  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    while (true) {
      val entry = zip.nextEntry ?: break
      val name = entry.name
      val segments = name.removeSuffix("/").split('/')
      if (
        name.startsWith("/") ||
          '\\' in name ||
          segments.any { it.isEmpty() || it == "." || it == ".." }
      ) {
        throw IOException("Unsafe path $name")
      }
      entries += name to if (entry.isDirectory) null else zip.readBytes()
    }
  }
  return entries
}

/**
 * [bytes]'s hash as RomM's `content_hash`: a zip's is the MD5 of each file entry's name and MD5,
 * one `<name>:<md5>` per line in name order, and any other file's its MD5.
 */
internal fun contentHash(bytes: ByteArray): String {
  if (!isZip(bytes)) return md5(bytes)
  val hashes = mutableListOf<Pair<String, String>>()
  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    while (true) {
      val entry = zip.nextEntry ?: break
      if (!entry.name.endsWith("/")) hashes += entry.name to md5(zip.readBytes())
    }
  }
  return combinedHash(hashes)
}

private fun isZip(bytes: ByteArray) =
  bytes.size >= 4 &&
    bytes[0] == 'P'.code.toByte() &&
    bytes[1] == 'K'.code.toByte() &&
    ((bytes[2].toInt() == 3 && bytes[3].toInt() == 4) ||
      (bytes[2].toInt() == 5 && bytes[3].toInt() == 6))

private fun combinedHash(hashes: List<Pair<String, String>>): String =
  md5(
    hashes
      .sortedWith(compareBy(CODE_POINTS) { it.first })
      .joinToString("\n") { "${it.first}:${it.second}" }
      .encodeToByteArray(),
  )

// Python sorts names by code point, which UTF-16 order differs from past the BMP.
internal val CODE_POINTS =
  Comparator<String> { a, b ->
    val x = a.codePoints().toArray()
    val y = b.codePoints().toArray()
    (x zip y).firstOrNull { (i, j) -> i != j }?.let { (i, j) -> i.compareTo(j) }
      ?: x.size.compareTo(y.size)
  }

internal fun md5(bytes: ByteArray): String = hexDigest("MD5", bytes)
