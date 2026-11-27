package dev.sargunv.handheld.domain.storage

/**
 * The files of disc games: playlists (`.m3u`), a disc's descriptor (`.cue`, `.gdi`, `.ccd`, ...)
 * and disc images. The sheets among them, playlists, cue sheets and GDIs, list other files, so a
 * sheet copied in loose with the files it lists beside it is one game, which opens it, as
 * [GameLayout.launchFile] opens a downloaded game of several files.
 */
object DiscSets {
  /**
   * The disc sets among [names], the entries of one folder: each sheet that no other lists, with
   * the files beside it that it lists and that the sheets among them list in turn, in order.
   * [readText] reads a sheet, or gives null when it can't, so it heads no set.
   */
  fun sets(names: List<String>, readText: (String) -> String?): Map<String, List<String>> {
    val listed =
      names
        .filter(::isSheet)
        .mapNotNull { sheet ->
          readText(sheet)?.let { sheet to siblings(listedFiles(sheet, it), names) - sheet }
        }
        .toMap()
    val members = listed.values.flatten().toSet()
    return (listed.keys - members).associateWith { head ->
      walk(head, Int.MAX_VALUE) { listed[it].orEmpty() }
    }
  }

  /**
   * The file that holds the header of the game at [path]: [path] itself, or the file a playlist
   * names first, a cue sheet's first data track's, or a GDI's high-density data track's. [readText]
   * reads a sheet, or gives null when it can't. Null when one can't be read, names no such file, or
   * nests more than three deep.
   */
  fun headerFile(path: String, readText: (String) -> String?): String? {
    val file =
      walk(path, 3) { sheet -> listOfNotNull(readText(sheet)?.let { headerNamed(sheet, it) }) }
        .lastOrNull() ?: path
    return file.takeUnless(::isSheet)
  }

  /**
   * The text of a sheet [size] bytes long, which [read] reads: UTF-8, else Latin-1, as cue sheets
   * made on Windows often are. Null, without reading it, when it's too big to be one; real ones are
   * a few kilobytes.
   */
  fun sheetText(size: Long, read: () -> ByteArray): String? {
    if (size > SHEET_LIMIT) return null
    val bytes = read().takeIf { it.size <= SHEET_LIMIT } ?: return null
    val text = bytes.decodeToString()
    if ('\uFFFD' !in text) return text
    return CharArray(bytes.size) { (bytes[it].toInt() and 0xff).toChar() }.concatToString()
  }

  /** Whether a file named [fileName] is a playlist. */
  internal fun isPlaylist(fileName: String): Boolean = fileName.extension() == "m3u"

  /** Of [files], the discs: their descriptors when they have them, else their images. */
  internal fun discs(files: List<String>): List<String> =
    files
      .filter { it.extension() in Descriptors }
      .ifEmpty { files.filter { it.extension() in Images } }

  /**
   * The files reached from [start] through the sheets it nests, depth first, each once and without
   * [start]: [next] gives the files a sheet leads to, and the walk reads sheets at most [depth]
   * deep.
   */
  private fun walk(start: String, depth: Int, next: (String) -> List<String>): List<String> {
    val seen = mutableSetOf(start)
    fun from(file: String, depth: Int): List<String> =
      if (depth == 0 || !isSheet(file)) emptyList()
      else
        next(file).flatMap { if (seen.add(it)) listOf(it) + from(it, depth - 1) else emptyList() }
    return from(start, depth)
  }

  /** Whether a file named [fileName] is a sheet: a playlist, cue sheet or GDI. */
  private fun isSheet(fileName: String): Boolean = fileName.extension() in Sheets

  /**
   * The files the sheet named [fileName], whose content is [text], lists, as it names them: a
   * playlist's discs, a cue sheet's `FILE`s, or a GDI's tracks, in order.
   */
  private fun listedFiles(fileName: String, text: String): List<String> {
    val lines = lines(text)
    return when (fileName.extension()) {
      "m3u" -> lines.filterNot { it.startsWith('#') }
      "cue" -> lines.mapNotNull { line -> cueArgs(line, "FILE")?.let(::cueFile) }
      else -> gdiTracks(lines).map { it.file }
    }
  }

