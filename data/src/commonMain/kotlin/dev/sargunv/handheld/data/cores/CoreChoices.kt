package dev.sargunv.handheld.data.cores

import dev.sargunv.handheld.domain.emulator.LibretroCore
import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The user's choice of core for each platform, where it differs from the recommended one, kept in a
 * JSON file at [path].
 */
class CoreChoices(path: Path) {
  private val file = JsonFile(path, MapSerializer(String.serializer(), String.serializer()))
  private val mutex = Mutex()

  /** The chosen cores' library names, by platform. */
  suspend fun overrides(): Map<PlatformSlug, String> =
    withContext(Dispatchers.IO) {
      mutex.withLock { file.read().orEmpty().mapKeys { PlatformSlug(it.key) } }
    }

  /** Chooses [core] for [platform], or goes back to the recommended core when it's null. */
  suspend fun choose(platform: PlatformSlug, core: LibretroCore?) =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val current = file.read().orEmpty()
        file.write(
          if (core == null) current - platform.value
          else current + (platform.value to core.library),
        )
      }
    }

  /** The core that runs [platform]: the chosen one, or the recommended one. */
  suspend fun coreFor(platform: PlatformSlug): LibretroCore? =
    LibretroCores.coreFor(platform, overrides())
}
