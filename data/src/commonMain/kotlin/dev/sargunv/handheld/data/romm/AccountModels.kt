@file:UseSerializers(RommInstantSerializer::class)

package dev.sargunv.handheld.data.romm

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** The signed-in user, from `GET /api/users/me`. */
@Serializable
data class RommUser(
  val id: Long,
  val username: String,
  /** The scopes the user's role grants. A token may hold fewer. */
  @SerialName("oauth_scopes") val oauthScopes: List<String> = emptyList(),
  /** The RetroAchievements account the user linked in RomM, or empty when they haven't. */
  @SerialName("ra_username") val raUsername: String? = null,
  /** The user's RetroAchievements progress, which RomM fetches from RetroAchievements. */
  @SerialName("ra_progression") val raProgression: RommRaProgression? = null,
)

/** A user's RetroAchievements progress, game by game, as RomM last fetched it. */
@Serializable data class RommRaProgression(val results: List<RommRaGameProgression> = emptyList())

/** A user's RetroAchievements progress in one game. */
@Serializable
data class RommRaGameProgression(
  /** RetroAchievements' id for the game, which a ROM's `ra_id` matches. */
  @SerialName("rom_ra_id") val romRaId: Long? = null,
  /** How many achievements the game has. */
  @SerialName("max_possible") val maxPossible: Int? = null,
  /** How many of them the user has earned, in either mode. */
  @SerialName("num_awarded") val numAwarded: Int? = null,
  @SerialName("num_awarded_hardcore") val numAwardedHardcore: Int? = null,
)

/** What a device tells the server about itself when it asks to pair. */
@Serializable
data class DeviceAuthRequest(
  /** An id that stays the same across sign-ins on this install, so the server reuses its device. */
  @SerialName("client_device_identifier") val clientDeviceIdentifier: String,
  /** The device's name, which the server shows on the approval screen. */
  val name: String,
  /** The app's name. */
  val client: String,
  /** The device's operating system, such as `android`. */
  val platform: String? = null,
  @SerialName("client_version") val clientVersion: String? = null,
  @SerialName("requested_scopes") val requestedScopes: List<String>,
)

/** A pairing in progress: the code a user approves on the server's web page. */
data class DeviceCode(
  /** The secret the device polls with. */
  val deviceCode: String,
  /** The short code the user confirms. */
  val userCode: String,
  /** The approval page, with the user code filled in, for a link or a QR code. */
  val verificationUrl: String,
  /** How long the code stays valid. */
  val expiresIn: kotlin.time.Duration,
  /** How long to wait between polls. */
  val interval: kotlin.time.Duration,
)

/** A client API token the server issued to this device. */
@Serializable
data class DeviceToken(
  @SerialName("access_token") val accessToken: String,
  /** The server's id for the device the token is bound to. */
  @SerialName("device_id") val deviceId: String,
  val scopes: List<String> = emptyList(),
  /** When the token expires, or null when it doesn't. */
  @SerialName("expires_at") val expiresAt: Instant? = null,
)

/** The scopes a device requests by default: what browsing, downloads and save sync use. */
val DefaultDeviceScopes: List<String> =
  listOf(
    "me.read",
    "platforms.read",
    "roms.read",
    "roms.user.read",
    "roms.user.write",
    "collections.read",
    "firmware.read",
    "assets.read",
    "assets.write",
    "devices.read",
    "devices.write",
  )

/**
 * The scopes uploading a game needs, which a device requests only when the player allows it:
 * `roms.write` sends the file, and `tasks.run` scans it into the library.
 */
val UploadScopes: List<String> = listOf(UPLOAD_SCOPE, "tasks.run")

/**
 * The scope a token needs to upload a game at all. Without `tasks.run`, the game waits for a scan.
 */
const val UPLOAD_SCOPE = "roms.write"
