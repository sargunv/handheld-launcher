package dev.sargunv.handheld.data.auth

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * The store's file format and encryption, with a software key standing in for the Keystore's, which
 * only exists on a device.
 */
class KeystoreCredentialStoreTest {
  private val dir = Files.createTempDirectory("keystore-store").toFile()
  private val file = File(dir, "credentials")
  private val credentials =
    RommCredentials("https://romm.example.com", "rmm_secret", "d1", listOf("roms.read"), null)

  private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  @Test
  fun roundTripsEncrypted() = runTest {
    val key = aesKey()
    val store = KeystoreCredentialStore(file) { key }
    try {
      assertNull(store.load())
      store.save(credentials)

      assertEquals(credentials, KeystoreCredentialStore(file) { key }.load())
      assertFalse("rmm_secret" in file.readBytes().decodeToString())

      store.clear()
      assertNull(store.load())
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun readsAsSignedOutWithAnotherKey() = runTest {
    try {
      val key = aesKey()
      KeystoreCredentialStore(file) { key }.save(credentials)
      val other = aesKey()
      assertNull(KeystoreCredentialStore(file) { other }.load())
    } finally {
      dir.deleteRecursively()
    }
  }
}
