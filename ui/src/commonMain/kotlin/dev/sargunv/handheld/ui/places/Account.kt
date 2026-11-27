package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.romm.RommAccount
import dev.sargunv.handheld.data.romm.RommSession
import dev.sargunv.handheld.data.romm.RommState
import dev.sargunv.handheld.ui.signin.SignInState

/** Whether [romm] is signed in to a RomM server, and the sign-in that shows while it isn't. */
@Stable
class Account(private val romm: RommSession, private val device: DeviceDescription) {
  /** Whether the device is signed in, or null until the stored credentials have been read. */
  val signedIn: Boolean?
    @Composable get() = romm.state.collectAsState().value.signedIn

  var signIn: SignInState by mutableStateOf(newSignIn())
    private set

  /**
   * Whether Settings shows over the sign-in, which offers only the settings that need no server.
   */
  var settingsOverSignIn: Boolean by mutableStateOf(false)

  /**
   * Forgets the credentials. The sign-in starts over on the same server, and asks for permission to
   * upload games when [allowUploads].
   */
  suspend fun signOut(allowUploads: Boolean) {
    val address = romm.account?.credentials?.baseUrl.orEmpty()
    romm.signIn.signOut()
    signIn = newSignIn(address, allowUploads)
  }

  private fun newSignIn(address: String = "", allowUploads: Boolean = false) =
    SignInState(
      romm.signIn,
      device,
      onSignedIn = { settingsOverSignIn = false },
      address,
      allowUploads,
    )
}

/** Whether this is signed in, or null when it isn't known yet. */
internal val RommState.signedIn: Boolean?
  get() =
    when (this) {
      RommState.Unknown -> null
      RommState.SignedOut -> false
      is RommAccount -> true
    }
