package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.RommSignIn
import dev.sargunv.handheld.ui.signin.SignInState

/** Whether the device is signed in to a RomM server, and the sign-in that shows while it isn't. */
@Stable
class Account(
  private val credentials: CredentialStore,
  private val romm: RommSignIn,
  private val device: DeviceDescription,
) {
  /** Null until the stored credentials have been read. */
  var signedIn: Boolean? by mutableStateOf(null)
    private set

  var signIn: SignInState by mutableStateOf(newSignIn())
    private set

  /**
   * Whether Settings shows over the sign-in, which offers only the settings that need no server.
   */
  var settingsOverSignIn: Boolean by mutableStateOf(false)

  suspend fun load() {
    signedIn = credentials.load() != null
  }

  /**
   * Forgets the credentials. The sign-in starts over on the same server, and asks for permission to
   * upload games when [allowUploads].
   */
  suspend fun signOut(allowUploads: Boolean) {
    val address = credentials.load()?.baseUrl.orEmpty()
    romm.signOut()
    signIn = newSignIn(address, allowUploads)
    signedIn = false
  }

  private fun newSignIn(address: String = "", allowUploads: Boolean = false) =
    SignInState(
      romm,
      device,
      onSignedIn = {
        signedIn = true
        settingsOverSignIn = false
      },
      address,
      allowUploads,
    )
}
