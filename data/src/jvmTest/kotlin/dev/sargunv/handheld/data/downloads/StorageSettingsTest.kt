package dev.sargunv.handheld.data.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageSettingsTest {
  private val own = StorageTarget("/storage/emulated/0", "Device", "primary")
  private val card = StorageTarget("/storage/AAAA-1111", "SD card", "AAAA-1111")
  private val other = StorageTarget("/storage/BBBB-2222", "Other card", "BBBB-2222")

  private fun choose(
    mounted: List<StorageTarget>,
    settings: StorageSettings = StorageSettings(),
    bytes: Long = 10,
    free: Map<StorageTarget, Long> = mounted.associateWith { 100L },
  ) = chooseStorage(mounted, settings, bytes) { free.getValue(it) }

  @Test
  fun byDefaultEveryStorageIsOnInTheDevicesOrder() {
    val settings = StorageSettings()
    assertEquals(listOf(own, card), settings.enabled(listOf(own, card)))
    assertEquals(own, choose(listOf(own, card)))
  }

  @Test
  fun followsThePlayersOrder() {
    val settings = StorageSettings().moveUp(card, listOf(own, card))
    assertEquals(listOf("AAAA-1111", "primary"), settings.order)
    assertEquals(listOf(card, own), settings.arrange(listOf(own, card)))
    assertEquals(card, choose(listOf(own, card), settings))
  }

  @Test
  fun skipsStorageThatsOff() {
    val settings = StorageSettings().turnOff(own, listOf(own, card))!!
    assertEquals(card, choose(listOf(own, card), settings))
  }

  @Test
  fun fallsThroughToTheNextStorageThatsOnWithRoom() {
    val mounted = listOf(own, card, other)
    val settings = StorageSettings(off = setOf(card.id))
    val free = mapOf(own to 5L, card to 100L, other to 50L)
    assertEquals(other, choose(mounted, settings, bytes = 10, free = free))
  }

  @Test
  fun saysWhichStorageHasTheMostRoomWhenNoneFits() {
    val mounted = listOf(own, card, other)
    val settings = StorageSettings(off = setOf(other.id))
    val free = mapOf(own to 5L, card to 8L, other to 1000L)
    val error =
      assertFailsWith<DownloadException> { choose(mounted, settings, bytes = 10, free = free) }
    assertTrue("SD card" in error.message!!, error.message)
  }

  @Test
  fun storageThatIsntMountedKeepsItsPlaceAndSetting() {
    val settings = StorageSettings(order = listOf(card.id, other.id, own.id), off = setOf(other.id))
    // With the other card out, the rest keep their order.
    assertEquals(listOf(card, own), settings.arrange(listOf(own, card)))
    // Moving among what's mounted keeps the missing card's place and setting.
    val moved = settings.moveDown(card, listOf(own, card))
    assertEquals(listOf(own.id, other.id, card.id), moved.order)
    assertEquals(setOf(other.id), moved.off)
    assertEquals(listOf(own, other, card), moved.arrange(listOf(own, card, other)))
    assertEquals(listOf(own, card), moved.enabled(listOf(own, card, other)))
  }

  @Test
  fun aNewlySeenStorageGoesLastAndOn() {
    val settings = StorageSettings(order = listOf(card.id, own.id))
    assertEquals(listOf(card, own, other), settings.enabled(listOf(own, card, other)))
  }

  @Test
  fun refusesToTurnOffTheLastStorageThatsOn() {
    assertNull(StorageSettings().turnOff(own, listOf(own)))
    val cardOff = StorageSettings(off = setOf(card.id))
    assertNull(cardOff.turnOff(own, listOf(own, card)))
    // A card that's on but out doesn't count, since nothing can go to it now.
    assertNull(StorageSettings(order = listOf(own.id, card.id)).turnOff(own, listOf(own)))
    assertEquals(setOf(own.id), StorageSettings().turnOff(own, listOf(own, card))?.off)
  }

  @Test
  fun failsClearlyWhenNoStorageThatsOnIsMounted() {
    val settings = StorageSettings(off = setOf(own.id))
    val error = assertFailsWith<DownloadException> { choose(listOf(own), settings) }
    assertEquals("Download storage not inserted", error.message)
    assertFailsWith<DownloadException> { choose(emptyList()) }
  }

  @Test
  fun movingTheOnlyStorageDoesNothing() {
    assertEquals(StorageSettings(), StorageSettings().moveUp(own, listOf(own)))
    assertEquals(StorageSettings(), StorageSettings().moveDown(own, listOf(own)))
  }
}
