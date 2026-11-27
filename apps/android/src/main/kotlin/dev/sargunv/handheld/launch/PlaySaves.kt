package dev.sargunv.handheld.launch

import android.content.Context
import android.util.Log
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.romm.RommSession
import dev.sargunv.handheld.data.saves.BeforeLaunch
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.SaveSync
import dev.sargunv.handheld.data.saves.SaveSyncResult
import dev.sargunv.handheld.data.saves.StandaloneEmulator
import dev.sargunv.handheld.data.saves.beforeLaunch
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.SaveVersion
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Syncs a game's save with RomM around a play, for standalone emulators and the built-in session
 * alike: before the game starts, which pulls a newer save and asks the player when both copies
 * changed, and after it ends, which sends what the game saved. It says what happened through the
 * notes it's given.
 */
class PlaySaves(private val context: Context, private val romm: RommSession) {
  // A sync while the game runs and the one after it share the save's records, so one waits for
  // the other.
  private val lock = Mutex()

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
      when (outcome) {
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
    withSync(signedOut = "Not signed in", failure = { it }) { sync ->
      when (val result = sync.push(game)) {
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
    val account =
      romm.account
        ?: run {
          Log.i(TAG, "Not signed in, so the save didn't sync")
          return signedOut
        }
    val signedIn = account.credentials
    return try {
      val sync =
        SaveSync(
          account.client,
          records = recordsDir(signedIn.baseUrl, signedIn.userId),
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

  /**
   * Where save sync keeps its records for user [userId] on the server at [baseUrl], apart from
   * every other account's, so one account's record never decides what another's save replaces.
   */
  private fun recordsDir(baseUrl: String, userId: Long): File =
    File(context.filesDir, "save-sync/${URLEncoder.encode(baseUrl, "UTF-8")}/$userId")

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

/** Where save sync keeps the copies it replaced, for every account. */
fun saveHistory(context: Context): File = File(context.filesDir, "save-history")
