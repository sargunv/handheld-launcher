package dev.sargunv.handheld.ui.game

import androidx.compose.runtime.Immutable
import dev.sargunv.handheld.data.romm.RommSave
import kotlin.time.Instant

/**
 * A save on the server, as a game's save history lists it.
 *
 * @param device the device that made it, or null when the server doesn't say.
 * @param emulator the emulator it belongs to, or null when the server doesn't say.
 */
@Immutable
data class ServerSave(
  val device: String?,
  val emulator: String?,
  val changedAt: Instant,
  val sizeBytes: Long,
)

/** The name that stands for this device in a save history. */
internal const val THIS_DEVICE = "This device"

/**
 * The server's [saves] of one game, newest first, naming each one's device: [THIS_DEVICE] when it
 * came from [thisDevice] (the server's id for this device), else the name the server gives it.
 */
internal fun serverSaveHistory(saves: List<RommSave>, thisDevice: String?): List<ServerSave> =
  saves.sortedWith(compareByDescending<RommSave> { it.updatedAt }.thenByDescending { it.id }).map {
    save ->
    val origin = save.originDeviceId
    val device =
      when {
        origin == null -> null
        origin == thisDevice -> THIS_DEVICE
        else -> save.deviceSyncs.find { it.deviceId == origin }?.deviceName
      }
    ServerSave(device, save.emulator, save.updatedAt, save.fileSizeBytes)
  }
