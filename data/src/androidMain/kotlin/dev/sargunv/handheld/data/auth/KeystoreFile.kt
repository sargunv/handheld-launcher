package dev.sargunv.handheld.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.sargunv.handheld.data.FileJson
import dev.sargunv.handheld.data.Vault
import dev.sargunv.handheld.data.writeAtomically
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * A value kept in [file] as JSON, encrypted with an AES key that lives in the Android Keystore and
 * never leaves it. Only this app can use the key, so a copy of the file is useless elsewhere.
 *
 * A file the key can't decrypt, such as one restored from a backup onto another device, or whose
 * JSON doesn't decode, reads as missing.
 */
class KeystoreFile<T : Any>
internal constructor(
  private val file: File,
  private val serializer: KSerializer<T>,
  private val key: () -> SecretKey,
) : Vault<T> {
  /** A file encrypted with the Keystore's key named [alias], made the first time it's needed. */
  constructor(
    file: File,
    serializer: KSerializer<T>,
    alias: String,
  ) : this(file, serializer, { keystoreKey(alias) })

  override fun read(): T? {
    if (!file.exists()) return null
    val bytes = file.readBytes()
    return try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
      val plain = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
      FileJson.decodeFromString(serializer, plain.decodeToString())
    } catch (_: GeneralSecurityException) {
      null
    } catch (_: SerializationException) {
      null
    }
  }

  /** Encrypts and writes [value], throwing an [IOException] when either fails. */
  override fun write(value: T) {
    val plain = FileJson.encodeToString(serializer, value).encodeToByteArray()
    val sealed =
      try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        // The Keystore picks the IV; it goes first, so the file reads back on its own.
        cipher.iv + cipher.doFinal(plain)
      } catch (e: GeneralSecurityException) {
        throw IOException(e.message, e)
      }
    writeAtomically(file, sealed)
  }

  override fun delete() {
    file.delete()
  }
}

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_BYTES = 12
private const val TAG_BITS = 128
private const val PROVIDER = "AndroidKeyStore"

/** The Keystore's key named [alias], made the first time it's needed. */
private fun keystoreKey(alias: String): SecretKey {
  val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
  (keyStore.getKey(alias, null) as SecretKey?)?.let {
    return it
  }
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
