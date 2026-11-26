@file:UseSerializers(RommInstantSerializer::class)

package dev.sargunv.handheld.data.romm

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** A platform as `GET /api/platforms` lists it. */
@Serializable
data class RommPlatform(
  val id: Long,
  val slug: String,
  @SerialName("fs_slug") val fsSlug: String,
  val name: String,
  @SerialName("custom_name") val customName: String? = null,
  /** The custom name when one is set, else the name. */
  @SerialName("display_name") val displayName: String,
  @SerialName("rom_count") val romCount: Int,
  @SerialName("firmware_count") val firmwareCount: Int = 0,
  @SerialName("created_at") val createdAt: Instant,
  @SerialName("updated_at") val updatedAt: Instant,
)

/** One page of a paginated list. */
@Serializable
data class RommPage<T>(
  val items: List<T>,
  /** The size of the whole result, or null when the request didn't ask for it. */
  val total: Int? = null,
  val limit: Int,
  val offset: Int,
)

/** A ROM as `GET /api/roms` and `GET /api/roms/{id}` return it. */
@Serializable
data class RommRom(
  val id: Long,
  @SerialName("platform_id") val platformId: Long,
  @SerialName("platform_slug") val platformSlug: String,
  @SerialName("platform_fs_slug") val platformFsSlug: String,
  @SerialName("platform_display_name") val platformDisplayName: String,
  /** The matched title, or null when no metadata source matched the ROM. */
  val name: String? = null,
  @SerialName("name_sort_key") val nameSortKey: String? = null,
  val slug: String? = null,
  /** The name of the ROM's file, or of its folder when it has several files. */
  @SerialName("fs_name") val fsName: String,
  @SerialName("fs_name_no_tags") val fsNameNoTags: String,
  @SerialName("fs_name_no_ext") val fsNameNoExt: String,
  @SerialName("fs_extension") val fsExtension: String,
  @SerialName("fs_size_bytes") val fsSizeBytes: Long,
  val summary: String? = null,
  @SerialName("alternative_names") val alternativeNames: List<String> = emptyList(),
  val metadatum: RommRomMetadata? = null,
  /** Server-relative paths of the cover art RomM keeps. */
  @SerialName("path_cover_small") val pathCoverSmall: String? = null,
  @SerialName("path_cover_large") val pathCoverLarge: String? = null,
  /** The metadata source's cover URL, which RomM may not have downloaded. */
  @SerialName("url_cover") val urlCover: String? = null,
  val regions: List<String> = emptyList(),
  val languages: List<String> = emptyList(),
  val tags: List<String> = emptyList(),
  val revision: String? = null,
  @SerialName("has_simple_single_file") val hasSimpleSingleFile: Boolean = false,
  @SerialName("has_nested_single_file") val hasNestedSingleFile: Boolean = false,
  @SerialName("has_multiple_files") val hasMultipleFiles: Boolean = false,
  /** The ROM's files. `GET /api/roms` includes them only when asked to. */
  val files: List<RommRomFile> = emptyList(),
  @SerialName("missing_from_fs") val missingFromFs: Boolean = false,
  /** What the signed-in user keeps about the ROM, or null when they've kept nothing. */
  @SerialName("rom_user") val romUser: RommRomUser? = null,
  @SerialName("created_at") val createdAt: Instant,
  @SerialName("updated_at") val updatedAt: Instant,
)

/** What one user keeps about a ROM. */
@Serializable
data class RommRomUser(
  /** When the user last played the ROM, or null when they haven't. */
  @SerialName("last_played") val lastPlayed: Instant? = null,
)

/** The metadata RomM merges from its sources for a ROM. */
@Serializable
data class RommRomMetadata(
  val genres: List<String> = emptyList(),
  val franchises: List<String> = emptyList(),
  val companies: List<String> = emptyList(),
  @SerialName("player_count") val playerCount: String? = null,
  /** The first release date, in milliseconds since the epoch. */
  @SerialName("first_release_date") val firstReleaseDate: Long? = null,
  @SerialName("average_rating") val averageRating: Double? = null,
)

/** One file of a ROM. */
@Serializable
data class RommRomFile(
  val id: Long,
  @SerialName("rom_id") val romId: Long,
  @SerialName("file_name") val fileName: String,
  @SerialName("file_path") val filePath: String,
  @SerialName("file_size_bytes") val fileSizeBytes: Long,
  @SerialName("is_top_level") val isTopLevel: Boolean = true,
  /** RomM's category for the file, such as `game` or `dlc`. */
  val category: String? = null,
  @SerialName("crc_hash") val crcHash: String? = null,
  @SerialName("md5_hash") val md5Hash: String? = null,
  @SerialName("sha1_hash") val sha1Hash: String? = null,
  @SerialName("updated_at") val updatedAt: Instant,
)

/** A collection whose ROMs a user picked, as `GET /api/collections` lists it. */
@Serializable
data class RommCollection(
  val id: Long,
  val name: String,
  val description: String = "",
  @SerialName("rom_ids") val romIds: List<Long> = emptyList(),
  @SerialName("rom_count") val romCount: Int,
  @SerialName("path_cover_small") val pathCoverSmall: String? = null,
  @SerialName("path_cover_large") val pathCoverLarge: String? = null,
  @SerialName("is_public") val isPublic: Boolean = false,
  @SerialName("is_favorite") val isFavorite: Boolean = false,
  @SerialName("user_id") val userId: Long,
  @SerialName("owner_username") val ownerUsername: String,
  @SerialName("created_at") val createdAt: Instant,
  @SerialName("updated_at") val updatedAt: Instant,
)

/** A collection of the ROMs that match a saved filter, as `GET /api/collections/smart` lists it. */
@Serializable
data class RommSmartCollection(
  val id: Long,
  val name: String,
  val description: String = "",
  @SerialName("rom_ids") val romIds: List<Long> = emptyList(),
  @SerialName("rom_count") val romCount: Int,
  @SerialName("path_cover_small") val pathCoverSmall: String? = null,
  @SerialName("path_cover_large") val pathCoverLarge: String? = null,
  @SerialName("is_public") val isPublic: Boolean = false,
  /** A readable description of the filter. */
  @SerialName("filter_summary") val filterSummary: String = "",
  @SerialName("user_id") val userId: Long,
  @SerialName("owner_username") val ownerUsername: String,
  @SerialName("created_at") val createdAt: Instant,
  @SerialName("updated_at") val updatedAt: Instant,
)

/** A BIOS or firmware file, as `GET /api/firmware` lists it. */
@Serializable
data class RommFirmware(
  val id: Long,
  @SerialName("platform_id") val platformId: Long,
  @SerialName("file_name") val fileName: String,
  @SerialName("file_size_bytes") val fileSizeBytes: Long,
  /** Whether the file's hash matches a known good dump. */
  @SerialName("is_verified") val isVerified: Boolean = false,
  @SerialName("crc_hash") val crcHash: String? = null,
  @SerialName("md5_hash") val md5Hash: String? = null,
  @SerialName("sha1_hash") val sha1Hash: String? = null,
  @SerialName("missing_from_fs") val missingFromFs: Boolean = false,
  @SerialName("updated_at") val updatedAt: Instant,
)
