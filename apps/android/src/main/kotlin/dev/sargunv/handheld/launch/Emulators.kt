package dev.sargunv.handheld.launch

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.sargunv.handheld.data.saves.Boot
import dev.sargunv.handheld.data.saves.EmulatorProbe
import dev.sargunv.handheld.data.saves.EmulatorState
import dev.sargunv.handheld.data.saves.LaunchedEmulators
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path

/**
 * The standalone emulators the launcher started, and what it can tell and do about them on this
 * device. The app holds one, so its records have one writer.
 */
internal class Emulators(private val context: Context) {
  private val launched = LaunchedEmulators(Path(context.filesDir.path, "launched-emulators.json"))

  /** Whether the device has a root service the launcher can close emulators with. */
  val canClose: Boolean
    get() = RootService.instance != null

  /** What the device can tell about emulators, using the root service when [useRoot]. */
  fun probe(useRoot: Boolean): EmulatorProbe =
    DeviceProbe(context, RootService.instance.takeIf { useRoot })

  /** Notes that the launcher is starting [packageName] with the ROM [romId]. */
  suspend fun launched(packageName: String, romId: Long?, probe: EmulatorProbe) =
    launched.launched(packageName, romId, probe)

  /** Notes that a launch of [packageName] didn't start, back to what was known [before] it. */
  suspend fun launchFailed(packageName: String, before: LaunchedEmulators.Earlier) =
    launched.launchFailed(packageName, before)

  /** Whether [packageName] may still be running. */
  suspend fun state(packageName: String, probe: EmulatorProbe): EmulatorState =
    launched.state(packageName, probe).also { Log.i(TAG, "$packageName: $it") }

  /**
   * Closes [packageName] through the root service, and returns whether it's gone. Call it only
   * while the launcher is in front, so the emulator is in the background.
   */
  suspend fun close(packageName: String): Boolean {
    val root = RootService.instance ?: return false
    if (!root.forceStop(packageName)) return false
    // The process takes a moment to go.
    val gone =
      withTimeoutOrNull(3.seconds) {
        while (root.isRunning(packageName) != false) delay(200.milliseconds)
        true
      } ?: false
    Log.i(TAG, "Closed $packageName: ${if (gone) "gone" else "still running"}")
    if (gone) launched.stopped(packageName)
    return gone
  }

  private companion object {
    const val TAG = "Emulators"
  }
}

/** What Android tells about other apps, and the root service when it's given one. */
private class DeviceProbe(private val context: Context, private val root: RootService?) :
  EmulatorProbe {
  override fun boot(): Boot {
    val sinceBoot = SystemClock.elapsedRealtime()
    val count =
      try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
      } catch (_: Settings.SettingNotFoundException) {
        null
      }
    return Boot(count, System.currentTimeMillis() - sinceBoot, sinceBoot)
  }

  override fun isStopped(packageName: String): Boolean =
    try {
      val info = context.packageManager.getApplicationInfo(packageName, 0)
      info.flags and ApplicationInfo.FLAG_STOPPED != 0
    } catch (_: PackageManager.NameNotFoundException) {
      // Uninstalled, so it isn't running.
      true
    }

  override suspend fun isRunning(packageName: String): Boolean? = root?.isRunning(packageName)
}

/**
 * The root service AYN's devices ship, `PServerBinder`, which runs any shell command as root for
 * any app, without a permission or a prompt. The launcher sends it only [isRunningCommand] and
 * [forceStopCommand], and only for emulators it started, while the player has turned on closing
 * emulators.
 *
 * The binder call is ported from Argosy's `PServerExecutor`
 * (https://github.com/rommapp/argosy-launcher), GPL-3.0.
 */
private class RootService(private val binder: IBinder) {
  /** Whether [packageName]'s main process is running, or null when the service can't tell. */
  suspend fun isRunning(packageName: String): Boolean? {
    val output = run(isRunningCommand(packageName)) ?: return null
    return when {
      // pidof prints nothing when no process has the name, which the service sends as no bytes.
      output.isEmpty() -> false
      output.split(Regex("\\s+")).all { pid -> pid.all { it.isDigit() } } -> true
      else -> null
    }
  }

  /** Stops [packageName] at once. Returns whether the service ran the command. */
  suspend fun forceStop(packageName: String): Boolean = run(forceStopCommand(packageName)) != null

  /** Runs [command] and returns what it printed, trimmed, or null when the call failed. */
  private suspend fun run(command: String): String? =
    withContext(Dispatchers.IO) {
      val data = Parcel.obtain()
      val reply = Parcel.obtain()
      try {
        // No interface token: the service reads the command and its flag straight away.
        data.writeStringArray(arrayOf(command, "1"))
        if (!binder.transact(0, data, reply, 0)) return@withContext null
        // A missing reply or "null" is the service failing, not a command that printed nothing.
        val output = reply.createByteArray()?.decodeToString()?.trim()
        Log.i(TAG, "$command: ${output?.length ?: "no"} characters")
        output?.takeIf { it != "null" }
      } catch (e: Exception) {
        Log.w(TAG, "The root service failed at $command", e)
        null
      } finally {
        data.recycle()
        reply.recycle()
      }
    }

  companion object {
    private const val TAG = "RootService"

    /** The device's root service, or null when it has none. */
    val instance: RootService? by lazy {
      try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        (getService.invoke(null, "PServerBinder") as IBinder?)?.let(::RootService)
      } catch (e: ReflectiveOperationException) {
        Log.i(TAG, "No root service: $e")
        null
      }
    }
  }
}

private val PACKAGE_NAME = Regex("[a-zA-Z0-9_.]{1,255}")

/**
 * [packageName], checked to hold only letters, digits, `_` and `.`, so nothing else reaches the
 * root service's shell.
 */
private fun checked(packageName: String): String {
  require(PACKAGE_NAME.matches(packageName)) { "Not a package name: $packageName" }
  return packageName
}

/** Lists the process ids of [packageName]'s main process: none when it isn't running. */
internal fun isRunningCommand(packageName: String) = "pidof ${checked(packageName)}"

/** Stops [packageName] at once, without the app's own exit. */
internal fun forceStopCommand(packageName: String) = "am force-stop ${checked(packageName)}"
