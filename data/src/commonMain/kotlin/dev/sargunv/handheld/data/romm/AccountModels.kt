@file:UseSerializers(RommInstantSerializer::class)

package dev.sargunv.handheld.data.romm

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** The server's public configuration, from `GET /api/heartbeat`, which needs no sign-in. */
@Serializable
data class RommHeartbeat(
  @SerialName("SYSTEM") val system: System,
  @SerialName("FRONTEND") val frontend: Frontend = Frontend(),
  @SerialName("OIDC") val oidc: Oidc = Oidc(),
) {
  @Serializable data class System(@SerialName("VERSION") val version: String)

  @Serializable
  data class Frontend(
    @SerialName("DISABLE_USERPASS_LOGIN") val disableUserPassLogin: Boolean = false,
  )

  @Serializable
  data class Oidc(
    @SerialName("ENABLED") val enabled: Boolean = false,
    @SerialName("PROVIDER") val provider: String = "",
  )
}

/** The signed-in user, from `GET /api/users/me`. */
@Serializable
data class RommUser(
  val id: Long,
  val username: String,
  val email: String? = null,
  /** `user` or `admin`. */
  val role: String,
  /** The scopes the user's role grants. A token may hold fewer. */
  @SerialName("oauth_scopes") val oauthScopes: List<String> = emptyList(),
  @SerialName("current_device_id") val currentDeviceId: String? = null,
  @SerialName("created_at") val createdAt: Instant,
  @SerialName("updated_at") val updatedAt: Instant,
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
