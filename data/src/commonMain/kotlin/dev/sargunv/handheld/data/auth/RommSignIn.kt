package dev.sargunv.handheld.data.auth

import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.romm.DefaultDeviceScopes
import dev.sargunv.handheld.data.romm.DeviceAuthRequest
import dev.sargunv.handheld.data.romm.DeviceCode
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.UploadScopes
import io.ktor.client.engine.HttpClientEngine
import okhttp3.HttpUrl.Companion.toHttpUrl

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
  private val engine: () -> HttpClientEngine,
) {
  /**
   * Signs in by pairing: the server issues a code, [onCode] shows it, and a user approves it on the
   * server's web page, signed in there however the server allows. This works on a server that only
   * signs in through a single sign-on provider.
   *
   * @param allowUploads also asks for [UploadScopes], so the device can upload games to the server.
   * @throws RommException.DeviceAuthDenied when the user denies the code.
   * @throws RommException.DeviceAuthExpired when the code expires before anyone approves it.
   * @throws RommException.Unreachable when [address] isn't one the engine can send to.
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
    val user =
      RommClient(baseUrl, token.accessToken, engine()).use { client ->
        // The server's web page offers to send games only to a device that says it takes them.
        try {
          client.updateDeviceCapabilities(token.deviceId, mapOf("remote_install" to true))
        } catch (e: RommException) {
          log.w(e) { "Couldn't offer this device for games sent from the web page" }
        }
        client.me()
      }
    val credentials =
      RommCredentials(
        baseUrl = baseUrl,
        token = token.accessToken,
        userId = user.id,
        username = user.username,
        deviceId = token.deviceId,
        scopes = token.scopes,
      )
    store.save(credentials)
    return credentials
  }

  /**
   * Signs in with a client API token the user created on the server's web page. The token is
   * checked against the server before it's stored.
   *
   * @throws RommException.Unauthorized when the server doesn't accept the token.
   * @throws RommException.Unreachable when [address] isn't one the engine can send to.
   */
  suspend fun withToken(address: String, token: String): RommCredentials {
    val baseUrl = encryptedIfOffered(address)
    val user = RommClient(baseUrl, token.trim(), engine()).use { it.me() }
    val credentials =
      RommCredentials(baseUrl, token.trim(), userId = user.id, username = user.username)
    store.save(credentials)
    return credentials
  }

  suspend fun signOut() = store.clear()

  /**
   * [address], or its `https://` address when it's a plain `http://` one whose server redirects
   * there, so no credentials go out unencrypted to a server that offers encryption.
   */
  private suspend fun encryptedIfOffered(address: String): String {
    try {
      address.toHttpUrl()
    } catch (e: IllegalArgumentException) {
      throw RommException.Unreachable(e)
    }
    return RommClient(address, engine = engine()).use { it.httpsAddress() } ?: address
  }

  private companion object {
    const val CLIENT_NAME = "handheld"
  }
}

private val log = Logger.withTag("RommSignIn")
