package dev.sargunv.handheld.domain

import kotlin.time.Instant

/** A platform on the RomM server, such as a console. Its shelf holds its ROMs. */
data class Platform(
  /** RomM's id for the platform. */
  val id: Long,
  /** RomM's canonical slug, such as `ps2`, which launch rules key on. */
  val slug: String,
  /** The name of the platform's folder in the server's library. */
  val fsSlug: String,
  /** The name to show, which honors a custom name set on the server. */
  val name: String,
  val romCount: Int,
)

/**
 * What names a game across the app, such as in launch rules and on the Game screen: a ROM on the
 * server by its RomM id, or a local game by its file's path.
 */
sealed interface GameKey {
  data class Romm(val romId: Long) : GameKey

  /**
   * A [LocalGame], by its file's path. It names the file, not its content, so a choice made for it
   * stays with the file while it keeps its name and place. Once the file is a ROM's, what's kept
   * for it goes to the ROM ([PerGameState]).
   */
  data class Local(val path: String) : GameKey
}

/**
 * A game the library lists: a [Rom] on the server, or a [LocalGame], a file on the device that
 * matches none of the server's games.
 */
sealed interface LibraryGame {
  val key: GameKey
  val platformId: Long
  /** The platform's RomM slug, which launch rules key on. */
  val platformSlug: String
  val title: String
  /** What the library sorts the game by. */
  val sortKey: String
  val sizeBytes: Long
  /**
   * When the game came to the library: added to the server, or for a local game, its file's last
   * change.
   */
  val addedAt: Instant
}

/**
 * A file in a platform's folder on the device that matches none of the server's games, by name or
 * by content, such as a game the player copied in that the server doesn't have. It plays like a
 * game from the server, but has no cover, and its saves don't sync, since RomM files saves by ROM.
 * Once a scan matches the file to a ROM, such as after the server gains the game, it's that ROM.
 */
data class LocalGame(
  /** The file's path, which identifies the game. */
  val path: String,
  /** The platform whose folder holds the file. */
  override val platformId: Long,
  override val platformSlug: String,
  /** The size of its file, or of all the files of a disc set. */
  override val sizeBytes: Long,
  /** When the file last changed. */
  override val addedAt: Instant,
  /** The other files of a disc set, which its sheet lists, by path. */
  val otherFiles: List<String> = emptyList(),
) : LibraryGame {
  override val key: GameKey
    get() = GameKey.Local(path)

  val fileName: String
    get() = path.substringAfterLast('/')

  /** The file's name without its extension. */
  override val title: String
    get() = fileName.substringBeforeLast('.').ifEmpty { fileName }

  override val sortKey: String
    get() = title.lowercase()
}

/** A ROM in the library: one game on one platform, made of one or more files. */
data class Rom(
  /** RomM's id for the ROM. */
  val id: Long,
  override val platformId: Long,
  override val platformSlug: String,
  /** The matched title, or the file name without tags when the server matched nothing. */
  override val title: String,
  /** What the library sorts the ROM by: RomM's natural-sort key for the title. */
  override val sortKey: String,
  /** The ROM's other titles, such as regional ones. */
  val alternativeNames: List<String>,
  /** The name of the ROM's file or folder on the server. */
  val fileName: String,
  /** The total size of the ROM's files. */
  override val sizeBytes: Long,
  val summary: String?,
  val genres: List<String>,
  val regions: List<String>,
  val releaseDate: Instant?,
  /** Absolute URLs of the server's cover art, or null when it has none. */
  val coverSmallUrl: String?,
  val coverLargeUrl: String?,
  /** Whether the ROM is a folder of several files, such as a multi-disc game. */
  val hasMultipleFiles: Boolean,
  val files: List<RomFile>,
  /** When the server last changed the ROM. */
  val updatedAt: Instant,
  /** When the ROM was added to the server. */
  val createdAt: Instant,
) : LibraryGame {
  override val key: GameKey
    get() = GameKey.Romm(id)

  override val addedAt: Instant
    get() = createdAt
}

/** One file of a [Rom]. */
data class RomFile(
  /** RomM's id for the file. */
  val id: Long,
  val fileName: String,
  val sizeBytes: Long,
  /** What the file holds, or null when the server didn't say. */
  val category: RomFileCategory?,
  /**
   * RomM's hashes of the file, in lowercase hex, or null when it has none. For an archive such as a
   * zip, they're of what the archive holds, not of the archive itself.
   */
  val crcHash: String? = null,
  val md5Hash: String? = null,
  val sha1Hash: String? = null,
  /** The SHA-1 a CHD's header records for the disc it holds. */
  val chdSha1Hash: String? = null,
)

/** What a [RomFile] holds, as RomM categorizes it. */
enum class RomFileCategory {
  Game,
  Dlc,
  Hack,
  Manual,
  Walkthrough,
  Patch,
  Update,
  Mod,
  Demo,
  Translation,
  Prototype,
  Cheat,
  Soundtrack,
  Screenshot,
}

/** A collection of ROMs on the server. Its shelf holds its member ROMs. */
data class Collection(
  val kind: CollectionKind,
  /** RomM's id for the collection, unique within its [kind]. */
  val id: Long,
  val name: String,
  val description: String,
  val romCount: Int,
  /** Whether this is the user's favorites collection. */
  val isFavorite: Boolean,
)

enum class CollectionKind {
  /** A collection whose ROMs the user picked. */
  Regular,
  /** A collection whose ROMs match a saved filter. */
  Smart,
}
