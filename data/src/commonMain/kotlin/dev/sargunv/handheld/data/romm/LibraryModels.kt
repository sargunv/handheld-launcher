@file:UseSerializers(RommInstantSerializer::class)

package dev.sargunv.handheld.data.romm

import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** A platform as `GET /api/platforms` lists it. */
@Serializable
internal data class RommPlatform(
  val id: Long,
  val slug: PlatformSlug,
  @SerialName("fs_slug") val fsSlug: String,
  /** The custom name when one is set, else the name. */
  @SerialName("display_name") val displayName: String,
  @SerialName("rom_count") val romCount: Int,
)

/** One page of a paginated list. */
@Serializable
internal data class RommPage<T>(
  val items: List<T>,
  /** The size of the whole result, or null when the request didn't ask for it. */
  val total: Int? = null,
)

/** A ROM as `GET /api/roms` and `GET /api/roms/{id}` return it. */
@Serializable
internal data class RommRom(
  val id: Long,
  @SerialName("platform_id") val platformId: Long,
  @SerialName("platform_slug") val platformSlug: PlatformSlug,
  @SerialName("platform_fs_slug") val platformFsSlug: String,
  /** The matched title, or null when no metadata source matched the ROM. */
  val name: String? = null,
  @SerialName("name_sort_key") val nameSortKey: String? = null,
  /** The name of the ROM's file, or of its folder when it has several files. */
  @SerialName("fs_name") val fsName: String,
  @SerialName("fs_name_no_tags") val fsNameNoTags: String,
  @SerialName("fs_size_bytes") val fsSizeBytes: Long,
  val summary: String? = null,
  val metadatum: RommRomMetadata? = null,
  /** Server-relative paths of the cover art RomM keeps. */
  @SerialName("path_cover_small") val pathCoverSmall: String? = null,
  @SerialName("path_cover_large") val pathCoverLarge: String? = null,
  /** The metadata source's cover URL, which RomM may not have downloaded. */
  @SerialName("url_cover") val urlCover: String? = null,
  /** The ROM's files. `GET /api/roms` includes them only when asked to. */
  val files: List<RommRomFile> = emptyList(),
  /** What the signed-in user keeps about the ROM, or null when they've kept nothing. */
  @SerialName("rom_user") val romUser: RommRomUser? = null,
  /** RetroAchievements' id for the game, when RomM matched it there. */
  @SerialName("ra_id") val raId: Long? = null,
  /** What RomM keeps from RetroAchievements about the game. */
  @SerialName("merged_ra_metadata") val raMetadata: RommRaMetadata? = null,
  @SerialName("created_at") val createdAt: Instant,
  @SerialName("updated_at") val updatedAt: Instant,
)

/** What RomM keeps from RetroAchievements about a game. */
@Serializable
internal data class RommRaMetadata(val achievements: List<RommRaAchievement> = emptyList())

/** One of a game's achievements, as RomM keeps it. */
@Serializable internal data class RommRaAchievement(@SerialName("ra_id") val raId: Long? = null)

/** What one user keeps about a ROM. */
@Serializable
internal data class RommRomUser(
  /** When the user last played the ROM, or null when they haven't. */
  @SerialName("last_played") val lastPlayed: Instant? = null,
)

/** The metadata RomM merges from its sources for a ROM. */
@Serializable
internal data class RommRomMetadata(
  val genres: List<String> = emptyList(),
  /** The first release date, in milliseconds since the epoch. */
  @SerialName("first_release_date") val firstReleaseDate: Long? = null,
)

/** One file of a ROM. */
@Serializable
internal data class RommRomFile(
  val id: Long,
  @SerialName("file_name") val fileName: String,
  @SerialName("file_path") val filePath: String,
  @SerialName("file_size_bytes") val fileSizeBytes: Long,
  @SerialName("is_top_level") val isTopLevel: Boolean = true,
  @SerialName("crc_hash") val crcHash: String? = null,
  @SerialName("md5_hash") val md5Hash: String? = null,
  @SerialName("sha1_hash") val sha1Hash: String? = null,
  /** The SHA-1 in a CHD's header. RomM 5.4 leaves it null and puts that SHA-1 in [sha1Hash]. */
  @SerialName("chd_sha1_hash") val chdSha1Hash: String? = null,
)

/** A collection whose ROMs a user picked, as `GET /api/collections` lists it. */
@Serializable
internal data class RommCollection(
  val id: Long,
  val name: String,
  val description: String = "",
  @SerialName("rom_ids") val romIds: List<Long> = emptyList(),
  @SerialName("rom_count") val romCount: Int,
  @SerialName("is_favorite") val isFavorite: Boolean = false,
)

/** A collection of the ROMs that match a saved filter, as `GET /api/collections/smart` lists it. */
@Serializable
internal data class RommSmartCollection(
  val id: Long,
  val name: String,
  val description: String = "",
  @SerialName("rom_ids") val romIds: List<Long> = emptyList(),
  @SerialName("rom_count") val romCount: Int,
)

/** A BIOS or firmware file, as `GET /api/firmware` lists it. */
@Serializable
internal data class RommFirmware(
  val id: Long,
  @SerialName("file_name") val fileName: String,
  @SerialName("file_size_bytes") val fileSizeBytes: Long,
  @SerialName("md5_hash") val md5Hash: String? = null,
  @SerialName("sha1_hash") val sha1Hash: String? = null,
  @SerialName("updated_at") val updatedAt: Instant,
)
