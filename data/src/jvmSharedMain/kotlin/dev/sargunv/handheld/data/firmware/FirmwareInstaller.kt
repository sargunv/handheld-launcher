package dev.sargunv.handheld.data.firmware

import dev.sargunv.handheld.data.digest
import dev.sargunv.handheld.data.feed
import dev.sargunv.handheld.data.hexDigest
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
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 *
 * A download fails when no data arrives for [stallTimeout], so a stalled server can't hold up the
 * launch that's waiting on it. What arrived stays in [staging], and the next install resumes it.
 */
class FirmwareInstaller(
  private val client: RommClient,
  private val staging: File,
  private val stallTimeout: Duration = 30.seconds,
) {
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
    // The name stays the same from one install to the next, so a broken download resumes, and
    // changes with the file's server or version, so it resumes only onto the same bytes.
    val identity =
      listOf(
        client.baseUrl,
        file.id,
        file.fileSizeBytes,
        file.updatedAt,
        file.md5Hash,
        file.sha1Hash,
      )
    val staged =
      File(staging, "${hexDigest("SHA-256", identity.joinToString("\n").encodeToByteArray())}.part")
    download(file, staged)
    try {
      mismatchedHash(staged, file)?.let {
        throw IOException("The download doesn't match the server's $it hash")
      }
      target.parentFile.mkdirs()
      // Creating the file fails if one exists, so nothing is ever replaced.
      val output =
        try {
          Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
          return false
        }
      try {
        output.use { Files.copy(staged.toPath(), it) }
      } catch (e: IOException) {
        // The file is this copy's own, and a broken one would be kept from then on.
        target.delete()
        throw e
      }
      return true
    } finally {
      staged.delete()
    }
  }

  /**
   * Downloads [file] to [staged], resuming from what [staged] already holds, and fails when no data
   * arrives for [stallTimeout].
   */
  private suspend fun download(file: RommFirmware, staged: File) = coroutineScope {
    val lastProgress = AtomicReference(TimeSource.Monotonic.markNow())
    val watchdog = launch {
      while (true) {
        val idle = lastProgress.get().elapsedNow()
        if (idle >= stallTimeout) throw IOException("No data arrived for $stallTimeout")
        delay(stallTimeout - idle)
      }
    }
    client.downloadFirmware(file.id, file.fileName, Path(staged.path)) {
      lastProgress.set(TimeSource.Monotonic.markNow())
    }
    watchdog.cancel()
  }

  /** The name of the first hash RomM lists for [file] that [staged] doesn't match, if any. */
  private fun mismatchedHash(staged: File, file: RommFirmware): String? {
    val expected =
      listOfNotNull(file.md5Hash?.let { "MD5" to it }, file.sha1Hash?.let { "SHA-1" to it })
    if (expected.isEmpty()) return null
    val digests = expected.map { digest(it.first) }
    staged.inputStream().use { it.feed(digests) }
    return expected
      .zip(digests)
      .firstOrNull { (hash, digest) ->
        !digest.digest().toHexString().equals(hash.second, ignoreCase = true)
      }
      ?.first
      ?.first
  }
}
