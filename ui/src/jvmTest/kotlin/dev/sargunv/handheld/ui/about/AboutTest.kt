package dev.sargunv.handheld.ui.about

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.auth.InMemoryCredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
import dev.sargunv.handheld.data.cache.LibraryCache
import dev.sargunv.handheld.ui.App
import dev.sargunv.handheld.ui.TestDevice
import dev.sargunv.handheld.ui.dualscreen.DualScreenLayout
import dev.sargunv.handheld.ui.input.HeldKeys
import dev.sargunv.handheld.ui.input.trackHeldKeys
import dev.sargunv.handheld.ui.rememberTestDownloads
import dev.sargunv.handheld.ui.testCoverDir
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AboutTest {
  /** Two components, the second with a licence longer than the screen. */
  private val components =
    """
    {
      "libraries": [
        {"uniqueId": "org.example:beta", "name": "beta", "artifactVersion": "2.0",
         "description": "The second one.", "licenses": ["Apache-2.0"]},
        {"uniqueId": "crates.io:alpha@1.0.0", "name": "Alpha", "artifactVersion": "1.0.0",
         "licenses": ["crate-MIT-1"]}
      ],
      "licenses": {
        "Apache-2.0": {"name": "Apache License 2.0", "spdxId": "Apache-2.0",
                       "content": "${(1..200).joinToString("\\n\\n") { "Paragraph $it" }}"},
        "crate-MIT-1": {"name": "MIT License", "spdxId": "MIT", "content": "Copyright Alpha"}
      }
    }
    """

  /** Signs in to a server that's out of reach, so the places show nothing to focus. */
  private fun ComposeUiTest.launch() {
    setContent {
      val heldKeys = remember { HeldKeys() }
      val library = remember { LibraryCache.open(path = null) }
      val credentials = remember {
        InMemoryCredentialStore(RommCredentials("https://romm.test", "rmm_token"))
      }
      App(
        DualScreenLayout.Frame,
        heldKeys,
        credentials,
        TestDevice,
        library,
        rememberTestDownloads(library, credentials),
        remember { testCoverDir() },
        Modifier.trackHeldKeys(heldKeys),
        httpEngine = { MockEngine { respondError(HttpStatusCode.NotFound) } },
        appInfo = remember { AppInfo("1.2.3") { components } },
      )
    }
  }

  private fun ComposeUiTest.awaitFocused(tag: String) {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun ComposeUiTest.press(keys: List<Key>) {
    for (key in keys) {
      onRoot().performKeyInput { keyDown(key) }
      waitForIdle()
      onRoot().performKeyInput { keyUp(key) }
      waitForIdle()
    }
  }

  private fun ComposeUiTest.press(key: Key) = press(listOf(key))

  @Test
  fun opensFromTheQuickMenuAndShowsEachComponentsLicence() = runComposeUiTest {
    launch()
    waitUntil(timeoutMillis = 5_000) {
      onAllNodes(hasText("Offline")).fetchSemanticsNodes().isNotEmpty()
    }
    press(Key.ButtonStart)
    awaitFocused("quick:confirm")
    press(listOf(Key.DirectionDown, Key.DirectionDown))
    onNodeWithTag("quick:about").assertIsFocused()
    press(Key.ButtonA)

    // The components come by name, whatever their case, and the first one has focus.
    awaitFocused("component:crates.io:alpha@1.0.0")
    onNodeWithText("About").assert(isHeading())
    onNodeWithText("Version 1.2.3").assertExists()
    onNodeWithTag("about:source").assertTextEquals(SourceUrl)
    onNodeWithText("1.0.0 · MIT").assertExists()
    onNodeWithText("2.0 · Apache-2.0").assertExists()
    // The lower screen describes the focused component.
    onNodeWithTag("about:licence").assertTextEquals("MIT License")

    press(Key.DirectionDown)
    onNodeWithTag("component:org.example:beta").assertIsFocused()
    onNodeWithText("The second one.").assertExists()
    onNodeWithTag("about:licence").assertTextEquals("Apache License 2.0")

    press(Key.ButtonA)
    awaitFocused("licence")
    onNodeWithTag("licence:Apache-2.0").assert(isHeading())
    // The lower screen still describes the component.
    onNodeWithTag("about:licence").assertTextEquals("Apache License 2.0")
    // Down scrolls a little and R a page, past the start.
    press(Key.DirectionDown)
    onNodeWithText("Paragraph 1").assertExists()
    press(listOf(Key.ButtonR1, Key.ButtonR1))
    onAllNodesWithText("Paragraph 1").fetchSemanticsNodes().let { check(it.isEmpty()) }
    onNodeWithTag("licence").assertIsFocused()

    // Back returns to the component it showed, then to the places.
    press(Key.ButtonB)
    awaitFocused("component:org.example:beta")
    press(Key.ButtonB)
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithText("Third-party components").fetchSemanticsNodes().isEmpty()
    }
    onAllNodesWithText("The second one.").fetchSemanticsNodes().let { check(it.isEmpty()) }
  }
}
