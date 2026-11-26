package dev.sargunv.handheld.launch

import android.content.Context
import android.util.Log
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.auth.RommCredentials
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
 * changed, and after it ends, which sends what the game saved. It says what happened in plain
 * words, through the notes it's given.
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
              "Played this device's save. RomM's save stays as it is, and you'll choose which " +
                "to keep the next time you play."
            } else {
              "Played this device's save. RomM's newer save stays there for the next time " +
                "you play."
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
              "Launch canceled. Quit the game from the emulator's own menu, then play again to " +
                "get the newer save from RomM."
            } else {
              // Without the root service, only a stopped app or a restart shows it quit.
              "Launch canceled. Quit the game from the emulator's own menu. This device can't " +
                "tell when it has quit, so to get the newer save from RomM, also restart the " +
                "device or use Force stop in the emulator's app info, then play again."
            },
          )
          false
        }
        BeforeLaunch.CloseFailed -> {
          note(
            "Couldn't close the emulator, so the game didn't start. Both saves are as they " +
              "were.",
          )
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
        is SaveSyncResult.ServerNewer ->
          note("RomM has a newer save, which comes to this device the next time you play.")
        else -> describe(result, note)
      }
    }

  /**
   * Sends [game]'s save to RomM while the game runs, and says what happened. It never pulls, since
   * the emulator would write over a pulled save.
   */
  suspend fun push(game: GameSave): String =
    withSync(signedOut = "Not signed in, so the save can't sync.", failure = { it }) { sync ->
      when (val result = sync.push(game)) {
        SaveSyncResult.NoSave -> "There's no save yet, so there's nothing to sync."
        is SaveSyncResult.Pushed -> "Sent the save to RomM."
        is SaveSyncResult.ServerNewer ->
          "RomM has a newer save, which comes to this device the next time you play."
        is SaveSyncResult.Conflict -> conflictLater(result)
        is SaveSyncResult.UpToDate -> "RomM already has this save."
        // A push never writes to the device or settles a conflict.
        is SaveSyncResult.Pulled,
        is SaveSyncResult.NoFolder,
        is SaveSyncResult.Resolved -> "The save synced."
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
      val sync =
        SaveSync(
          client,
          records = recordsDir(signedIn),
          history = saveHistory(context),
          staging = File(context.cacheDir, "save-staging"),
        )
      try {
        lock.withLock { block(sync) }
      } catch (e: RommException) {
        failure(failed(e))
      } catch (e: IOException) {
        failure(failed(e))
      }
    }
  }

  /**
   * Where save sync keeps its records for [signedIn]'s server and sign-in, apart from every other
   * account's, so one account's record never decides what another account's save replaces.
   */
  private fun recordsDir(signedIn: RommCredentials): File {
    val account = "${signedIn.baseUrl}\n${signedIn.token}"
    val digest = MessageDigest.getInstance("SHA-256").digest(account.encodeToByteArray())
    val key = digest.take(8).joinToString("") { "%02x".format(it) }
    return File(context.filesDir, "save-sync/$key")
  }

  private fun conflictLater(conflict: SaveSyncResult.Conflict) =
    if (conflict.reverted) {
      "The emulator wrote back the save this device had before it got RomM's newer one, so it " +
        "wasn't sent. You'll choose which to keep the next time you play."
    } else {
      "The save changed on RomM during the game too, so you'll choose which to keep the next " +
        "time you play."
    }

  // A failed sync writes nothing, and its record stays as it was, so the next sync tries again.
  private fun failed(e: Exception) =
    "Couldn't sync the save with RomM: ${e.message}. The save on this device stays as it is, and " +
      "syncs the next time you play."

  private fun describe(result: SaveSyncResult, note: (String) -> Unit) {
    when (result) {
      is SaveSyncResult.Pulled ->
        note(
          "Got the newer save from RomM." +
            if (result.previous != null) " The one this device had is in the save history." else "",
        )
      is SaveSyncResult.NoFolder ->
        note(
          "RomM has a save for this game, but the emulator hasn't made its save folder yet, so " +
            "the save stays on RomM for now.",
        )
      is SaveSyncResult.Resolved -> {
        val kept = if (result.kept == SaveCopy.Device) "this device's" else "RomM's"
        Log.i(TAG, "Kept $kept save; the other is in the save history at ${result.history}")
        result.uploadError?.let {
          note(
            "Kept $kept save. The other copy is in the save history on this device, but couldn't " +
              "go to RomM: $it",
          )
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
  }
}

/** Where save sync keeps the copies it replaced, for every account. */
fun saveHistory(context: Context): File = File(context.filesDir, "save-history")
