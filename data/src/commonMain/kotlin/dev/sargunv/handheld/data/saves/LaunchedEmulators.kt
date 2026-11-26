package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.data.cores.JsonFile
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Which boot of the device this is, for telling whether it restarted since. */
@Serializable
data class Boot(
  /** The device's boot count, when it keeps one. */
  val count: Int?,
  /** When the device booted, in epoch milliseconds, which moves when the clock is changed. */
  val bootedAt: Long,
  /** The time since the device booted, in milliseconds. */
  val sinceBoot: Long,
) {
  /** Whether this boot is a later one than [earlier]. */
  fun isAfter(earlier: Boot): Boolean =
    if (count != null && earlier.count != null) {
      count != earlier.count
    } else {
      // Without a boot count, the time since boot going back, or the boot time moving by more than
      // a clock correction would move it, means the device restarted.
      sinceBoot < earlier.sinceBoot || abs(bootedAt - earlier.bootedAt) > BOOT_TIME_SLACK_MS
    }

  private companion object {
    const val BOOT_TIME_SLACK_MS = 2 * 60 * 1000L
  }
}

/** What the device can tell about an app the launcher started. */
interface EmulatorProbe {
  /** The device's current boot. */
  fun boot(): Boot

  /**
   * Whether [packageName] is stopped: force-stopped, or never started since it was installed.
   * Android sets this only then. An app that quits by itself or that the system kills isn't.
   */
  fun isStopped(packageName: String): Boolean

  /** Whether [packageName] is running, or null when the device can't tell. */
  suspend fun isRunning(packageName: String): Boolean?
}

/** What [LaunchedEmulators.state] knows about an emulator. */
sealed interface EmulatorState {
  /** Why the launcher believes it, in a few words, for the log. */
  val reason: String

  /** It isn't running, or it isn't one the launcher started. */
  data class NotRunning(override val reason: String) : EmulatorState

  /**
   * It may still be running since the launcher started it with the ROM [romId], and hold that
   * game's save in memory, or the device says it's running. The player saying they quit it doesn't
   * count, since nothing can check that.
   */
  data class MayBeRunning(val romId: Long?, override val reason: String) : EmulatorState {
    /**
     * Whether pulling the ROM [romId]'s save now could be undone by the emulator writing the one it
     * holds in memory.
     */
    fun holds(romId: Long): Boolean = this.romId == romId
  }
}

/**
 * The emulators the launcher started that may still be running, one record per app, kept in the
 * file at [path].
 *
 * Android doesn't let an app stop another or see its processes, so an emulator counts as running
 * from the moment the launcher starts it until the device shows it isn't: the app is stopped, the
 * device restarted, or [EmulatorProbe.isRunning] says so.
 */
class LaunchedEmulators(path: Path) {
  private val file = JsonFile(path, MapSerializer(String.serializer(), Launch.serializer()))
  private val mutex = Mutex()

  @Serializable internal data class Launch(val romId: Long?, val boot: Boot)

  /**
   * Notes that the launcher started [packageName] with the ROM [romId]. Returns what it knew
   * before, for [launchFailed].
   */
  suspend fun launched(packageName: String, romId: Long?, probe: EmulatorProbe): Earlier =
    mutex.withLock {
      val launches = read()
      write(launches + (packageName to Launch(romId, probe.boot())))
      Earlier(launches[packageName])
    }

  /**
   * Notes that a launch of [packageName] didn't start, so the game it may still hold is the one it
   * held [before]. With nothing before, it stays counted as running, since a failed launch may have
   * started it anyway.
   */
  suspend fun launchFailed(packageName: String, before: Earlier) = mutex.withLock {
    val earlier = before.launch ?: return@withLock
    write(read() + (packageName to earlier))
  }

  /** What [LaunchedEmulators] knew about an app before a launch. */
  class Earlier internal constructor(internal val launch: Launch?)

  /** Forgets [packageName], once it's known to have stopped. */
  suspend fun stopped(packageName: String) = mutex.withLock { write(read() - packageName) }

  /**
   * Whether [packageName] may still be running, by what [probe] tells. A record the device shows is
   * out of date is dropped.
   */
  suspend fun state(packageName: String, probe: EmulatorProbe): EmulatorState = mutex.withLock {
    val launches = read()
    val launch = launches[packageName] ?: return@withLock EmulatorState.NotRunning("not launched")
    val stopped =
      when {
        probe.boot().isAfter(launch.boot) -> "the device restarted"
        probe.isStopped(packageName) -> "the app is stopped"
        else ->
          when (probe.isRunning(packageName)) {
            false -> "the device says it isn't running"
            true ->
              return@withLock EmulatorState.MayBeRunning(
                launch.romId,
                "the device says it's running",
              )
            null -> null
          }
      }
    if (stopped != null) {
      write(launches - packageName)
      EmulatorState.NotRunning(stopped)
    } else {
      EmulatorState.MayBeRunning(launch.romId, "nothing shows it stopped")
    }
  }

  private fun read(): Map<String, Launch> =
    try {
      file.read().orEmpty()
    } catch (_: Exception) {
      // An unreadable file loses only the records, which next launches write again.
      emptyMap()
    }

  private fun write(launches: Map<String, Launch>) = file.write(launches)
}
