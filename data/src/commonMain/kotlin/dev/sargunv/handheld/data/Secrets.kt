package dev.sargunv.handheld.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where a secret lives between launches: a file encrypted with a key only this app can use, or on
 * desktop a plain [JsonFile]. One that can't be read or decrypted reads as none. Its calls block.
 */
interface Vault<T : Any> {
  fun read(): T?

  fun write(value: T)

  fun delete()

  /** A vault that forgets everything when the process ends. */
  class InMemory<T : Any>(@Volatile private var value: T? = null) : Vault<T> {
    override fun read() = value

    override fun write(value: T) {
      this.value = value
    }

    override fun delete() {
      value = null
    }
  }
}

/** A secret, such as a sign-in's token, read from [vault] the first time it's needed. */
class SecretStore<T : Any>(private val vault: Vault<T>) {
  private val mutex = Mutex()
  private val current = MutableStateFlow<T?>(null)
  private var loaded = false

  /** The secret, or null while there's none, as it's saved and cleared. */
  val value: Flow<T?> = flow {
    load()
    emitAll(current)
  }

  suspend fun load(): T? = locked {
    if (!loaded) current.value = vault.read()
    current.value
  }

  suspend fun save(value: T) = locked {
    vault.write(value)
    current.value = value
  }

  suspend fun clear() = locked {
    vault.delete()
    current.value = null
  }

  private suspend fun <R> locked(block: () -> R): R = mutex.withLock {
    withContext(Dispatchers.IO) { block() }.also { loaded = true }
  }
}
