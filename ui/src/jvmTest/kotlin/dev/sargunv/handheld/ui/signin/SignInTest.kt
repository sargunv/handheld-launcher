package dev.sargunv.handheld.ui.signin

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.TestDevice
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.trackHeldKeys
import dev.sargunv.handheld.ui.rememberTestDownloads
import dev.sargunv.handheld.ui.testCoverDir
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.IOException

@OptIn(ExperimentalTestApi::class)
class SignInTest {
  /** The bodies of the requests to start a device sign-in. */
  private val pairings = Collections.synchronizedList(mutableListOf<String>())

  /** A server that hands out a code, and never approves it. */
  private val server = {
    MockEngine { request ->
      if (request.url.encodedPath != "/api/auth/device/init") {
        return@MockEngine respondError(HttpStatusCode.NotFound)
      }
      pairings += (request.body as TextContent).text
      respond(
        """{"device_code": "secret", "user_code": "ABCD2345", "verification_path": "/pair/device",
        "verification_path_complete": "/pair/device?user_code=ABCD2345", "expires_in": 600,
        "interval": 5}""",
        HttpStatusCode.Created,
        headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
  }

  private fun androidx.compose.ui.test.ComposeUiTest.launch(store: CredentialStore) {
    setContent {
      val heldKeys = remember { HeldKeys() }
      val library = remember { LibraryCache.open(path = null) }
      App(
        DualScreenLayout.Separate,
        heldKeys,
        store,
        TestDevice,
        library,
        rememberTestDownloads(library, store),
        remember { testCoverDir() },
        Modifier.trackHeldKeys(heldKeys),
        httpEngine = server,
      )
    }
    waitForIdle()
  }

  /** Enters an address, and asks it for a code, with uploads allowed when [allowUploads]. */
  private fun androidx.compose.ui.test.ComposeUiTest.pair(allowUploads: Boolean): String {
    launch(InMemoryCredentialStore())
    onNodeWithTag("allowUploads")
      .assert(expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
      .assert(expectValue(SemanticsProperties.Role, Role.Switch))
    onRoot().performKeyInput { pressKey(Key.ButtonA) }
    waitForIdle()
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
  fun opensOnTheServerAddressWithoutCredentials() = runComposeUiTest {
    launch(InMemoryCredentialStore())
    onNodeWithTag("address").assertIsFocused()
  }

  @Test
  fun fieldsSayWhatTheyAreAndWhatTheyHold() = runComposeUiTest {
    launch(InMemoryCredentialStore())
    onNodeWithTag("address").assertContentDescriptionEquals("Server address: empty")
    onNodeWithText("Sign in to RomM").assert(isHeading())
    onNodeWithTag("continue").assert(expectValue(SemanticsProperties.Role, Role.Button))
  }

  @Test
  fun eachScreenShowsItsButtonHints() = runComposeUiTest {
    launch(InMemoryCredentialStore())
    onNodeWithTag("hint:Confirm").assertTextEquals("A", "Choose")
    onNodeWithTag("hint:MainMenu").assertTextEquals("Start", "Main menu")
    // The first screen has nothing to go back to.
    onNodeWithTag("hint:Back").assertDoesNotExist()
    onRoot().performKeyInput { pressKey(Key.ButtonA) }
    waitForIdle()
    onNode(hasSetTextAction()).performTextReplacement("romm.example.com")
    onNodeWithTag("token").performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
    onNodeWithText("Sign in with an API token").assertExists()
    onNodeWithTag("hint:Back").assertTextEquals("B", "Back")
    onNodeWithTag("hint:Confirm").assertExists()
  }

  @Test
  fun asksForAnAddressBeforeGoingOn() = runComposeUiTest {
    launch(InMemoryCredentialStore())
    onRoot().performKeyInput { pressKey(Key.DirectionDown) }
    waitForIdle()
    onNodeWithTag("allowUploads").assertIsFocused()
    onRoot().performKeyInput { pressKey(Key.DirectionDown) }
    waitForIdle()
    onNodeWithTag("continue").assertIsFocused()
    onRoot().performKeyInput { pressKey(Key.ButtonA) }
    waitForIdle()
    onNodeWithText("Enter the address", substring = true).assertExists()
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
  fun notesAnUnencryptedServer() {
    assertEquals(
      "This address isn't encrypted. If the server doesn't switch to https itself, your sign-in " +
        "is sent in the clear, so use it only on a network you trust.",
      unencryptedNote(serverUrl("http://192.168.1.5:8080")),
    )
    assertNull(unencryptedNote(serverUrl("romm.example.com")))
    assertNull(unencryptedNote(serverUrl("https://romm.example.com")))
    assertNull(unencryptedNote(null))
  }

  @Test
  fun notesAnUnencryptedAddressAsItsTyped() = runComposeUiTest {
    launch(InMemoryCredentialStore())
    onRoot().performKeyInput { pressKey(Key.ButtonA) }
    waitForIdle()
    onNode(hasSetTextAction()).performTextInput("romm.example.com")
    waitForIdle()
    onNodeWithText("This address isn't encrypted", substring = true).assertDoesNotExist()
    onNode(hasSetTextAction()).performTextReplacement("http://192.168.1.5:8080")
    waitForIdle()
    onNodeWithText("This address isn't encrypted", substring = true).assertExists()
  }

  @Test
  fun explainsFailuresPlainly() {
    val url = "https://romm.example.com"
    assertEquals(
      "Couldn't reach https://romm.example.com. Check the address, and that this device is online.",
      signInErrorMessage(RommException.Unreachable(IOException("boom")), url, SignInMethod.Code),
    )
    assertEquals(
      "The server didn't accept this token. Check that it's complete, and that it hasn't " +
        "expired or been deleted in RomM.",
      signInErrorMessage(RommException.Unauthorized(null), url, SignInMethod.Token),
    )
    assertEquals(
      "The code expired before it was approved. Get a new code to try again.",
      signInErrorMessage(RommException.DeviceAuthExpired(), url, SignInMethod.Code),
    )
  }
}
