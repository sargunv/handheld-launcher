package dev.sargunv.handheld.ui.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import dev.sargunv.handheld.data.achievements.RetroAchievementsAccount
import dev.sargunv.handheld.data.achievements.RetroAchievementsClient
import dev.sargunv.handheld.data.achievements.RetroAchievementsException
import dev.sargunv.handheld.data.achievements.RetroAchievementsStore
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.places.MenuButton
import dev.sargunv.handheld.ui.signin.TextEntry
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** How games in the built-in session take part in RetroAchievements. */
@Serializable
data class AchievementSettings(
  /**
   * Whether unlocks count as hardcore. In hardcore, games with achievements can't load states or
   * fast forward, as RetroAchievements requires.
   */
  val hardcore: Boolean = false,
)

/**
 * The device's RetroAchievements account: signing in with a username and password, which go only to
 * RetroAchievements, keeping the token it returns in [store], and signing out.
 *
 * @param client makes a client for each sign-in, which closes it after.
 */
class RetroAchievementsSignIn(
  private val store: RetroAchievementsStore,
  private val client: () -> RetroAchievementsClient,
) {
  /** The account the device is signed in to, or null. */
  suspend fun account(): RetroAchievementsAccount? = store.load()

  /**
   * Signs in and keeps the account.
   *
   * @throws RetroAchievementsException when it fails, with why as its message.
   */
  suspend fun signIn(username: String, password: String): RetroAchievementsAccount {
    val account = client().use { it.signIn(username, password) }
    store.save(account)
    return account
  }

  suspend fun signOut() = store.clear()
}

/** Settings' view of the RetroAchievements account. */
@Stable
class RetroAchievementsState(private val signIn: RetroAchievementsSignIn) {
  /** The signed-in username, or null when signed out or not yet read. */
  var username by mutableStateOf<String?>(null)
    private set

  /** Whether a sign-in is under way. */
  var busy by mutableStateOf(false)
    private set

  /** Why the last sign-in failed, in plain words. */
  var error by mutableStateOf<String?>(null)
    private set

  internal val usernameField = TextFieldState()
  internal val passwordField = TextFieldState()

  /** Reads the stored account. */
  suspend fun load() {
    username = signIn.account()?.username
  }

  /** Signs in with what's in the fields, and forgets the password whatever happens. */
  suspend fun submit() {
    if (busy) return
    val user = usernameField.text.toString().trim()
    val password = passwordField.text.toString()
    if (user.isEmpty() || password.isEmpty()) {
      error = "Enter your RetroAchievements username and password."
      return
    }
    busy = true
    error = null
    try {
      username = signIn.signIn(user, password).username
      usernameField.clearText()
    } catch (e: RetroAchievementsException) {
      error = e.message
    } finally {
      passwordField.clearText()
      busy = false
    }
  }

  suspend fun signOut() {
    signIn.signOut()
    username = null
    error = null
  }
}

/**
 * Settings → RetroAchievements: sign in when signed out; when signed in, who is, hardcore mode, and
 * signing out.
 */
@Composable
internal fun RetroAchievementsPanel(
  state: RetroAchievementsState,
  settings: AchievementSettings,
  onSettingsChange: (AchievementSettings) -> Unit,
) {
  val scope = rememberCoroutineScope()
  Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(
      "RetroAchievements",
      color = Placeholder.text,
      fontSize = 22.sp,
      modifier = Modifier.semantics { heading() },
    )
    val username = state.username
    if (username != null) {
      Text(
        "Signed in as $username",
        color = Placeholder.text,
        modifier = Modifier.testTag("ra:account"),
      )
      MenuButton("ra:hardcore", "Hardcore: ${if (settings.hardcore) "On" else "Off"}") {
        onSettingsChange(settings.copy(hardcore = !settings.hardcore))
      }
      Text(
        "In hardcore, games with achievements can't load states or fast forward.",
        color = Placeholder.mutedText,
      )
      MenuButton("ra:sign-out", "Sign out") { scope.launch { state.signOut() } }
    } else {
      Text(
        "Sign in to earn achievements in games that play in Built-in. Your password " +
          "goes only to RetroAchievements, and the device keeps a token instead of it.",
        color = Placeholder.mutedText,
      )
      TextEntry(
        key = "ra:username",
        field = state.usernameField,
        placeholder = "Username",
        label = "Username",
        keyboardOptions =
          KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
          ),
        onSubmit = {},
      )
      TextEntry(
        key = "ra:password",
        field = state.passwordField,
        placeholder = "Password",
        label = "Password",
        secure = true,
        keyboardOptions =
          KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
          ),
        onSubmit = { scope.launch { state.submit() } },
      )
      MenuButton("ra:sign-in", "Sign in", enabled = !state.busy) {
        scope.launch { state.submit() }
      }
      val status =
        when {
          state.busy -> "Signing in…"
          else -> state.error
        }
      status?.let {
        Text(
          it,
          color = Placeholder.text,
          modifier = Modifier.testTag("ra:status").semantics { liveRegion = LiveRegionMode.Polite },
        )
      }
    }
  }
}
