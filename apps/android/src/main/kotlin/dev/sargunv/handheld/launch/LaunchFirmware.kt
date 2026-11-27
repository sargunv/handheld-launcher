package dev.sargunv.handheld.launch

import android.content.Context
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.firmware.FirmwareInstall
import dev.sargunv.handheld.data.firmware.FirmwareInstaller
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.domain.launch.LaunchTarget
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.domain.saves.BiosKnowledge
import dev.sargunv.handheld.handheldApp
import java.io.File

/**
 * Before a game launches in [target], puts the firmware its emulator needs for [platform] where the
 * emulator reads it, from the signed-in RomM server. Returns null without touching the network when
 * the game needs no firmware there, the emulator's firmware folder isn't known, or the launcher
 * isn't signed in.
 *
 * @throws RommException when the server can't list its firmware.
 */
suspend fun installFirmwareFor(
  context: Context,
  credentials: CredentialStore,
  target: LaunchTarget,
  platform: PlatformSlug,
): FirmwareInstall? {
  val emulator = target.emulator ?: return null
  val location = BiosKnowledge.locationFor(emulator.id, platform) ?: return null
  val signedIn = credentials.load() ?: return null
  val storage = context.handheldApp.storageTargets().firstOrNull()?.root ?: return null
  return RommClient(signedIn.baseUrl, signedIn.token).use { client ->
    FirmwareInstaller(client, File(context.cacheDir, "firmware"))
      .install(platform, location, location.folders(storage, target.packageName))
  }
}
