package dev.sargunv.handheld.launch

import android.content.Context
import android.util.Log
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.BeforeLaunch
import dev.sargunv.handheld.data.saves.EmulatorState
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.SaveSync
import dev.sargunv.handheld.data.saves.SaveSyncResult
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.data.saves.beforeLaunch
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.SaveVersion
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Syncs a game's save with RomM around a play, for standalone emulators and the built-in session
 * alike: before the game starts, which pulls a newer save and asks the player when both copies
 * changed, and after it ends, which sends what the game saved. It says what happened through the
 * notes it's given.
 */
class PlaySaves(private val context: Context, private val credentials: CredentialStore) {
  // A sync while the game runs and the one after it share the save's records, so one waits for
  // the other.
  private val lock = Mutex()

  /**
   * Syncs [game]'s save before it launches, asking the player through [chooseSave] when both copies
   * changed, with [title] naming the game. Returns false when the player canceled the launch.
   *
   * When [emulator] may still hold the game, a pull asks [askStillRunning] first, which offers to
   * close it with [close] when [canClose]. See
   * [beforeLaunch][dev.sargunv.handheld.data.saves.beforeLaunch].
   */
  suspend fun beforeLaunch(
    title: String,
    game: GameSave,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
    note: (String) -> Unit,
    emulator: EmulatorState,
    canClose: Boolean,
    askStillRunning: suspend (canClose: Boolean) -> StillRunningChoice,
    close: suspend () -> Boolean,
  ): Boolean =
    // Nothing else syncs before the game starts, so the lock holds through the questions.
    withSync(
      signedOut = true,
      failure = {
        note(it)
        true
      },
    ) { sync ->
      val outcome =
        sync.beforeLaunch(
          game,
          emulator,
          canClose,
          chooseSave = { result ->
            chooseSave(
              SaveConflict(
                title,
                SaveVersion(result.device.modifiedAt, result.device.sizeBytes),
                SaveVersion(result.server.updatedAt, result.server.fileSizeBytes),
              ),
            )
          },
          askStillRunning,
          close,
        )
      when (outcome) {
        is BeforeLaunch.Synced -> {
          describe(outcome.result, note)
          true
        }
        is BeforeLaunch.Skipped -> {
          note(
            if (outcome.result is SaveSyncResult.Conflict) {
              "Played this device's save. Choose between it and RomM's at the next play."
            } else {
              "Played this device's save. RomM's newer save waits for the next play."
            },
          )
          true
        }
        BeforeLaunch.Canceled -> {
          Log.i(TAG, "Launch canceled; both saves are as they were")
          false
        }
        BeforeLaunch.QuitFirst -> {
          note(
            if (canClose) {
              "Launch canceled. Quit the game in the emulator to get RomM's newer save."
            } else {
              // Without the root service, only a stopped app or a restart shows it quit.
              "Launch canceled. To get RomM's newer save, quit the game, then force stop the " +
                "emulator or restart the device."
            },
          )
          false
        }
        BeforeLaunch.CloseFailed -> {
          note("Launch canceled: couldn't close the emulator. Both saves are unchanged.")
          false
        }
      }
    }

  /**
   * Syncs [game]'s save after the game, which sends what it saved. It never pulls: a newer save on
   * RomM waits for the next launch rather than replacing the device's, since the game may still be
   * running, or still writing its save, and would write over a pulled one.
   */
  suspend fun afterPlay(game: GameSave, note: (String) -> Unit) =
    withSync(signedOut = Unit, failure = note) { sync ->
      when (val result = sync.push(game)) {
        // The conflict screen comes before a launch, so the choice waits for the next one.
        is SaveSyncResult.Conflict -> note(conflictLater(result))
        is SaveSyncResult.ServerNewer -> note(SERVER_NEWER)
        else -> describe(result, note)
      }
    }

