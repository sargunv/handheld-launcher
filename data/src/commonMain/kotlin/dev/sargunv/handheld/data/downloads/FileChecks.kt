package dev.sargunv.handheld.data.downloads

import kotlinx.io.files.Path

/**
 * Whether the file at [path] matches the hashes RomM lists for it, by the first of MD5, SHA-1 and
 * CRC-32 that RomM has. RomM hashes an archive's content rather than the archive itself, so a zip
 * also matches by its content. Null when it can't tell: RomM lists no hash, or the file is an
 * archive this check can't read.
 */
internal expect fun matchesServerHashes(
  path: Path,
  md5: String?,
  sha1: String?,
  crc: String?,
): Boolean?

/** The bytes the app can write to the storage at [root]. */
internal expect fun freeBytes(root: String): Long
