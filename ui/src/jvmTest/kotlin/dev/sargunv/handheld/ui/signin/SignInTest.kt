package dev.sargunv.handheld.ui.signin

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.io.IOException

@OptIn(ExperimentalTestApi::class)
class SignInTest {
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
      )
    }
    waitForIdle()
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
  fun asksForAnAddressBeforeGoingOn() = runComposeUiTest {
    launch(InMemoryCredentialStore())
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
