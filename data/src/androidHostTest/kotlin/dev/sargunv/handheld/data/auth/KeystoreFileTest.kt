package dev.sargunv.handheld.data.auth

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The file format and encryption, with a software key standing in for the Keystore's, which only
 * exists on a device.
 */
class KeystoreFileTest {
  private val dir = Files.createTempDirectory("keystore-file").toFile()
  private val file = File(dir, "credentials")
  private val credentials = RommCredentials("https://romm.example.com", "rmm_secret", 1, "player")

  @AfterTest fun cleanUp() = check(dir.deleteRecursively())

  private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  private fun vault(key: javax.crypto.SecretKey) =
    KeystoreFile(file, RommCredentials.serializer()) { key }

  @Test
  fun roundTripsEncrypted() {
    val key = aesKey()
    assertNull(vault(key).read())
    vault(key).write(credentials)

    assertEquals(credentials, vault(key).read())
    assertFalse("rmm_secret" in file.readBytes().decodeToString())

    vault(key).delete()
    assertNull(vault(key).read())
  }

  @Test
  fun readsAsMissingWithAnotherKey() {
    vault(aesKey()).write(credentials)
    assertNull(vault(aesKey()).read())
  }
}
