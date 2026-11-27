@file:UseSerializers(RommInstantSerializer::class)

package dev.sargunv.handheld.data.romm

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** A save file on the server, as `GET /api/saves` lists it. */
@Serializable
data class RommSave(
  val id: Long,
  @SerialName("file_name") val fileName: String,
  @SerialName("file_size_bytes") val fileSizeBytes: Long,
  val emulator: String? = null,
  /** The slot saves pair on between devices, or null for an archived upload. */
  val slot: String? = null,
  @SerialName("content_hash") val contentHash: String? = null,
  @SerialName("origin_device_id") val originDeviceId: String? = null,
  @SerialName("device_syncs") val deviceSyncs: List<RommDeviceSync> = emptyList(),
  @SerialName("updated_at") val updatedAt: Instant,
)

/** A device that synced a save. */
@Serializable
data class RommDeviceSync(
  @SerialName("device_id") val deviceId: String,
  @SerialName("device_name") val deviceName: String? = null,
)

/** A request, made on the server's web page, to install a ROM on a device. */
@Serializable
internal data class RommInstallRequest(
  val id: String,
  @SerialName("device_id") val deviceId: String,
  @SerialName("rom_id") val romId: Long,
  /** The ROM's files to install, or all of them when empty. */
  @SerialName("file_ids") val fileIds: List<Long> = emptyList(),
  val status: RommInstallStatus,
  val reason: String? = null,
)

/** Where an install request stands. A device takes pending ones, then reports how they ended. */
@Serializable
internal enum class RommInstallStatus {
  @SerialName("pending") Pending,
  @SerialName("taken") Taken,
  @SerialName("done") Done,
  @SerialName("already_installed") AlreadyInstalled,
  @SerialName("failed") Failed,
  @SerialName("cancelled") Cancelled,
}

@Serializable
internal data class RommInstallUpdate(
  val status: RommInstallStatus,
  val reason: String? = null,
)

@Serializable internal data class RommDeviceCapabilities(val capabilities: Map<String, Boolean>)
