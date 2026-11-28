package dev.sargunv.handheld.ui.signin

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.Vault
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.ui.awaitTag
import dev.sargunv.handheld.ui.awaitText
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.fakeRomm
import dev.sargunv.handheld.ui.json
import dev.sargunv.handheld.ui.launchApp
import dev.sargunv.handheld.ui.press
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class SignInTest {
  private val temp = TemporaryFolder().apply { create() }

  @AfterTest fun cleanUp() = temp.delete()

  /** The bodies of the requests to start a device sign-in. */
  private val pairings = Collections.synchronizedList(mutableListOf<String>())

  /** A server that hands out a code, and never approves it. */
  private val server = fakeRomm { path, request ->
    if (path != "auth/device/init") return@fakeRomm respondError(HttpStatusCode.NotFound)
    pairings += (request.body as TextContent).text
    json(
      """{"device_code": "secret", "user_code": "ABCD2345", "verification_path": "/pair/device",
      "verification_path_complete": "/pair/device?user_code=ABCD2345", "expires_in": 600,
      "interval": 5}""",
      HttpStatusCode.Created,
    )
  }

  /** The presses of the system's HOME, which [pressHome] makes. */
  private val homePresses = Channel<Unit>(Channel.UNLIMITED)

  /** Shows the app signed out. */
  private fun ComposeUiTest.launch() {
    launchApp(
      temp,
      server,
      credentials = Vault.InMemory<RommCredentials>(),
      homePresses = homePresses.receiveAsFlow(),
      layout = DualScreenLayout.Separate,
    )
    waitForIdle()
  }

  private fun ComposeUiTest.pressHome() {
    homePresses.trySend(Unit)
    waitForIdle()
  }

  /** Enters an address, and asks it for a code, with uploads allowed when [allowUploads]. */
  private fun ComposeUiTest.pair(allowUploads: Boolean): String {
    launch()
    onNodeWithTag("allowUploads")
      .assert(expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
      .assert(expectValue(SemanticsProperties.Role, Role.Switch))
    press(Key.ButtonA)
    onNode(hasSetTextAction()).performTextReplacement("romm.example.com")
    if (allowUploads) {
      onNodeWithTag("allowUploads").performSemanticsAction(SemanticsActions.OnClick)
      waitForIdle()
      onNodeWithTag("allowUploads")
        .assert(expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }
    onNodeWithTag("continue").performSemanticsAction(SemanticsActions.OnClick)
    waitUntil(timeoutMillis = 5_000) { pairings.isNotEmpty() }
    return pairings.single()
  }

  @Test
  fun asksForTheUsualPermissionsByDefault() = runComposeUiTest {
    val scopes = pair(allowUploads = false)
    assertTrue("\"roms.read\"" in scopes)
    assertFalse("roms.write" in scopes)
    assertFalse("tasks.run" in scopes)
  }

  @Test
  fun asksForPermissionToUploadWhenAllowed() = runComposeUiTest {
    val scopes = pair(allowUploads = true)
    assertTrue("\"roms.read\"" in scopes)
    assertTrue("\"roms.write\"" in scopes)
    assertTrue("\"tasks.run\"" in scopes)
  }

  @Test
  fun homeClosesSettingsBackToTheStepUnderIt() = runComposeUiTest {
    pair(allowUploads = false)
    awaitText("Approve this device")
    press(Key.ButtonStart)
    awaitTag("main:Settings")
    press(Key.ButtonA)
    awaitTag("place:Settings")
    pressHome()
    onNodeWithTag("place:Settings").assertDoesNotExist()
    onNodeWithText("Approve this device").assertExists()
    pressHome()
    onNodeWithText("Approve this device").assertExists()
  }

  @Test
  fun opensOnTheServerAddressWithoutCredentials() = runComposeUiTest {
    launch()
    onNodeWithTag("address").assertIsFocused()
  }

  @Test
  fun fieldsSayWhatTheyAreAndWhatTheyHold() = runComposeUiTest {
    launch()
    onNodeWithTag("address").assertContentDescriptionEquals("Server address: empty")
    onNodeWithText("Sign in to RomM").assert(isHeading())
    onNodeWithTag("continue").assert(expectValue(SemanticsProperties.Role, Role.Button))
  }

  @Test
  fun eachScreenShowsItsButtonHints() = runComposeUiTest {
    launch()
    onNodeWithTag("hint:Confirm").assertTextEquals("A", "Choose")
    onNodeWithTag("hint:MainMenu").assertTextEquals("Start", "Main menu")
    // The first screen has nothing to go back to.
    onNodeWithTag("hint:Back").assertDoesNotExist()
    press(Key.ButtonA)
    onNode(hasSetTextAction()).performTextReplacement("romm.example.com")
    onNodeWithTag("token").performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
    onNodeWithText("Sign in with an API token").assertExists()
    onNodeWithTag("hint:Back").assertTextEquals("B", "Back")
    onNodeWithTag("hint:Confirm").assertExists()
  }

  @Test
  fun asksForAnAddressBeforeGoingOn() = runComposeUiTest {
    launch()
    press(Key.DirectionDown)
    onNodeWithTag("allowUploads").assertIsFocused()
    press(Key.DirectionDown)
    onNodeWithTag("continue").assertIsFocused()
    press(Key.ButtonA)
    onNodeWithText("Valid server address required").assertExists()
  }

  @Test
  fun readsServerAddresses() {
    assertEquals("https://romm.example.com", serverUrl(" romm.example.com/ "))
    assertEquals("http://192.168.1.5:8080", serverUrl("http://192.168.1.5:8080"))
    assertEquals("https://example.com/romm", serverUrl("https://example.com/romm"))
    assertNull(serverUrl(""))
    assertNull(serverUrl("ftp://romm.example.com"))
  }

  @Test
  fun notesAnUnencryptedAddressAsItsTyped() = runComposeUiTest {
    launch()
    press(Key.ButtonA)
    onNode(hasSetTextAction()).performTextInput("romm.example.com")
    waitForIdle()
    onNodeWithText("Unencrypted connection").assertDoesNotExist()
    onNode(hasSetTextAction()).performTextReplacement("http://192.168.1.5:8080")
    waitForIdle()
    onNodeWithText("Unencrypted connection").assertExists()
  }
}
