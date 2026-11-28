package dev.sargunv.handheld.ui.places

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import dev.sargunv.handheld.ui.awaitFocused
import dev.sargunv.handheld.ui.awaitTag
import dev.sargunv.handheld.ui.fakeRomm
import dev.sargunv.handheld.ui.json
import dev.sargunv.handheld.ui.lastPlayed
import dev.sargunv.handheld.ui.launchApp
import dev.sargunv.handheld.ui.page
import dev.sargunv.handheld.ui.platform
import dev.sargunv.handheld.ui.press
import dev.sargunv.handheld.ui.rom
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test

/** A screen the launch intent's `open` extra names shows as if the player had gone there. */
@OptIn(ExperimentalTestApi::class)
class OpenRequestTest {
  /** A server with two games on one platform, of which the player played game 1 last. */
  private val server = fakeRomm { path, request ->
    json(
      when (path) {
        "platforms" -> "[${platform(roms = 2)}]"
        "roms" ->
          if (request.lastPlayed) page(listOf(rom(1, lastPlayed = "2025-01-01")))
          else page(listOf(rom(1), rom(2)))
        "roms/identifiers" -> "[1, 2]"
        "collections",
        "collections/smart" -> "[]"
        else -> return@fakeRomm respondError(HttpStatusCode.NotFound)
      },
    )
  }

  @Test
  fun aGameOpensOverHome() = runComposeUiTest {
    launchApp(server, open = OpenTarget.Game(2))
    awaitTag("primary")
    onNodeWithText("Game 2").assert(isHeading())
    press(Key.ButtonB)
    awaitTag("continue:1")
  }

  @Test
  fun aPlatformOpensItsShelfOverThePlatforms() = runComposeUiTest {
    launchApp(server, open = OpenTarget.LibraryPlatform(PlatformCatalog.GB))
    awaitTag("shelf:platform:1")
    onNodeWithTag("place:Library").assertExists()
    press(Key.ButtonB)
    awaitFocused("library:platform:1")
  }
}
