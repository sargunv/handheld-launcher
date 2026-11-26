package dev.sargunv.handheld

import android.content.Intent
import dev.sargunv.handheld.ui.places.OpenRequest
import dev.sargunv.handheld.ui.places.OpenTarget
import dev.sargunv.handheld.ui.places.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HomeIntentTest {
  @Test
  fun homeIsMainWithTheHomeCategory() {
    assertTrue(isHomeIntent(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME)))
    assertTrue(
      isHomeIntent(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME, Intent.CATEGORY_DEFAULT)),
    )
  }

  @Test
  fun theLauncherIconAndOtherIntentsAreNotHome() {
    assertFalse(isHomeIntent(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER)))
    assertFalse(isHomeIntent(Intent.ACTION_MAIN, null))
    assertFalse(isHomeIntent(Intent.ACTION_VIEW, setOf(Intent.CATEGORY_HOME)))
    assertFalse(isHomeIntent(null, null))
  }

  @Test
  fun homeInFrontIsAPress() {
    assertEquals(
      IntentResponse.PressHome,
      respondTo(home = true, open = null, wasInFront = true, playing = false),
    )
  }

  @Test
  fun homeFromAnotherAppOnlyBringsTheLauncherBack() {
    assertEquals(
      IntentResponse.Show,
      respondTo(home = true, open = null, wasInFront = false, playing = false),
    )
  }

  @Test
  fun homeDuringAPlayLeavesItsScreenAlone() {
    // A standalone game: HOME brings the launcher back, and the Game screen syncs the save.
    assertEquals(
      IntentResponse.Show,
      respondTo(home = true, open = null, wasInFront = false, playing = true),
    )
    // Before the game starts, such as during the save's sync or while its core downloads.
    assertEquals(
      IntentResponse.Show,
      respondTo(home = true, open = null, wasInFront = true, playing = true),
    )
  }

  @Test
  fun anOpenRequestWinsOverHome() {
    val open = OpenRequest(OpenTarget.AtPlace(Place.Library))
    val response = respondTo(home = true, open = open, wasInFront = true, playing = false)
    assertIs<IntentResponse.Open>(response)
    assertEquals(open, response.request)
  }

  @Test
  fun anotherIntentInFrontIsNotAPress() {
    assertEquals(
      IntentResponse.Show,
      respondTo(home = false, open = null, wasInFront = true, playing = false),
    )
  }
}
