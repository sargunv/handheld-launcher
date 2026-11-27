package dev.sargunv.handheld.ui.settings

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.data.achievements.InMemoryRetroAchievementsStore
import dev.sargunv.handheld.data.achievements.RetroAchievementsAccount
import dev.sargunv.handheld.data.achievements.RetroAchievementsClient
import dev.sargunv.handheld.data.achievements.RetroAchievementsStore
import dev.sargunv.handheld.ui.Settings
import dev.sargunv.handheld.ui.achievements.AchievementSettings
import dev.sargunv.handheld.ui.achievements.RetroAchievementsSignIn
import dev.sargunv.handheld.ui.awaitFocused
import dev.sargunv.handheld.ui.awaitText
import dev.sargunv.handheld.ui.launchApp
import dev.sargunv.handheld.ui.places.OpenTarget
import dev.sargunv.handheld.ui.press
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException

@OptIn(ExperimentalTestApi::class)
class RetroAchievementsTest {
  private val store = InMemoryRetroAchievementsStore()
  private val saved = mutableListOf<Settings>()

  /**
   * Opens the RetroAchievements section, whose server [accepts] the password or refuses it once
   * [answer] completes, keeping the account in [accounts].
   */
  private fun ComposeUiTest.launch(
    accepts: Boolean,
    answer: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    accounts: RetroAchievementsStore = store,
  ) {
    launchApp(
      saveSettings = { saved += it },
      retroAchievements =
        RetroAchievementsSignIn(accounts) {
          RetroAchievementsClient(
            "HandheldLauncher/test rcheevos/12.5",
            MockEngine {
              answer.await()
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
        },
      open = OpenTarget.Settings(SettingsSection.RetroAchievements),
    )
    awaitFocused("settings:RetroAchievements")
  }

  /** Types into the username and password fields, then presses Sign in. */
  private fun ComposeUiTest.signIn(username: String, password: String) {
    press(Key.DirectionRight)
    for ((tag, text) in
      listOf("settings:ra:username" to username, "settings:ra:password" to password)) {
      awaitFocused(tag)
      press(Key.ButtonA)
      onNode(hasSetTextAction()).performTextInput(text)
      press(listOf(Key.ButtonB, Key.DirectionDown))
    }
    awaitFocused("settings:ra:signIn")
    press(Key.ButtonA)
  }

  @Test
  fun signsInAndKeepsOnlyTheToken() = runComposeUiTest {
    launch(accepts = true)
    signIn("player", "hunter2")
    awaitFocused("settings:ra:account")
    onNodeWithTag("settings:ra:account").assertTextEquals("Account", "Player")
    assertEquals(RetroAchievementsAccount("Player", "ra_token"), runBlocking { store.load() })

    onNodeWithTag("settings:ra:hardcore").assertTextEquals("Hardcore", "Off")
    onNodeWithTag("settings:ra:hardcore").performSemanticsAction(SemanticsActions.OnClick)
    onNodeWithTag("settings:ra:hardcore").assertTextEquals("Hardcore", "On")
    waitForIdle()
    assertEquals(AchievementSettings(hardcore = true), saved.last().achievements)

    onNodeWithTag("settings:ra:signOut").performSemanticsAction(SemanticsActions.OnClick)
    awaitFocused("settings:ra:username")
    assertNull(runBlocking { store.load() })
    // The password isn't kept, even in its field.
    onNodeWithTag("settings:ra:password").assertContentDescriptionEquals("Password: empty")
  }

  @Test
  fun finishesASignInAfterFocusLeavesTheSection() = runComposeUiTest {
    val held = CompletableDeferred<Unit>()
    launch(accepts = true, answer = held)
    signIn("player", "hunter2")
    awaitText("Signing in")
    press(listOf(Key.DirectionLeft, Key.DirectionUp))
    awaitFocused("settings:RomM")
    held.complete(Unit)
    waitUntil(timeoutMillis = 5_000) {
      runBlocking { store.load() } == RetroAchievementsAccount("Player", "ra_token")
    }
  }

  @Test
  fun saysWhyASignInFailed() = runComposeUiTest {
    launch(accepts = false)
    signIn("player", "wrong")
    awaitText("Invalid user/password combination.")
    onNodeWithTag("settings:ra:status")
      .assertTextEquals("Sign-in failed · Invalid user/password combination. Please try again.")
    assertNull(runBlocking { store.load() })
    onNodeWithTag("settings:ra:password").assertContentDescriptionEquals("Password: empty")
  }

  @Test
  fun saysWhenASignInIsNotSaved() = runComposeUiTest {
    launch(
      accepts = true,
      accounts =
        object : RetroAchievementsStore by store {
          override suspend fun save(account: RetroAchievementsAccount) {
            throw IOException("Keystore unavailable")
          }
        },
    )
    signIn("player", "hunter2")
    awaitText("Sign-in not saved")
    onNodeWithTag("settings:ra:status").assertTextEquals("Sign-in not saved")
  }
}
