package dev.sargunv.handheld.data

/** Small synthetic RomM responses, shaped like RomM 5.4's, including fields this client ignores. */
object Fixtures {
  fun platform(id: Long, slug: String, name: String, romCount: Int) =
    """
    {
      "id": $id, "slug": "$slug", "fs_slug": "$slug", "rom_count": $romCount, "name": "$name",
      "igdb_slug": "$slug", "moby_slug": null, "hltb_slug": null, "libretro_slug": null,
      "custom_name": null, "category": "Console", "generation": 6,
      "firmware": [], "created_at": "2025-01-01T00:00:00+00:00",
      "updated_at": "2025-01-02T00:00:00+00:00", "fs_size_bytes": 1000,
      "is_unidentified": false, "is_identified": true, "missing_from_fs": false,
      "display_name": "$name", "abbreviation": "${slug.uppercase()}", "alternative_names": [],
      "firmware_count": 0
    }
    """

  fun file(id: Long, romId: Long, name: String, size: Long, category: String? = "game") =
    """
    {
      "id": $id, "rom_id": $romId, "file_name": "$name", "file_path": "roms/test/$name",
      "file_size_bytes": $size, "full_path": "roms/test/$name", "is_top_level": true,
      "created_at": "2025-01-01T00:00:00+00:00", "updated_at": "2025-01-01T00:00:00+00:00",
      "last_modified": "2025-01-01T00:00:00.123456+00:00", "crc_hash": "0badf00d",
      "md5_hash": null, "sha1_hash": null, "ra_hash": null, "chd_sha1_hash": null,
      "archive_members": null, "category": ${category?.let { "\"$it\"" } ?: "null"}
    }
    """

  fun rom(
    id: Long,
    platformId: Long = 1,
    platformSlug: String = "test",
    name: String? = "Test Game $id",
    fsName: String = "Test Game $id (USA).bin",
    updatedAt: String = "2025-01-01T00:00:00+00:00",
    alternativeNames: List<String> = emptyList(),
    files: List<String> = listOf(file(id * 10, id, fsName, 1024)),
    multi: Boolean = files.size > 1,
    hasCover: Boolean = true,
    urlCover: String = "https://images.example/cover/$id.jpg",
    createdAt: String = "2025-01-01T00:00:00+00:00",
    lastPlayed: String? = null,
  ) =
    """
    {
      "id": $id, "igdb_id": null, "platform_id": $platformId, "platform_slug": "$platformSlug",
      "platform_fs_slug": "$platformSlug", "platform_custom_name": null,
      "platform_display_name": "Test Platform", "fs_name": "$fsName",
      "fs_name_no_tags": "${fsName.substringBefore(" (")}",
      "fs_name_no_ext": "${fsName.substringBeforeLast('.')}",
      "fs_extension": "${fsName.substringAfterLast('.', "")}", "fs_path": "roms/test",
      "fs_size_bytes": ${1024L * files.size},
      "name": ${name?.let { "\"$it\"" } ?: "null"},
      "name_sort_key": ${name?.let { "\"${it.lowercase()}\"" } ?: "null"},
      "slug": null, "summary": "A synthetic game.",
      "alternative_names": [${alternativeNames.joinToString { "\"$it\"" }}],
      "youtube_video_id": null,
      "metadatum": {
        "rom_id": $id, "genres": ["Platform", "Adventure"], "franchises": [], "collections": [],
        "companies": [], "publishers": [], "developers": [], "game_modes": [], "age_ratings": [],
        "player_count": "1", "first_release_date": 1000000000000, "average_rating": null
      },
      "igdb_metadata": {"total_rating": "80", "genres": ["Platform"]},
      "path_cover_small": "${if (hasCover) "/assets/romm/resources/roms/$platformId/$id/cover/small.png?ts=2025-01-01 00:00:00" else ""}",
      "path_cover_large": "${if (hasCover) "/assets/romm/resources/roms/$platformId/$id/cover/big.png?ts=2025-01-01 00:00:00" else ""}",
      "url_cover": "$urlCover", "has_manual": false,
      "is_identified": true, "revision": null, "regions": ["USA"], "languages": [], "tags": [],
      "has_simple_single_file": ${!multi}, "has_nested_single_file": false,
      "has_multiple_files": $multi, "full_path": "roms/test/$fsName",
      "created_at": "$createdAt", "updated_at": "$updatedAt",
      "missing_from_fs": false,
      "rom_user": {
        "id": 1, "user_id": 1, "rom_id": $id,
        "last_played": ${lastPlayed?.let { "\"$it\"" } ?: "null"}, "hidden": false
      },
      "files": [${files.joinToString()}], "sibling_roms": []
    }
    """

  fun page(items: List<String>, total: Int?, limit: Int, offset: Int) =
    """
    {
      "items": [${items.joinToString()}], "total": ${total ?: "null"}, "limit": $limit,
      "offset": $offset, "char_index": {}, "rom_id_index": [], "filter_values": {}
    }
    """

  fun collection(id: Long, name: String, romIds: List<Long>, favorite: Boolean = false) =
    """
    {
      "name": "$name", "description": "", "rom_ids": [${romIds.joinToString()}],
      "path_cover_small": null, "path_cover_large": null, "path_covers_small": [],
      "path_covers_large": [], "is_public": false, "is_favorite": $favorite, "is_virtual": false,
      "is_smart": false, "created_at": "2025-01-01T00:00:00+00:00",
      "updated_at": "2025-01-01T00:00:00+00:00", "id": $id, "url_cover": null, "user_id": 1,
      "owner_username": "player", "rom_count": ${romIds.size}
    }
    """

  fun smartCollection(id: Long, name: String, romIds: List<Long>) =
    """
    {
      "name": "$name", "rom_ids": [${romIds.joinToString()}], "path_cover_small": null,
      "path_cover_large": null, "path_covers_small": [], "path_covers_large": [],
      "is_smart": true, "created_at": "2025-01-01T00:00:00+00:00",
      "updated_at": "2025-01-01T00:00:00+00:00", "id": $id,
      "filter_criteria": {"genres": ["Platform"]}, "filter_summary": "Genre is Platform",
      "user_id": 1, "owner_username": "player", "rom_count": ${romIds.size}
    }
    """

  val me =
    """
    {
      "id": 1, "username": "player", "email": null, "enabled": true, "role": "user",
      "permission_group_id": null, "oauth_scopes": ["me.read", "roms.read"],
      "avatar_path": "", "last_login": "2025-01-01T00:00:00", "last_active": null,
      "ui_settings": null, "current_device_id": null,
      "created_at": "2025-01-01T00:00:00+00:00", "updated_at": "2025-01-01T00:00:00+00:00"
    }
    """
}
