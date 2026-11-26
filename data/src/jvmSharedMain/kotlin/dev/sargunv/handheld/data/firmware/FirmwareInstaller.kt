package dev.sargunv.handheld.data.firmware

import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommFirmware
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.saves.BiosLocation
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/** What [FirmwareInstaller.install] did. */
sealed interface FirmwareInstall {
  /** The server has no firmware for the platform. */
  data object NoneOnServer : FirmwareInstall

  /**
   * The launcher can't write to any of [folders], such as when they're in another app's
   * `Android/data` folder.
   */
  data class Unreachable(val folders: List<String>) : FirmwareInstall

  /**
   * The firmware went to [folder]. [placed] are the files written, [kept] those that were already
   * there and were left as they were, and [failed] maps each file that couldn't be placed to the
   * reason.
   */
  data class Done(
    val folder: String,
    val placed: List<String>,
    val kept: List<String>,
    val failed: Map<String, String>,
  ) : FirmwareInstall
}

/**
 * Puts a platform's firmware from a RomM server where an emulator reads it. Files download to
 * [staging] first and are checked against the hashes RomM lists. A file already at the target is
 * never replaced, even when it differs from the server's: the emulator may depend on it.
 */
class FirmwareInstaller(private val client: RommClient, private val staging: File) {
  /**
   * Places the firmware the server has for [platform] in the first of [folders] the launcher can
   * write to, named as [location] says. A folder that doesn't exist yet is created.
   *
   * @throws RommException when the server can't list its firmware.
   */
  suspend fun install(
    platform: PlatformSlug,
    location: BiosLocation,
    folders: List<String>,
  ): FirmwareInstall {
    val firmware = firmwareFor(platform)
    if (firmware.isEmpty()) return FirmwareInstall.NoneOnServer
    return withContext(Dispatchers.IO) {
      val folder =
        folders.map(::File).firstOrNull { (it.isDirectory || it.mkdirs()) && it.canWrite() }
          ?: return@withContext FirmwareInstall.Unreachable(folders)
      val placed = mutableListOf<String>()
      val kept = mutableListOf<String>()
      val failed = mutableMapOf<String, String>()
      for (file in firmware) {
        val name = location.targetName(file.fileName, file.md5Hash)
        val target = File(folder, name)
        // A name from the server mustn't climb out of the folder.
        if (!target.canonicalPath.startsWith(folder.canonicalPath + File.separator)) {
          failed[name] = "The name leaves the firmware folder"
          continue
        }
        if (target.exists()) {
          kept += name
          continue
        }
        try {
          if (place(file, target)) placed += name else kept += name
        } catch (e: RommException) {
          failed[name] = e.message.orEmpty()
        } catch (e: IOException) {
          failed[name] = e.message.orEmpty()
        }
      }
      FirmwareInstall.Done(folder.path, placed, kept, failed)
    }
  }

  /** The firmware the server has for every platform it emulates as [platform]. */
  private suspend fun firmwareFor(platform: PlatformSlug): List<RommFirmware> {
    val emulatedAs = PlatformCatalog.emulatedAs(platform)
    return client
      .platforms()
      .filter { PlatformCatalog.emulatedAs(PlatformSlug(it.slug)) == emulatedAs }
      .flatMap { client.firmware(it.id) }
  }

  /** Downloads [file] and writes it to [target], unless a file appears there first. */
  private suspend fun place(file: RommFirmware, target: File): Boolean {
    staging.mkdirs()
    val staged = File(staging, file.id.toString())
    client.downloadFirmware(file.id, file.fileName, Path(staged.path))
    mismatchedHash(staged, file)?.let {
      staged.delete()
      throw IOException("The download doesn't match the server's $it hash")
    }
    target.parentFile.mkdirs()
    // Copy beside the target and move it into place, so a broken copy never sits at the target,
    // where it would be kept from then on.
    val partial = Files.createTempFile(target.parentFile.toPath(), ".handheld-", ".part")
    try {
      Files.copy(staged.toPath(), partial, StandardCopyOption.REPLACE_EXISTING)
      // Without REPLACE_EXISTING, the move fails rather than replace a file.
      Files.move(partial, target.toPath())
    } catch (e: FileAlreadyExistsException) {
      return false
    } finally {
      Files.deleteIfExists(partial)
    }
    staged.delete()
    return true
  }

  /** The name of the first hash RomM lists for [file] that [staged] doesn't match, if any. */
  private fun mismatchedHash(staged: File, file: RommFirmware): String? {
    val expected =
      listOfNotNull(file.md5Hash?.let { "MD5" to it }, file.sha1Hash?.let { "SHA-1" to it })
    if (expected.isEmpty()) return null
    val digests = expected.map { MessageDigest.getInstance(it.first) }
    staged.inputStream().buffered().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        for (digest in digests) digest.update(buffer, 0, count)
      }
    }
    return expected
      .zip(digests)
      .firstOrNull { (hash, digest) ->
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        !actual.equals(hash.second, ignoreCase = true)
      }
      ?.first
      ?.first
  }
}
