package dev.sargunv.handheld.data.cache

import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.downloads.Download
import dev.sargunv.handheld.data.downloads.DownloadManager
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommInstallStatus
import io.ktor.client.engine.HttpClientEngine

/**
 * Takes the games sent to the device [deviceId] from RomM's web page and queues them in
 * [downloads]. A game that's on the device already is reported to the server as installed, and the
 * queue reports how the others end with [reportInstall].
 *
 * @throws RommException when the server can't be reached.
 */
suspend fun takeInstallRequests(client: RommClient, deviceId: String, downloads: DownloadManager) {
  val listed = client.installRequests(deviceId)
  val taken =
    if (listed.any { it.status == RommInstallStatus.Pending }) {
      client.claimInstallRequests(deviceId)
    } else listed
  for (request in taken.filter { it.status == RommInstallStatus.Taken }) {
    if (downloads.queue.value.any { it.installRequestId == request.id }) continue
    val rom = client.rom(request.romId)
    if (!downloads.enqueue(rom.toDomain(client), rom.platformFsSlug, request)) {
      client.updateInstallRequest(deviceId, request.id, RommInstallStatus.AlreadyInstalled)
    }
  }
}

/**
 * Tells the server that sent [download] from its web page, if one did, that its install ended with
 * [status], for [reason].
 */
internal suspend fun reportInstall(
  credentials: CredentialStore,
  engine: () -> HttpClientEngine,
  download: Download,
  status: RommInstallStatus,
  reason: String? = null,
) {
  val requestId = download.installRequestId ?: return
  val signedIn = credentials.load() ?: return
  val deviceId = signedIn.deviceId ?: return
  try {
    RommClient(signedIn.baseUrl, signedIn.token, engine()).use {
      // A device reports how a request ended, but cancels one through its own endpoint.
      if (status == RommInstallStatus.Cancelled) it.cancelInstallRequest(deviceId, requestId)
      else it.updateInstallRequest(deviceId, requestId, status, reason)
    }
  } catch (e: RommException) {
    // The request stays taken, and the next poll finds the game on the device or queues it.
  }
}
