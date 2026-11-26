package dev.sargunv.handheld.data.achievements

import dev.sargunv.handheld.data.auth.KeystoreFile
import java.io.File
import javax.crypto.SecretKey

/**
 * A [RetroAchievementsStore] that keeps the account in [file], encrypted the way the RomM
 * credentials are: with an AES key that lives in the Android Keystore and never leaves it. A file
 * the key can't decrypt reads as signed out.
 */
class KeystoreRetroAchievementsStore internal constructor(file: File, key: () -> SecretKey) :
  RetroAchievementsStore {
  constructor(file: File) : this(file, { KeystoreFile.keystoreKey(ALIAS) })

  private val store = KeystoreFile(file, RetroAchievementsAccount.serializer(), key)

  override suspend fun load(): RetroAchievementsAccount? = store.load()

  override suspend fun save(account: RetroAchievementsAccount) = store.save(account)

  override suspend fun clear() = store.clear()

  private companion object {
    const val ALIAS = "retroachievements-account"
  }
}
