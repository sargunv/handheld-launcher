package dev.sargunv.handheld.domain.storage

/**
 * Disc sets copied in loose: a playlist (`.m3u`) or a disc descriptor (`.cue`, `.gdi`) in a
 * platform's folder, with the files it lists beside it. Such a set is one game, which opens its
 * playlist or descriptor, as [GameLayout.launchFile] opens a downloaded game of several files.
 */
object DiscSets {
  /** The largest file read as a playlist or descriptor. Real ones are a few kilobytes. */
  const val DESCRIPTOR_LIMIT: Long = 64 * 1024

  /** Whether a file named [fileName] may list other files of its set: a playlist or descriptor. */
  fun isDescriptor(fileName: String): Boolean = fileName.extension() in Descriptors

  /**
   * The files the playlist or descriptor named [fileName], whose content is [text], lists, as it
   * names them: a playlist's discs, a cue sheet's `FILE`s, or a GDI's tracks, in order. Null when
   * [fileName] isn't a playlist or descriptor.
   */
  fun listedFiles(fileName: String, text: String): List<String>? {
    val lines = text.removePrefix("﻿").lines().map { it.trim() }.filter { it.isNotEmpty() }
    return when (fileName.extension()) {
      "m3u" -> lines.filterNot { it.startsWith('#') }
      "cue" -> lines.mapNotNull(::cueFile)
      // The first line counts the tracks, and each line after it names one's file fifth.
      "gdi" -> lines.drop(1).mapNotNull { tokens(it).getOrNull(4) }
      else -> null
    }
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

  /** The file a cue sheet's `FILE "name" TYPE` line names, or null for another line. */
  private fun cueFile(line: String): String? {
    if (!line.startsWith("FILE", ignoreCase = true)) return null
    val rest = line.drop(4)
    if (rest.firstOrNull()?.isWhitespace() != true) return null
    val args = rest.trim()
    if (args.startsWith('"')) return args.drop(1).substringBefore('"').takeIf { it.isNotEmpty() }
    // Unquoted, the name runs to the type at the end of the line.
    return args.substringBeforeLast(' ', args).trim().takeIf { it.isNotEmpty() }
  }

  /** A line's fields, split by spaces, with a quoted field kept whole. */
  private fun tokens(line: String): List<String> {
    val tokens = mutableListOf<String>()
    var rest = line.trim()
    while (rest.isNotEmpty()) {
      if (rest.startsWith('"')) {
        tokens += rest.drop(1).substringBefore('"')
        rest = rest.drop(1).substringAfter('"', "").trim()
      } else {
        tokens += rest.substringBefore(' ')
        rest = rest.substringAfter(' ', "").trim()
      }
    }
    return tokens
  }

  private val Descriptors = setOf("m3u", "cue", "gdi")

  private fun String.extension() = substringAfterLast('.', "").lowercase()
}
