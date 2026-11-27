package dev.sargunv.handheld.launch

import android.util.Log
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.BeforeLaunch
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.SaveSync
import dev.sargunv.handheld.data.saves.SaveSyncResult
import dev.sargunv.handheld.data.saves.StandaloneEmulator
import dev.sargunv.handheld.data.saves.beforeLaunch
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.SaveVersion
import java.io.IOException
import kotlinx.coroutines.flow.StateFlow

/**
 * Syncs a game's save with RomM around a play, for standalone emulators and the built-in session
 * alike: before the game starts, which pulls a newer save and asks the player when both copies
 * changed, and after it ends, which sends what the game saved. It says what happened through the
 * notes it's given.
 *
 * @param saveSync the signed-in account's save sync, or null while signed out.
 */
class PlaySaves(private val saveSync: StateFlow<SaveSync?>) {
  /**
   * Syncs [game]'s save before it launches, asking the player through [chooseSave] when both copies
   * changed, with [title] naming the game. Returns false when the player canceled the launch.
   * [emulator] is the standalone emulator that keeps the save, or null for the built-in session.
   */
  suspend fun beforeLaunch(
    title: String,
    game: GameSave,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
    note: (String) -> Unit,
    emulator: StandaloneEmulator?,
  ): Boolean {
    val sync = signedIn() ?: return true
    val outcome =
      try {
        sync.beforeLaunch(
          game,
          emulator,
          chooseSave = { result ->
            chooseSave(
              SaveConflict(
                title,
                SaveVersion(result.device.modifiedAt, result.device.sizeBytes),
                SaveVersion(result.server.updatedAt, result.server.fileSizeBytes),
              ),
            )
          },
        )
      } catch (e: RommException) {
        note(failed(e))
        return true
      } catch (e: IOException) {
        note(failed(e))
        return true
      }
    return when (outcome) {
      is BeforeLaunch.Synced -> {
        describe(outcome.result, note)
        true
      }
      is BeforeLaunch.Skipped -> {
        note(
          if (outcome.result is SaveSyncResult.Conflict) {
            chooseLater("Played this device's save")
          } else {
            "Played this device's save · RomM's newer save comes at the next play"
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
          if (emulator?.close != null) {
            "Launch canceled · Quit the game to get RomM's newer save"
          } else {
            // Without the root service, only a stopped app or a restart shows it quit.
            "Launch canceled · Force stop the emulator to get RomM's newer save"
          },
        )
        false
      }
      BeforeLaunch.CloseFailed -> {
        note("Launch canceled · Emulator didn't close")
        false
      }
    }
  }

  /**
   * Syncs [game]'s save after the game, which sends what it saved. It never pulls: a newer save on
   * RomM waits for the next launch rather than replacing the device's, since the game may still be
   * running, or still writing its save, and would write over a pulled one.
   */
  suspend fun afterPlay(game: GameSave, note: (String) -> Unit) {
    val sync = signedIn() ?: return
    val result =
      try {
        sync.push(game)
      } catch (e: RommException) {
        return note(failed(e))
      } catch (e: IOException) {
        return note(failed(e))
      }
    when (result) {
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
  suspend fun push(game: GameSave): String {
    val sync = signedIn() ?: return "Not signed in"
    val result =
      try {
        sync.push(game)
      } catch (e: RommException) {
        return failed(e)
      } catch (e: IOException) {
        return failed(e)
      }
    return when (result) {
      SaveSyncResult.NoSave -> "No save yet"
      is SaveSyncResult.Pushed -> "Sent to RomM"
      is SaveSyncResult.ServerNewer -> SERVER_NEWER
      is SaveSyncResult.Conflict -> conflictLater(result)
      is SaveSyncResult.UpToDate -> "Already on RomM"
      // A push never writes to the device or settles a conflict.
      is SaveSyncResult.Pulled,
      is SaveSyncResult.NoFolder,
      is SaveSyncResult.Resolved -> "Save synced"
    }
  }

  private fun signedIn(): SaveSync? =
    saveSync.value
      ?: run {
        Log.i(TAG, "Not signed in, so the save didn't sync")
        null
      }

  private fun conflictLater(conflict: SaveSyncResult.Conflict) =
    chooseLater(
      if (conflict.reverted) "Emulator restored the old save" else "Save changed on RomM too",
    )

  private fun chooseLater(label: String) = "$label · Choose at the next play"

  // A failed sync writes nothing, and its record stays as it was, so the next sync tries again.
  private fun failed(e: Exception) = "Save sync failed · ${e.message}"

  private fun describe(result: SaveSyncResult, note: (String) -> Unit) {
    when (result) {
      is SaveSyncResult.Pulled ->
        note(
          if (result.previous != null) "Got RomM's newer save · Old save in the save history"
          else "Got RomM's newer save",
        )
      is SaveSyncResult.NoFolder -> note("RomM's save not placed · Emulator has no save folder yet")
      is SaveSyncResult.Resolved -> {
        val kept = if (result.kept == SaveCopy.Device) "this device's" else "RomM's"
        Log.i(TAG, "Kept $kept save; the other is in the save history at ${result.history}")
        result.uploadError?.let {
          note("Kept $kept save · Other copy not sent: $it")
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
    const val SERVER_NEWER = "Save not sent · RomM's copy is newer"
  }
}
