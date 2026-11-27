package dev.sargunv.handheld.domain.storage

/**
 * The files of disc games: playlists (`.m3u`), a disc's descriptor (`.cue`, `.gdi`, `.ccd`, ...)
 * and disc images. Of these, playlists, cue sheets and GDIs list other files, so one copied in
 * loose with the files it lists beside it is one game, which opens it, as [GameLayout.launchFile]
 * opens a downloaded game of several files.
 */
object DiscSets {
  /** The largest file read as a playlist or descriptor. Real ones are a few kilobytes. */
  const val DESCRIPTOR_LIMIT: Long = 64 * 1024

  /**
   * Whether a file named [fileName] lists other files of its set: a playlist, cue sheet or GDI. Not
   * every disc's descriptor does: a `.ccd` doesn't.
   */
  fun isDescriptor(fileName: String): Boolean = fileName.extension() in Lists

  /**
   * The files the playlist or descriptor named [fileName], whose content is [text], lists, as it
   * names them: a playlist's discs, a cue sheet's `FILE`s, or a GDI's tracks, in order. Null when
   * [fileName] isn't a playlist or descriptor.
   */
  fun listedFiles(fileName: String, text: String): List<String>? {
    val lines = lines(text)
    return when (fileName.extension()) {
      "m3u" -> lines.filterNot { it.startsWith('#') }
      "cue" -> lines.mapNotNull { line -> cueArgs(line, "FILE")?.let(::cueFile) }
      "gdi" -> gdiTracks(lines).map { it.file }
      else -> null
    }
  }

  /**
   * The file that holds the header of the game at [path]: [path] itself, or the file a playlist
   * names first, a cue sheet's first data track's, or a GDI's high-density data track's. [readText]
   * reads a playlist or descriptor, or gives null when it can't. Null when one can't be read, names
   * no such file, or nests more than three deep, so one that names itself ends.
   */
  fun headerFile(path: String, readText: (String) -> String?): String? {
    var file = path
    repeat(3) {
      if (!isDescriptor(file)) return file
      val lines = lines(readText(file) ?: return null)
      val named =
        when (file.extension()) {
          "m3u" -> lines.firstOrNull { !it.startsWith('#') }
          "cue" -> cueDataFile(lines)
          else -> gdiDataFile(lines)
        } ?: return null
      val folder = file.substringBeforeLast('/', "")
      file =
        named.replace('\\', '/').let {
          if (folder.isEmpty() || it.startsWith('/')) it else "$folder/$it"
        }
    }
    return file.takeUnless { isDescriptor(it) }
  }

  /**
   * Of the [names] of the entries in one folder, the ones that [listed], as a playlist or
   * descriptor there lists them, names. A listed file in another folder names none of them. Names
   * compare without case, as Android's shared storage and cue sheets made on Windows do.
   */
  fun siblings(listed: List<String>, names: Collection<String>): List<String> {
    val byName = names.associateBy { it.lowercase() }
    return listed
      .map { it.replace('\\', '/') }
      .filterNot { '/' in it.removePrefix("./") }
      .mapNotNull { byName[it.removePrefix("./").lowercase()] }
      .distinct()
  }

  /** Whether a file named [fileName] is a playlist. */
  internal fun isPlaylist(fileName: String): Boolean = fileName.extension() == "m3u"

  /** Of [files], the discs: their descriptors when they have them, else their images. */
  internal fun discs(files: List<String>): List<String> =
    files
      .filter { it.extension() in Descriptors }
      .ifEmpty { files.filter { it.extension() in Images } }

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

  /** The files that list other files: playlists, cue sheets and GDIs. */
  private val Lists = setOf("m3u", "cue", "gdi")

  /** A disc's descriptor, which an emulator opens for the disc in place of its image. */
  private val Descriptors = setOf("cue", "gdi", "ccd", "mds", "toc")

  private val Images = setOf("chd", "iso", "cdi", "pbp", "img", "bin")

  private fun String.extension() = substringAfterLast('.', "").lowercase()
}
