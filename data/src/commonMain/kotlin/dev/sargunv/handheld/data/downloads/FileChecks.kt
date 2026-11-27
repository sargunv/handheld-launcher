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

/** When the file at [path] last changed, in milliseconds since the epoch. */
internal expect fun lastModified(path: Path): Long

/**
 * The CRC-32 of the one file in the zip at [path], from its central directory, in lowercase hex.
 * Null when it isn't a zip this can read or holds other than one file.
 */
internal expect fun oneFileZipCrc(path: Path): String?

/** The CRC-32 and SHA-1 of the whole file at [path], in lowercase hex. */
internal expect fun crcAndSha1(path: Path): Pair<String, String>
