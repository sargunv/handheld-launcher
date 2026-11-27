package dev.sargunv.handheld.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavEntry
import com.composeunstyled.Text
import dev.sargunv.handheld.data.auth.DeviceDescription
import dev.sargunv.handheld.data.auth.RommSignIn
import dev.sargunv.handheld.data.romm.DeviceCode
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.ui.Placeholder
import dev.sargunv.handheld.ui.focus.HelpHolder
import dev.sargunv.handheld.ui.focus.focusItem
import dev.sargunv.handheld.ui.focus.help
import dev.sargunv.handheld.ui.input.Action
import dev.sargunv.handheld.ui.input.Hint
import dev.sargunv.handheld.ui.input.HintBar
import dev.sargunv.handheld.ui.navigation.NavigationHost
import dev.sargunv.handheld.ui.navigation.Navigator
import io.ktor.http.URLProtocol
import io.ktor.http.parseUrl
import kotlinx.coroutines.launch
import kotlinx.io.IOException

/** The steps of signing in. */
sealed interface SignInScreen {
  /** The server's address. */
  data object Address : SignInScreen

  /** A code to approve from another device: the usual way in. */
  data class Code(val baseUrl: String) : SignInScreen

  /** A pasted client API token: the fallback. */
  data class Token(val baseUrl: String) : SignInScreen
}

/**
 * State the sign-in's two screens share.
 *
 * @param address the server's address to start with, such as the one the device signed out of.
 * @param allowUploads whether the sign-in starts out asking for permission to upload games.
 */
@Stable
class SignInState(
  internal val signIn: RommSignIn,
  internal val device: DeviceDescription,
  internal val onSignedIn: () -> Unit,
  address: String = "",
  allowUploads: Boolean = false,
) {
  val navigator = Navigator<SignInScreen>(SignInScreen.Address)
  internal val address = TextFieldState(address)

  /** Whether a sign-in with a code asks for permission to upload games to the server too. */
  internal var allowUploads: Boolean by mutableStateOf(allowUploads)
  internal val help = HelpHolder()
}

/** The sign-in's top screen. */
@Composable
fun SignInPrimary(state: SignInState) {
  NavigationHost(state.navigator, Modifier.fillMaxSize().background(Placeholder.background)) {
    screen ->
    when (screen) {
      SignInScreen.Address -> NavEntry(screen) { AddressScreen(state) }
      is SignInScreen.Code -> NavEntry(screen) { CodeScreen(state, screen.baseUrl) }
      is SignInScreen.Token -> NavEntry(screen) { TokenScreen(state, screen.baseUrl) }
    }
  }
}

/** The sign-in's bottom screen: what the focused item does. */
@Composable
fun SignInSecondary(state: SignInState) {
  Column(
    Modifier.fillMaxSize().background(Placeholder.secondaryBackground).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    state.help.shown?.let { help ->
      Text(help.label, color = Placeholder.text)
      Text(help.text, color = Placeholder.mutedText)
    }
  }
}

@Composable
private fun AddressScreen(state: SignInState) {
  var error by remember { mutableStateOf<String?>(null) }
  fun next(screen: (String) -> SignInScreen) {
    val url = serverUrl(state.address.text.toString())
    error = if (url == null) "Valid server address required" else null
    if (url != null) state.navigator.push(screen(url))
  }
  ScreenColumn(canGoBack = false) {
    Text(
      "Sign in to RomM",
      color = Placeholder.text,
      fontSize = 28.sp,
      modifier = Modifier.semantics { heading() },
    )
    TextEntry(
      state.help,
      key = "address",
      field = state.address,
      modifier = Modifier.fillMaxWidth(),
      placeholder = "romm.example.com",
      label = "Server address",
      help = "The address you open RomM at in a browser, such as romm.example.com.",
      keyboardOptions =
        KeyboardOptions(
          autoCorrectEnabled = false,
          keyboardType = KeyboardType.Uri,
          imeAction = ImeAction.Go,
        ),
      onSubmit = { next(SignInScreen::Code) },
    )
    UnencryptedNote(serverUrl(state.address.text.toString()))
    error?.let { Text(it, color = Placeholder.text, modifier = Modifier.announced()) }
    SignInToggle(
      state,
      "allowUploads",
      "Allow uploading games to your server",
      checked = state.allowUploads,
      "Lets this device upload games that aren't on your server.",
    ) {
      state.allowUploads = !state.allowUploads
    }
    SignInButton(
      state,
      "continue",
      "Continue",
      "Get a code, then approve it in RomM on your phone or computer.",
    ) {
      next(SignInScreen::Code)
    }
    SignInButton(state, "token", "Use an API token", TOKEN_HELP) { next(SignInScreen::Token) }
  }
}

