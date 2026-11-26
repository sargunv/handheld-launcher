package dev.sargunv.handheld.data.auth

import java.io.File
import javax.crypto.SecretKey

/**
 * A [CredentialStore] that keeps the credentials in [file], encrypted with an AES key that lives in
 * the Android Keystore and never leaves it. Only this app can use the key, so a copy of the file is
 * useless elsewhere.
 *
 * A file the key can't decrypt, such as one restored from a backup onto another device, reads as
 * signed out.
 */
class KeystoreCredentialStore internal constructor(file: File, key: () -> SecretKey) :
  CredentialStore {
  constructor(file: File) : this(file, { KeystoreFile.keystoreKey(ALIAS) })

  private val store = KeystoreFile(file, RommCredentials.serializer(), key)

  override suspend fun load(): RommCredentials? = store.load()

  override suspend fun save(credentials: RommCredentials) = store.save(credentials)

  override suspend fun clear() = store.clear()

  private companion object {
    const val ALIAS = "romm-credentials"
  }
}
