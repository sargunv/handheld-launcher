package dev.sargunv.handheld.data.achievements

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** The account's file, with a software key standing in for the Keystore's. */
class KeystoreRetroAchievementsStoreTest {
  private val dir = Files.createTempDirectory("ra-store").toFile()
  private val file = File(dir, "retroachievements")
  private val account = RetroAchievementsAccount("player", "ra_secret_token")

  private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  @Test
  fun keepsTheTokenEncrypted() = runTest {
    val key = aesKey()
    val store = KeystoreRetroAchievementsStore(file) { key }
    try {
      assertNull(store.load())
      store.save(account)
      assertEquals(account, KeystoreRetroAchievementsStore(file) { key }.load())
      assertFalse("ra_secret_token" in file.readBytes().decodeToString())
      store.clear()
      assertNull(store.load())
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun neverPrintsTheToken() {
    assertFalse("ra_secret_token" in account.toString())
  }
}