@Composable
private fun CodeScreen(state: SignInState, baseUrl: String) {
  var attempt by remember { mutableIntStateOf(0) }
  var code by remember { mutableStateOf<DeviceCode?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(attempt) {
    code = null
    error = null
    try {
      state.signIn.withDeviceCode(baseUrl, state.device, state.allowUploads) { code = it }
      state.onSignedIn()
    } catch (e: RommException) {
      error = signInErrorMessage(e, baseUrl, SignInMethod.Code)
    } catch (_: IOException) {
      error = SIGN_IN_NOT_SAVED
    }
  }
  ScreenColumn(canGoBack = true) {
    Text(
      "Approve this device",
      color = Placeholder.text,
      fontSize = 28.sp,
      modifier = Modifier.semantics { heading() },
    )
    val shown = code
    when {
      error != null ->
        Text(error.orEmpty(), color = Placeholder.text, modifier = Modifier.announced())
      shown == null -> Text("Getting a code", color = Placeholder.mutedText)
      else -> {
        Text("Open", color = Placeholder.mutedText)
        Text(shown.verificationUrl, color = Placeholder.text)
        Text("Code", color = Placeholder.mutedText)
        Text(
          shown.userCode,
          color = Placeholder.text,
          fontSize = 48.sp,
          letterSpacing = 6.sp,
          // Spelled out, a character at a time, the way it's typed.
          modifier =
            Modifier.semantics { contentDescription = shown.userCode.toList().joinToString(" ") },
        )
        Text("Waiting for approval", color = Placeholder.mutedText)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      if (error != null) {
        SignInButton(state, "retry", "Get a new code") { attempt++ }
      }
      SignInButton(state, "token", "Use an API token instead", TOKEN_HELP) {
        state.navigator.push(SignInScreen.Token(baseUrl))
      }
    }
  }
}

@Composable
private fun TokenScreen(state: SignInState, baseUrl: String) {
  var error by remember { mutableStateOf<String?>(null) }
  var checking by remember { mutableStateOf(false) }
  // Each visit starts empty, so a token typed for one server isn't sent to another.
  val field = remember { TextFieldState() }
  val scope = rememberCoroutineScope()
  val clipboard = LocalClipboard.current
  fun submit() {
    if (checking) return
    val token = field.text.toString()
    if (token.isBlank()) {
      error = "Token required"
      return
    }
    error = null
    checking = true
    scope.launch {
      try {
        state.signIn.withToken(baseUrl, token)
        state.onSignedIn()
      } catch (e: RommException) {
        error = signInErrorMessage(e, baseUrl, SignInMethod.Token)
      } catch (_: IOException) {
        error = SIGN_IN_NOT_SAVED
      } finally {
        checking = false
      }
    }
  }
  ScreenColumn(canGoBack = true) {
    Text(
      "Sign in with an API token",
      color = Placeholder.text,
      fontSize = 28.sp,
      modifier = Modifier.semantics { heading() },
    )
    Text("Client API token from RomM", color = Placeholder.mutedText)
    UnencryptedNote(baseUrl)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      TextEntry(
        state.help,
        key = "token",
        field = field,
        modifier = Modifier.weight(1f),
        placeholder = "API token",
        label = "API token",
        help = "A client API token from RomM, for $baseUrl.",
        secure = true,
        keyboardOptions =
          KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
          ),
        onSubmit = ::submit,
      )
      SignInButton(state, "paste", "Paste") {
        scope.launch {
          val text = clipboard.readText()?.trim()
          if (text.isNullOrEmpty()) {
            error = "Clipboard empty"
          } else {
            field.setTextAndPlaceCursorAtEnd(text)
            error = null
          }
        }
      }
    }
    when {
      checking -> Text("Checking token", color = Placeholder.mutedText)
      error != null ->
        Text(error.orEmpty(), color = Placeholder.text, modifier = Modifier.announced())
    }
    SignInButton(state, "sign-in", "Sign in", onConfirm = ::submit)
  }
}

private const val TOKEN_HELP =
  "Sign in with a client API token that you create in RomM, instead of approving a code."

private const val SIGN_IN_NOT_SAVED = "Sign-in not saved"

/**
 * Says that the server at [url] isn't encrypted, when it isn't. Plain HTTP is allowed, for a server
 * on a private network or a tailnet.
 */
@Composable
private fun UnencryptedNote(url: String?) {
  if (url != null && parseUrl(url)?.protocol == URLProtocol.HTTP) {
    Text("Unencrypted connection", color = Placeholder.mutedText)
  }
}

