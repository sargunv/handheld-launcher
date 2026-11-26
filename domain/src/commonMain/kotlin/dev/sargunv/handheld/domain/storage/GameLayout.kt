package dev.sargunv.handheld.domain.storage

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
   * The name of a ROM on the device, within its platform's folder: its one file's name, or for a
   * ROM with several files, the name of the folder that holds them.
   *
   * @param romName the ROM's name on the server: its file's, or its folder's.
   * @param fileNames the names of the ROM's files.
   */
  fun romName(romName: String, fileNames: List<String>): String =
    fileNames.singleOrNull() ?: romName

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
    if (PlatformCatalog.emulatedAs(platform) !in PlaylistPlatforms) return null
    if (files.any { it.extension() == "m3u" }) return null
    val descriptors = files.filter { it.extension() in DiscDescriptors }
    val discs = descriptors.ifEmpty { files.filter { it.extension() in DiscImages } }
    if (discs.size < 2) return null
    return discs.sortedWith(compareBy({ discNumber(it) ?: Int.MAX_VALUE }, { it }))
  }

  /** The platforms whose emulators switch discs through a playlist. */
  private val PlaylistPlatforms =
    setOf(
      PlatformCatalog.PSX,
      PlatformCatalog.SATURN,
      PlatformCatalog.DREAMCAST,
      PlatformCatalog.SEGA_CD,
      PlatformCatalog.TG_CD,
    )

  private val DiscDescriptors = setOf("cue", "gdi", "ccd", "mds", "toc")
  private val DiscImages = setOf("chd", "iso", "cdi", "pbp", "img", "bin")

  private fun String.extension() = substringAfterLast('.', "").lowercase()

  private fun discNumber(name: String): Int? =
    Regex("""\b(?:disc|disk|cd)\s*(\d+)""", RegexOption.IGNORE_CASE)
      .find(name)
      ?.groupValues
      ?.get(1)
      ?.toIntOrNull()
}