  /**
   * Of the [names] of the entries in one folder, the ones that [listed], as a sheet there lists
   * them, names. A listed file in another folder names none of them. Names compare without case, as
   * Android's shared storage and cue sheets made on Windows do.
   */
  private fun siblings(listed: List<String>, names: List<String>): List<String> {
    val byName = names.associateBy { it.lowercase() }
    return listed
      .map { it.replace('\\', '/') }
      .filterNot { '/' in it.removePrefix("./") }
      .mapNotNull { byName[it.removePrefix("./").lowercase()] }
      .distinct()
  }

  /**
   * The file the sheet at [path], whose content is [text], names for the header, as a path beside
   * the sheet unless it names a whole one.
   */
  private fun headerNamed(path: String, text: String): String? {
    val lines = lines(text)
    val named =
      when (path.extension()) {
        "m3u" -> lines.firstOrNull { !it.startsWith('#') }
        "cue" -> cueDataFile(lines)
        else -> gdiDataFile(lines)
      }?.replace('\\', '/') ?: return null
    val folder = path.substringBeforeLast('/', "")
    return if (folder.isEmpty() || named.startsWith('/')) named else "$folder/$named"
  }

  /** A sheet's lines, trimmed, without its byte order mark or blank lines. */
  private fun lines(text: String): List<String> =
    text.removePrefix("\uFEFF").lines().map { it.trim() }.filter { it.isNotEmpty() }

  /** The arguments of a cue sheet line that runs [command], in any case. */
  private fun cueArgs(line: String, command: String): String? {
    if (!line.startsWith(command, ignoreCase = true)) return null
    val rest = line.drop(command.length)
    return rest.takeIf { it.firstOrNull()?.isWhitespace() == true }?.trim()
  }

  /** The file a cue sheet's `FILE "name" TYPE` line names, from its [args]. */
  private fun cueFile(args: String): String? {
    if (args.startsWith('"')) return args.drop(1).substringBefore('"').takeIf { it.isNotEmpty() }
    // Unquoted, the name runs to the type at the end of the line, after a space or a tab.
    val typeAt = args.indexOfLast { it.isWhitespace() }
    return (if (typeAt < 0) args else args.take(typeAt)).trim().takeIf { it.isNotEmpty() }
  }

  /** The file of a cue sheet's first data track. */
  private fun cueDataFile(lines: List<String>): String? {
    var file: String? = null
    for (line in lines) {
      cueArgs(line, "FILE")?.let { file = cueFile(it) }
      val track = cueArgs(line, "TRACK") ?: continue
      if (!track.endsWith("AUDIO", ignoreCase = true)) return file
    }
    return null
  }

  /**
   * The file of a GDI's first data track in the high-density area, from track 3, else its first.
   */
  private fun gdiDataFile(lines: List<String>): String? {
    val data = gdiTracks(lines).filter { it.isData }
    return (data.firstOrNull { it.number >= 3 } ?: data.firstOrNull())?.file
  }

  private class GdiTrack(val number: Int, val isData: Boolean, val file: String)

  /**
   * A GDI's tracks. The first line counts them, and each line after it is one: its number, start,
   * type (4 for data), sector size, file and offset.
   */
  private fun gdiTracks(lines: List<String>): List<GdiTrack> =
    lines.drop(1).mapNotNull { line ->
      val fields = tokens(line)
      val file = fields.getOrNull(4) ?: return@mapNotNull null
      val number = fields[0].toIntOrNull() ?: return@mapNotNull null
      GdiTrack(number, isData = fields[2] == "4", file)
    }

  /** A line's fields, split by spaces or tabs, with a quoted field kept whole. */
  private fun tokens(line: String): List<String> {
    val tokens = mutableListOf<String>()
    var rest = line.trim()
    while (rest.isNotEmpty()) {
      if (rest.startsWith('"')) {
        tokens += rest.drop(1).substringBefore('"')
        rest = rest.drop(1).substringAfter('"', "").trim()
      } else {
        val end = rest.indexOfFirst { it.isWhitespace() }.let { if (it < 0) rest.length else it }
        tokens += rest.take(end)
        rest = rest.drop(end).trim()
      }
    }
    return tokens
  }

  /** The files that list other files of a disc set. */
  private val Sheets = setOf("m3u", "cue", "gdi")

  private const val SHEET_LIMIT = 64 * 1024

  /** A disc's descriptor, which an emulator opens for the disc in place of its image. */
  private val Descriptors = setOf("cue", "gdi", "ccd", "mds", "toc")

  private val Images = setOf("chd", "iso", "cdi", "pbp", "img", "bin")

  private fun String.extension() = substringAfterLast('.', "").lowercase()
}
