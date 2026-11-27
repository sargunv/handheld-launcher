package dev.sargunv.handheld.ui.achievements

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.achievements.InMemoryRetroAchievementsStore
import dev.sargunv.handheld.data.achievements.RetroAchievementsAccount
import dev.sargunv.handheld.data.achievements.RetroAchievementsClient
import dev.sargunv.handheld.ui.focus.FocusLazyColumn
import dev.sargunv.handheld.ui.focus.FocusRoot
import dev.sargunv.handheld.ui.input.TestActionInputRoot
import dev.sargunv.handheld.ui.settings.SettingsView
import dev.sargunv.handheld.ui.settings.retroAchievements
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class RetroAchievementsTest {
  private val store = InMemoryRetroAchievementsStore()

  private fun signIn(accepts: Boolean) =
    RetroAchievementsSignIn(store) {
      RetroAchievementsClient(
        "HandheldLauncher/test rcheevos/12.5",
        MockEngine {
          if (accepts) {
            respond("""{"Success":true,"User":"Player","Token":"ra_token"}""")
          } else {
            respond(
              """{"Success":false,"Status":401,"Code":"invalid_credentials",
              "Error":"Invalid user/password combination. Please try again."}""",
              HttpStatusCode.Unauthorized,
            )
          }
        },
      )
    }

  private var settings by mutableStateOf(AchievementSettings())

  private fun ComposeUiTest.show(state: RetroAchievementsState) {
    runBlocking { state.load() }
    setContent {
      TestActionInputRoot {
        FocusRoot {
          FocusLazyColumn { retroAchievements(SettingsView(), state, settings) { settings = it } }
        }
      }
    }
    waitForIdle()
  }

  /** Presses Sign in and waits until the sign-in has finished, one way or the other. */
  private fun ComposeUiTest.signIn(state: RetroAchievementsState) {
    onNodeWithTag("settings:ra:signIn").performSemanticsAction(SemanticsActions.OnClick)
    waitUntil(timeoutMillis = 5_000) {
      !state.busy && (state.username != null || state.error != null)
    }
    waitForIdle()
  }

  @Test
  fun signsInAndKeepsOnlyTheToken() = runComposeUiTest {
    val state = RetroAchievementsState(signIn(accepts = true))
    show(state)
    state.usernameField.setTextAndPlaceCursorAtEnd("player")
    state.passwordField.setTextAndPlaceCursorAtEnd("hunter2")
    signIn(state)
    onNodeWithTag("settings:ra:account").assertTextEquals("Account", "Player")
    assertEquals(RetroAchievementsAccount("Player", "ra_token"), runBlocking { store.load() })
    assertEquals("", state.passwordField.text.toString())

    onNodeWithTag("settings:ra:hardcore").assertTextEquals("Hardcore", "Off")
    onNodeWithTag("settings:ra:hardcore").performSemanticsAction(SemanticsActions.OnClick)
    assertEquals(AchievementSettings(hardcore = true), settings)
    onNodeWithTag("settings:ra:hardcore").assertTextEquals("Hardcore", "On")

    onNodeWithTag("settings:ra:signOut").performSemanticsAction(SemanticsActions.OnClick)
    waitUntil(timeoutMillis = 5_000) { state.username == null }
    assertNull(runBlocking { store.load() })
  }

  @Test
  fun saysWhyASignInFailed() = runComposeUiTest {
    val state = RetroAchievementsState(signIn(accepts = false))
    show(state)
    state.usernameField.setTextAndPlaceCursorAtEnd("player")
    state.passwordField.setTextAndPlaceCursorAtEnd("wrong")
    signIn(state)
    onNodeWithTag("settings:ra:status")
      .assertTextEquals("Invalid user/password combination. Please try again.")
    assertNull(runBlocking { store.load() })
    assertEquals("", state.passwordField.text.toString())
  }
}
