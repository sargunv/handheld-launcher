package dev.sargunv.handheld.data.auth

import dev.sargunv.handheld.data.romm.DefaultDeviceScopes
import dev.sargunv.handheld.data.romm.DeviceAuthRequest
import dev.sargunv.handheld.data.romm.DeviceCode
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.UPLOAD_SCOPE
import dev.sargunv.handheld.data.romm.UploadScopes
import dev.sargunv.handheld.data.romm.platformHttpEngine
import io.ktor.client.engine.HttpClientEngine

/** This device, as it introduces itself to a server it pairs with. */
data class DeviceDescription(
  /** An id that stays the same across sign-ins on this install, so the server reuses its device. */
  val identifier: String,
  /** The name the server shows for the device, such as `AYN Thor`. */
  val name: String,
  /** The operating system, such as `android`. */
  val platform: String?,
  val appVersion: String?,
)

/** Whether this device's sign-in lets it upload games to its server. */
enum class UploadAccess {
  /** The token may upload games. */
  Allowed,

  /** The token may not, but the account may: signing in again with uploads allowed lets it. */
  NotRequested,

  /** The account may not upload games, so only the server's admin can allow it. */
  AccountMayNot,

  /** The server didn't say what the token may do, as for a pasted API token. */
  Unknown,
}

/**
 * Signs this device in to a RomM server and keeps its credentials in [store].
 *
 * @param engine makes the HTTP engine for each request's client.
 */
class RommSignIn(
  private val store: CredentialStore,
  private val engine: () -> HttpClientEngine = ::platformHttpEngine,
) {
  /**
   * Signs in by pairing: the server issues a code, [onCode] shows it, and a user approves it on the
   * server's web page, signed in there however the server allows. This works on a server that only
   * signs in through a single sign-on provider.
   *
   * @param allowUploads also asks for [UploadScopes], so the device can upload games to the server.
   * @throws RommException.DeviceAuthDenied when the user denies the code.
   * @throws RommException.DeviceAuthExpired when the code expires before anyone approves it.
   */
  suspend fun withDeviceCode(
    address: String,
    device: DeviceDescription,
    allowUploads: Boolean = false,
    onCode: suspend (DeviceCode) -> Unit,
  ): RommCredentials {
    val baseUrl = encryptedIfOffered(address)
    val token =
      RommClient(baseUrl, engine = engine()).use { client ->
        val code =
          client.startDeviceAuth(
            DeviceAuthRequest(
              clientDeviceIdentifier = device.identifier,
              name = device.name,
              client = CLIENT_NAME,
              platform = device.platform,
              clientVersion = device.appVersion,
              requestedScopes =
                if (allowUploads) DefaultDeviceScopes + UploadScopes else DefaultDeviceScopes,
            ),
          )
        onCode(code)
        client.awaitDeviceAuth(code)
      }
    // The server's web page offers to send games only to a device that says it takes them. Without
    // it, the device still signs in.
    try {
      RommClient(baseUrl, token.accessToken, engine()).use {
        it.updateDeviceCapabilities(token.deviceId, mapOf("remote_install" to true))
      }
    } catch (e: RommException) {
      // The token may lack the scope; sending games from the web page then stays off.
    }
    val credentials =
      RommCredentials(
        baseUrl = baseUrl,
        token = token.accessToken,
        deviceId = token.deviceId,
        scopes = token.scopes,
        expiresAt = token.expiresAt,
      )
    store.save(credentials)
    return credentials
  }

  /**
   * Signs in with a client API token the user created on the server's web page. The token is
   * checked against the server before it's stored.
   *
   * @throws RommException.Unauthorized when the server doesn't accept the token.
   */
  suspend fun withToken(address: String, token: String): RommCredentials {
    val baseUrl = encryptedIfOffered(address)
    RommClient(baseUrl, token.trim(), engine()).use { it.me() }
    val credentials = RommCredentials(baseUrl = baseUrl, token = token.trim())
    store.save(credentials)
    return credentials
  }

  suspend fun signOut() = store.clear()

  /**
   * [address], or its `https://` address when it's a plain `http://` one whose server redirects
   * there, so no credentials go out unencrypted to a server that offers encryption.
   */
  private suspend fun encryptedIfOffered(address: String): String =
    RommClient(address, engine = engine()).use { it.httpsAddress() } ?: address

  /**
   * Whether the signed-in token may upload games to the server, or null when the device isn't
   * signed in. A device sign-in stores the scopes the server granted. When those lack uploading, or
   * a pasted token's scopes are unknown, the account's own scopes, which the server reports, tell
   * whether signing in again would help.
   */
  suspend fun uploadAccess(): UploadAccess? {
    val credentials = store.load() ?: return null
    if (UPLOAD_SCOPE in credentials.scopes) return UploadAccess.Allowed
    val accountScopes =
      try {
        RommClient(credentials.baseUrl, credentials.token, engine()).use { it.me().oauthScopes }
      } catch (_: RommException) {
        // Out of reach, or the token may not read the account: what it may do stays as it was.
        emptyList()
      }
    return when {
      accountScopes.isNotEmpty() && UPLOAD_SCOPE !in accountScopes -> UploadAccess.AccountMayNot
      credentials.scopes.isEmpty() -> UploadAccess.Unknown
      else -> UploadAccess.NotRequested
    }
  }

  /** A client for the signed-in server, or null when the device isn't signed in. */
  suspend fun client(): RommClient? =
    store.load()?.let { RommClient(it.baseUrl, it.token, engine()) }

  private companion object {
    const val CLIENT_NAME = "handheld"
  }
}
