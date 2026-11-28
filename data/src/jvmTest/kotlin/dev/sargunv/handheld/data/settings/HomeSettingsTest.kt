package dev.sargunv.handheld.data.settings

import dev.sargunv.handheld.data.settings.HomeShelf.Collections
import dev.sargunv.handheld.data.settings.HomeShelf.RecentlyAdded
import dev.sargunv.handheld.data.settings.HomeShelf.RecentlyPlayed
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeSettingsTest {
  @Test
  fun showsEveryShelfByDefault() {
    assertEquals(listOf(RecentlyPlayed, Collections, RecentlyAdded), HomeSettings().visible)
  }

  @Test
  fun hidesAndShowsAShelfInItsPlace() {
    val hidden = HomeSettings().hide(Collections)
    assertEquals(listOf(RecentlyPlayed, RecentlyAdded), hidden.visible)
    assertEquals(
      listOf(RecentlyPlayed, Collections, RecentlyAdded),
      hidden.show(Collections).visible,
    )
  }

  @Test
  fun movesAShelfPastItsVisibleNeighbor() {
    val settings = HomeSettings()
    assertEquals(
      listOf(RecentlyPlayed, RecentlyAdded, Collections),
      settings.moveUp(RecentlyAdded, settings.visible).visible,
    )
    assertEquals(
      listOf(Collections, RecentlyPlayed, RecentlyAdded),
      settings.moveDown(RecentlyPlayed, settings.visible).visible,
    )
    // A hidden shelf in between doesn't take a move.
    val hidden = settings.hide(Collections)
    val skipping = hidden.moveDown(RecentlyPlayed, hidden.visible)
    assertEquals(listOf(RecentlyAdded, RecentlyPlayed), skipping.visible)
    assertEquals(
      listOf(RecentlyAdded, Collections, RecentlyPlayed),
      skipping.show(Collections).visible,
    )
    // So doesn't one that's off the screen, such as an empty one.
    assertEquals(
      listOf(RecentlyAdded, Collections, RecentlyPlayed),
      settings.moveDown(RecentlyPlayed, shown = listOf(RecentlyPlayed, RecentlyAdded)).visible,
    )
  }

  @Test
  fun movingPastTheEndsDoesNothing() {
    val settings = HomeSettings()
    assertEquals(settings, settings.moveUp(RecentlyPlayed, settings.visible))
    assertEquals(settings, settings.moveDown(RecentlyAdded, settings.visible))
    val hidden = settings.hide(Collections)
    assertEquals(hidden, hidden.moveUp(Collections, hidden.visible))
  }
}
