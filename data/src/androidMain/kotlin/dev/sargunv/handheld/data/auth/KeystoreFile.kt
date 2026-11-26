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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * A value kept in [file] as JSON, encrypted with an AES key that lives in the Android Keystore and
 * never leaves it. Only this app can use the key, so a copy of the file is useless elsewhere.
 *
 * A file the key can't decrypt, such as one restored from a backup onto another device, reads as
 * missing.
 */
internal class KeystoreFile<T : Any>(
  private val file: File,
  private val serializer: KSerializer<T>,
  private val key: () -> SecretKey,
) {
  private val mutex = Mutex()

  suspend fun load(): T? = io {
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
    json.decodeFromString(serializer, plain.decodeToString())
  }

  suspend fun save(value: T) = io {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val plain = json.encodeToString(serializer, value).encodeToByteArray()
    // The Keystore picks the IV; it goes first, so the file reads back on its own.
    val bytes = cipher.iv + cipher.doFinal(plain)
    file.parentFile?.mkdirs()
    // Write a sibling and move it over the old file, so a crash never leaves half a file.
    val temp = File(file.path + ".tmp")
    temp.writeBytes(bytes)
    check(temp.renameTo(file)) { "Couldn't replace $file" }
  }

  suspend fun clear() {
    io { file.delete() }
  }

  private suspend fun <R> io(block: () -> R): R =
    withContext(Dispatchers.IO) { mutex.withLock { block() } }

  companion object {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PROVIDER = "AndroidKeyStore"

    private val json = Json { ignoreUnknownKeys = true }

    /** The Keystore's key named [alias], made the first time it's needed. */
    fun keystoreKey(alias: String): SecretKey {
      val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
      val existing = keyStore.getKey(alias, null) as SecretKey?
      if (existing != null) return existing
      val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
      generator.init(
        KeyGenParameterSpec.Builder(
            alias,
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