  /**
   * Sends [game]'s save to RomM while the game runs, and says what happened. It never pulls, since
   * the emulator would write over a pulled save.
   */
  suspend fun push(game: GameSave): String =
    withSync(signedOut = "Can't sync: not signed in.", failure = { it }) { sync ->
      when (val result = sync.push(game)) {
        SaveSyncResult.NoSave -> "No save yet."
        is SaveSyncResult.Pushed -> "Sent the save to RomM."
        is SaveSyncResult.ServerNewer -> SERVER_NEWER
        is SaveSyncResult.Conflict -> conflictLater(result)
        is SaveSyncResult.UpToDate -> "RomM already has this save."
        // A push never writes to the device or settles a conflict.
        is SaveSyncResult.Pulled,
        is SaveSyncResult.NoFolder,
        is SaveSyncResult.Resolved -> "Synced the save."
      }
    }

  /**
   * Runs [block] with the signed-in server's [SaveSync] while holding the lock, or returns
   * [signedOut] when nobody is signed in. When the sync fails, [failure] hears what to tell the
   * player.
   */
  private suspend fun <T> withSync(
    signedOut: T,
    failure: (String) -> T,
    block: suspend (SaveSync) -> T,
  ): T {
    val signedIn =
      credentials.load()
        ?: run {
          Log.i(TAG, "Not signed in, so the save didn't sync")
          return signedOut
        }
    return RommClient(signedIn.baseUrl, signedIn.token).use { client ->
      try {
        val sync =
          SaveSync(
            client,
            records = recordsDir(signedIn.baseUrl, client.me().username),
            history = saveHistory(context),
            staging = File(context.cacheDir, "save-staging"),
          )
        lock.withLock { block(sync) }
      } catch (e: RommException) {
        failure(failed(e))
      } catch (e: IOException) {
        failure(failed(e))
      }
    }
  }

  /**
   * Where save sync keeps its records for [username]'s account on the server at [baseUrl], apart
   * from every other account's, so one account's record never decides what another's save replaces.
   */
  private fun recordsDir(baseUrl: String, username: String): File {
    val account = "$baseUrl\n$username"
    val digest = MessageDigest.getInstance("SHA-256").digest(account.encodeToByteArray())
    val key = digest.copyOf(8).toHexString()
    return File(context.filesDir, "save-sync/$key")
  }

  private fun conflictLater(conflict: SaveSyncResult.Conflict) =
    if (conflict.reverted) {
      "Save not sent: the emulator wrote back the save from before RomM's. Choose which to " +
        "keep at the next play."
    } else {
      "The save changed on RomM during the game too. Choose which to keep at the next play."
    }

  // A failed sync writes nothing, and its record stays as it was, so the next sync tries again.
  private fun failed(e: Exception) =
    "Couldn't sync the save: ${e.message}. It stays on this device and syncs at the next play."

  private fun describe(result: SaveSyncResult, note: (String) -> Unit) {
    when (result) {
      is SaveSyncResult.Pulled ->
        note(
          "Got RomM's newer save." +
            if (result.previous != null) " The device's old save is in the save history." else "",
        )
      is SaveSyncResult.NoFolder ->
        note("RomM's save stays on RomM. The emulator hasn't made its save folder yet.")
      is SaveSyncResult.Resolved -> {
        val kept = if (result.kept == SaveCopy.Device) "this device's" else "RomM's"
        Log.i(TAG, "Kept $kept save; the other is in the save history at ${result.history}")
        result.uploadError?.let {
          note("Kept $kept save. The other is in the save history but couldn't go to RomM: $it")
        }
      }
      SaveSyncResult.NoSave,
      is SaveSyncResult.UpToDate,
      is SaveSyncResult.Pushed,
      is SaveSyncResult.Conflict,
      is SaveSyncResult.ServerNewer -> Log.i(TAG, "Synced the save: $result")
    }
  }

  private companion object {
    const val TAG = "PlaySaves"
    const val SERVER_NEWER = "RomM has a newer save. It comes to this device at the next play."
  }
}

/** Where save sync keeps the copies it replaced, for every account. */
fun saveHistory(context: Context): File = File(context.filesDir, "save-history")