@Composable
private fun ScreenColumn(canGoBack: Boolean, content: @Composable () -> Unit) {
  Column(Modifier.fillMaxSize()) {
    Column(
      Modifier.fillMaxWidth().weight(1f).padding(48.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      content()
    }
    HintBar(
      buildList {
        add(Hint(Action.Confirm, "Choose"))
        if (canGoBack) add(Hint(Action.Back, "Back"))
        add(Hint(Action.MainMenu, "Main menu"))
      },
    )
  }
}

/**
 * A text field for a controller: an item that focus moves over like any other, and that opens the
 * system keyboard on [Action.Confirm] or a tap. [Action.Back], [Action.Confirm] or moving focus
 * away stops editing; the keyboard's action key also calls [onSubmit]. Focusing it puts [help] on
 * [helpHolder]'s lower screen.
 *
 * @param label names the field to accessibility services and titles its help.
 * @param secure hides what's typed, for secrets.
 */
@Composable
internal fun TextEntry(
  helpHolder: HelpHolder,
  key: String,
  field: TextFieldState,
  placeholder: String,
  label: String,
  help: String,
  keyboardOptions: KeyboardOptions,
  onSubmit: () -> Unit,
  modifier: Modifier = Modifier,
  secure: Boolean = false,
) {
  var editing by remember { mutableStateOf(false) }
  val itemRequester = remember { FocusRequester() }
  val fieldRequester = remember { FocusRequester() }
  fun stopEditing() {
    itemRequester.requestFocus()
    editing = false
  }
  val text = field.text
  // Shown text that a screen reader would read as dots, or as a placeholder, says nothing; the
  // label names the field and says what it holds.
  val value =
    when {
      text.isEmpty() -> "empty"
      secure -> "entered"
      else -> text.toString()
    }
  Box(
    modifier
      .help(helpHolder, key, label, help)
      .focusRequester(itemRequester)
      .focusItem(
        key,
        onConfirm = { if (editing) stopEditing() else editing = true },
        description = if (editing) null else "$label: $value",
        onAction = { action ->
          if (action == Action.Back && editing) {
            stopEditing()
            true
          } else {
            false
          }
        },
      )
      .background(Placeholder.tile)
      .then(if (editing) Modifier.border(2.dp, Placeholder.text) else Modifier)
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    if (editing) {
      var hadFocus by remember { mutableStateOf(false) }
      val modifier =
        Modifier.fillMaxWidth().focusRequester(fieldRequester).onFocusChanged {
          // Focus that moves on, such as with the D-pad, ends editing too.
          if (it.isFocused) hadFocus = true else if (hadFocus) editing = false
        }
      val textStyle = TextStyle(color = Placeholder.text, fontSize = 20.sp)
      val cursor = SolidColor(Placeholder.text)
      val submit = {
        stopEditing()
        onSubmit()
      }
      if (secure) {
        BasicSecureTextField(
          state = field,
          modifier = modifier,
          textStyle = textStyle,
          keyboardOptions = keyboardOptions,
          onKeyboardAction = { submit() },
          cursorBrush = cursor,
          textObfuscationMode = TextObfuscationMode.Hidden,
        )
      } else {
        BasicTextField(
          state = field,
          modifier = modifier,
          textStyle = textStyle,
          keyboardOptions = keyboardOptions,
          onKeyboardAction = { submit() },
          lineLimits = TextFieldLineLimits.SingleLine,
          cursorBrush = cursor,
        )
      }
      LaunchedEffect(Unit) { fieldRequester.requestFocus() }
    } else {
      Text(
        when {
          text.isEmpty() -> placeholder
          secure -> "•".repeat(minOf(text.length, 32))
          else -> text.toString()
        },
        color = if (text.isEmpty()) Placeholder.mutedText else Placeholder.text,
        fontSize = 20.sp,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun SignInButton(
  state: SignInState,
  key: String,
  label: String,
  help: String? = null,
  onConfirm: () -> Unit,
) {
  Box(
    Modifier.help(state.help, key, label, help)
      .focusItem(key, onConfirm = onConfirm)
      .background(Placeholder.tile)
      .padding(horizontal = 24.dp, vertical = 12.dp),
  ) {
    Text(label, color = Placeholder.text)
  }
}

/** A choice on a sign-in screen, which [onToggle] turns on or off. */
@Composable
private fun SignInToggle(
  state: SignInState,
  key: String,
  label: String,
  checked: Boolean,
  help: String,
  onToggle: () -> Unit,
) {
  Row(
    Modifier.help(state.help, key, label, help)
      .semantics { toggleableState = ToggleableState(checked) }
      .focusItem(key, onConfirm = onToggle, role = Role.Switch)
      .background(Placeholder.tile)
      .padding(horizontal = 24.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    Text(label, color = Placeholder.text)
    Text(if (checked) "On" else "Off", color = Placeholder.mutedText)
  }
}

/** Makes text that a screen reader says as soon as it appears, such as an error. */
private fun Modifier.announced() = semantics { liveRegion = LiveRegionMode.Polite }
