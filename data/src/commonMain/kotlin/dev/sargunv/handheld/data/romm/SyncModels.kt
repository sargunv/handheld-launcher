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
  @SerialName("rom_id") val romId: Long,
  @SerialName("file_name") val fileName: String,
  @SerialName("file_size_bytes") val fileSizeBytes: Long,
  val emulator: String? = null,
  /** The slot saves pair on between devices, or null for an archived upload. */
  val slot: String? = null,
  @SerialName("content_hash") val contentHash: String? = null,
  @SerialName("download_path") val downloadPath: String,
  @SerialName("origin_device_id") val originDeviceId: String? = null,
  @SerialName("device_syncs") val deviceSyncs: List<RommDeviceSync> = emptyList(),
  @SerialName("created_at") val createdAt: Instant,
  @SerialName("updated_at") val updatedAt: Instant,
)

/** When a device last synced a save. */
@Serializable
data class RommDeviceSync(
  @SerialName("device_id") val deviceId: String,
  @SerialName("device_name") val deviceName: String? = null,
  @SerialName("last_synced_at") val lastSyncedAt: Instant,
  /** Whether the device stopped tracking the save. */
  @SerialName("is_untracked") val isUntracked: Boolean = false,
  /** Whether the device has the save's current version. */
  @SerialName("is_current") val isCurrent: Boolean = false,
)

/** A save state on the server, as `GET /api/states` lists it. */
@Serializable
data class RommState(
  val id: Long,
  @SerialName("rom_id") val romId: Long,
  @SerialName("file_name") val fileName: String,
  @SerialName("file_size_bytes") val fileSizeBytes: Long,
  val emulator: String? = null,
  val core: String? = null,
  @SerialName("download_path") val downloadPath: String,
  @SerialName("created_at") val createdAt: Instant,
  @SerialName("updated_at") val updatedAt: Instant,
)

/** A device registered with the server, as `GET /api/devices` lists it. */
@Serializable
data class RommDevice(
  val id: String,
  val name: String? = null,
  val platform: String? = null,
  val client: String? = null,
  @SerialName("client_version") val clientVersion: String? = null,
  @SerialName("client_device_identifier") val clientDeviceIdentifier: String? = null,
  /** How the device syncs, or null when the server reports a mode this client doesn't know. */
  @SerialName("sync_mode") val syncMode: RommSyncMode? = null,
  @SerialName("sync_enabled") val syncEnabled: Boolean = false,
  @SerialName("last_seen") val lastSeen: Instant? = null,
  @SerialName("created_at") val createdAt: Instant,
  @SerialName("updated_at") val updatedAt: Instant,
)

@Serializable
enum class RommSyncMode {
  /** The device syncs through `/api/sync`. */
  @SerialName("api") Api,
  @SerialName("file_transfer") FileTransfer,
  @SerialName("push_pull") PushPull,
}

/** A sync session a device ran, from `GET /api/sync/sessions`. */
@Serializable
data class RommSyncSession(
  val id: Long,
  @SerialName("device_id") val deviceId: String,
  val status: String,
  @SerialName("initiated_at") val initiatedAt: Instant,
  @SerialName("completed_at") val completedAt: Instant? = null,
  @SerialName("operations_planned") val operationsPlanned: Int,
  @SerialName("operations_completed") val operationsCompleted: Int,
  @SerialName("operations_failed") val operationsFailed: Int,
  @SerialName("error_message") val errorMessage: String? = null,
)

/** A save as the device has it, for `POST /api/sync/negotiate`. */
@Serializable
data class ClientSaveState(
  @SerialName("rom_id") val romId: Long,
  @SerialName("file_name") val fileName: String,
  /** The slot the save pairs on with the server's saves. Null marks an archived upload. */
  val slot: String? = null,
  val emulator: String? = null,
  @SerialName("content_hash") val contentHash: String? = null,
  @SerialName("updated_at") val updatedAt: Instant,
  @SerialName("file_size_bytes") val fileSizeBytes: Long,
)

/** The body of `POST /api/sync/negotiate`. */
@Serializable
data class SyncNegotiateRequest(
  val saves: List<ClientSaveState>,
  /** The device's id. A device-bound token implies it. */
  @SerialName("device_id") val deviceId: String? = null,
  /** The ROMs installed on the device, which scope the negotiation. */
  @SerialName("rom_ids") val romIds: List<Long>? = null,
  /** The emulators whose saves the device can load. */
  val emulators: List<String>? = null,
)

/** The server's plan for bringing a device's saves in sync. */
@Serializable
data class SyncNegotiateResponse(
  @SerialName("session_id") val sessionId: Long,
  val operations: List<SyncOperation>,
  @SerialName("total_upload") val totalUpload: Int,
  @SerialName("total_download") val totalDownload: Int,
  @SerialName("total_conflict") val totalConflict: Int,
  @SerialName("total_no_op") val totalNoOp: Int,
  @SerialName("total_delete") val totalDelete: Int,
)

/** One step of a [SyncNegotiateResponse]. */
@Serializable
data class SyncOperation(
  /** What to do, or null when the server asks for an action this client doesn't know. */
  val action: SyncAction? = null,
  @SerialName("rom_id") val romId: Long,
  /** The server's save, when one exists. */
  @SerialName("save_id") val saveId: Long? = null,
  @SerialName("file_name") val fileName: String,
  val slot: String? = null,
  val emulator: String? = null,
  /** Why the server chose the action, in words. */
  val reason: String,
  @SerialName("server_updated_at") val serverUpdatedAt: Instant? = null,
  @SerialName("server_content_hash") val serverContentHash: String? = null,
)

@Serializable
enum class SyncAction {
  @SerialName("upload") Upload,
  @SerialName("download") Download,
  @SerialName("conflict") Conflict,
  @SerialName("no_op") NoOp,
  @SerialName("delete") Delete,
}
