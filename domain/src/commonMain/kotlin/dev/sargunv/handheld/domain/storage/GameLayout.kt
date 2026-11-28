package dev.sargunv.handheld.domain.storage

import dev.sargunv.handheld.domain.Rom
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug

/**
 * Where games go on a storage target: `<root>/ROMs/<platform folder>/`, named for the platform's
 * folder on the server (its `fs_slug`). A ROM with one file is that file in the platform's folder.
 * A ROM with several files is a folder named as the server names it, holding every file.
 */
object GameLayout {
  /** The folder that holds a platform's games on the storage target at [root]. */
  fun platformFolder(root: String, platformFolder: String): String =
    "${root.trimEnd('/')}/ROMs/$platformFolder"

  /**
   * The name of [rom] on the device, within its platform's folder: its one file's name, or for a
   * ROM with several files, the name of the folder that holds them. Null when that name isn't one
   * entry of the folder, so nothing outside the game's own place is taken for it.
   */
  fun romName(rom: Rom): String? {
    val name = rom.files.singleOrNull()?.fileName ?: rom.fileName
    return name.takeUnless { it.isEmpty() || it == "." || it == ".." || '/' in it }
  }

  /**
   * The discs a playlist (`.m3u`) lists, in order, for a ROM made of [files] on [platform], or null
   * when the ROM needs none: it isn't a multi-disc game, it brings its own playlist, or the
   * platform's emulators don't read one.
   *
   * A disc is its descriptor file (`.cue`, `.gdi`, ...) when the discs have them, else its image.
   *
   * @param files the paths of the ROM's files within its folder, which the playlist lists.
   */
  fun playlist(platform: PlatformSlug, files: List<String>): List<String>? {
    if (platform.emulatedAs !in PlaylistPlatforms) return null
    if (files.any(DiscSets::isPlaylist)) return null
    val discs = DiscSets.discs(files)
    if (discs.size < 2) return null
    return discs.sortedWith(DiscOrder)
  }

  /**
   * The file an emulator opens for a ROM made of several files, among the [files] in its folder:
   * its playlist, else its first disc (by descriptor, else image), else the first file by name.
   * Null when the folder is empty.
   */
  fun launchFile(files: List<String>): String? =
    files.filter(DiscSets::isPlaylist).minOrNull()
      ?: DiscSets.discs(files).minWithOrNull(DiscOrder)
      ?: files.minOrNull()

  private val DiscOrder =
    compareBy<String>(
      { discNumber(it.substringAfterLast('/')) ?: discNumber(it) ?: Int.MAX_VALUE },
      { it },
    )

  /** The platforms whose emulators switch discs through a playlist. */
  private val PlaylistPlatforms =
    setOf(
      PlatformCatalog.PSX,
      PlatformCatalog.SATURN,
      PlatformCatalog.DREAMCAST,
      PlatformCatalog.SEGA_CD,
      PlatformCatalog.TG_CD,
    )

  private val DiscNumber = Regex("""\b(?:disc|disk|cd)\s*(\d+)""", RegexOption.IGNORE_CASE)

  private fun discNumber(name: String): Int? =
    DiscNumber.find(name)?.groupValues?.get(1)?.toIntOrNull()
}
