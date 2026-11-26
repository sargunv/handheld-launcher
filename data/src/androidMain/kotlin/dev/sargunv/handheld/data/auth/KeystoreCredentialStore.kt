package dev.sargunv.handheld.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * A [CredentialStore] that keeps the credentials in [file], encrypted with an AES key that lives in
 * the Android Keystore and never leaves it. Only this app can use the key, so a copy of the file is
 * useless elsewhere.
 *
 * A file the key can't decrypt, such as one restored from a backup onto another device, reads as
 * signed out.
 */
class KeystoreCredentialStore
internal constructor(private val file: File, private val key: () -> SecretKey) : CredentialStore {
  constructor(file: File) : this(file, ::keystoreKey)

  private val mutex = Mutex()

  override suspend fun load(): RommCredentials? = io {
    if (!file.exists()) return@io null
    val bytes = file.readBytes()
    val plain =
      try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
          Cipher.DECRYPT_MODE,
          key(),
          GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES),
        )
        cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
      } catch (e: GeneralSecurityException) {
        return@io null
      }
    json.decodeFromString(RommCredentials.serializer(), plain.decodeToString())
  }

  override suspend fun save(credentials: RommCredentials) = io {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val plain = json.encodeToString(RommCredentials.serializer(), credentials).encodeToByteArray()
    // The Keystore picks the IV; it goes first, so the file reads back on its own.
    val bytes = cipher.iv + cipher.doFinal(plain)
    file.parentFile?.mkdirs()
    // Write a sibling and move it over the old file, so a crash never leaves half a file.
    val temp = File(file.path + ".tmp")
    temp.writeBytes(bytes)
    check(temp.renameTo(file)) { "Couldn't replace $file" }
  }

  override suspend fun clear() {
    io { file.delete() }
  }

  private suspend fun <T> io(block: () -> T): T =
    withContext(Dispatchers.IO) { mutex.withLock { block() } }

  private companion object {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val PROVIDER = "AndroidKeyStore"
    const val ALIAS = "romm-credentials"

    val json = Json { ignoreUnknownKeys = true }

    /** The Keystore's key for the credentials, made the first time it's needed. */
    fun keystoreKey(): SecretKey {
      val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
      val existing = keyStore.getKey(ALIAS, null) as SecretKey?
      if (existing != null) return existing
      val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
      generator.init(
        KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
          )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build(),
      )
      return generator.generateKey()
    }
  }
}
