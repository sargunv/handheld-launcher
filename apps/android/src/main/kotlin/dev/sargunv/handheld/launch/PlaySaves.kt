package dev.sargunv.handheld.launch

import android.content.Context
import dev.sargunv.handheld.data.auth.CredentialStore
import dev.sargunv.handheld.data.romm.RommClient
import dev.sargunv.handheld.data.romm.RommException
import dev.sargunv.handheld.data.saves.BeforeLaunch
import dev.sargunv.handheld.data.saves.EmulatorState
import dev.sargunv.handheld.data.saves.GameSave
import dev.sargunv.handheld.data.saves.SaveCopy
import dev.sargunv.handheld.data.saves.SaveSyncResult
import dev.sargunv.handheld.data.saves.StillRunningChoice
import dev.sargunv.handheld.data.saves.afterReturn
import dev.sargunv.handheld.data.saves.beforeLaunch
import dev.sargunv.handheld.ui.saves.SaveConflict
import dev.sargunv.handheld.ui.saves.SaveVersion
import java.io.IOException
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

  /** A client for the save server, and the account its sync records belong to. */
  suspend fun client(request: PlayRequest): Pair<RommClient, String>? {
    request.saveServer?.let {
      return RommClient(it) to it
    }
    val signedIn = credentials.load() ?: return null
    return RommClient(signedIn.baseUrl, signedIn.token) to "${signedIn.baseUrl}\n${signedIn.token}"
  }

  /**
   * Syncs [game]'s save before it launches, asking the player through [chooseSave] when both copies
   * changed. Returns false when the player canceled the launch.
   *
   * When [emulator] may still hold the game, a pull asks [askStillRunning] first, which offers to
   * close it with [close] when [canClose]. See
   * [beforeLaunch][dev.sargunv.handheld.data.saves.beforeLaunch].
   */
  suspend fun beforeLaunch(
    request: PlayRequest,
    game: GameSave,
    chooseSave: suspend (SaveConflict) -> SaveCopy?,
    note: (String, Boolean) -> Unit,
    emulator: EmulatorState = EmulatorState.NotRunning("the built-in session"),
    canClose: Boolean = false,
    askStillRunning: suspend (canClose: Boolean) -> StillRunningChoice = {
      StillRunningChoice.QuitFirst
    },
    close: suspend () -> Boolean = { false },
  ): Boolean {
    val (client, account) =
      client(request)
        ?: run {
          note("Not signed in, so the save didn't sync", true)
          return true
        }
    client.use {
      val sync = saveSync(context, it, account)
      try {
        // Nothing else syncs before the game starts, so the lock holds through the questions.
        val outcome = lock.withLock {
          sync.beforeLaunch(
            game,
            emulator,
            canClose,
            chooseSave = { result ->
              chooseSave(
                SaveConflict(
                  request.title,
                  SaveVersion(result.device.modifiedAt, result.device.sizeBytes),
                  SaveVersion(result.server.updatedAt, result.server.fileSizeBytes),
                ),
              )
            },
            askStillRunning,
            close,
          )
        }
        when (outcome) {
          is BeforeLaunch.Synced -> describe(outcome.result, note)
          is BeforeLaunch.Skipped ->
            note(
              if (outcome.result is SaveSyncResult.Conflict) {
                "Played this device's save. RomM's save stays as it is, and you'll choose which " +
                  "to keep the next time you play."
              } else {
                "Played this device's save. RomM's newer save stays there for the next time " +
                  "you play."
              },
              false,
            )
          BeforeLaunch.Canceled -> {
            note("Launch canceled; both saves are as they were", true)
            return false
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
              false,
            )
            return false
          }
          BeforeLaunch.CloseFailed -> {
            note(
              "Couldn't close the emulator, so the game didn't start. Both saves are as they " +
                "were.",
              false,
            )
            return false
          }
        }
      } catch (e: RommException) {
        note(failed(e), false)
      } catch (e: IOException) {
        note(failed(e), false)
      }
    }
    return true
  }

  /**
   * Syncs [game]'s save after the game, which sends what it saved. It never pulls: a newer save on
   * RomM waits for the next launch rather than replacing the device's, since the game may still be
   * running, or still writing its save, and would write over a pulled one.
   */
  suspend fun afterPlay(request: PlayRequest, game: GameSave, note: (String, Boolean) -> Unit) {
    val (client, account) =
      client(request)
        ?: run {
          note("Not signed in, so the save didn't sync", true)
          return
        }
    client.use {
      try {
        val sync = saveSync(context, it, account)
        when (val result = lock.withLock { sync.afterReturn(game) }) {
          // The conflict screen comes before a launch, so the choice waits for the next one.
          is SaveSyncResult.Conflict -> note(conflictLater(result), false)
          is SaveSyncResult.ServerNewer ->
            note("RomM has a newer save, which comes to this device the next time you play.", false)
          else -> describe(result, note)
        }
      } catch (e: RommException) {
        note(failed(e), false)
      } catch (e: IOException) {
        note(failed(e), false)
      }
    }
  }

  /**
   * Sends [game]'s save to RomM while the game runs, and says what happened. It never pulls, since
   * the emulator would write over a pulled save.
   */
  suspend fun push(request: PlayRequest, game: GameSave): String {
    val (client, account) = client(request) ?: return "Not signed in, so the save can't sync."
    return client.use {
      try {
        lock.withLock {
          when (val result = saveSync(context, it, account).push(game)) {
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
      } catch (e: RommException) {
        failed(e)
      } catch (e: IOException) {
        failed(e)
      }
    }
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

  private fun describe(result: SaveSyncResult, note: (String, Boolean) -> Unit) {
    when (result) {
      SaveSyncResult.NoSave -> note("No save yet, here or on RomM", true)
      is SaveSyncResult.UpToDate -> note("The save is up to date", true)
      is SaveSyncResult.Pulled ->
        note(
          "Got the newer save from RomM." +
            if (result.previous != null) " The one this device had is in the save history." else "",
          false,
        )
      is SaveSyncResult.Pushed ->
        note("Sent ${result.file} to RomM as save ${result.save.id}", true)
      is SaveSyncResult.NoFolder ->
        note(
          "RomM has a save for this game, but the emulator hasn't made its save folder yet, so " +
            "the save stays on RomM for now.",
          false,
        )
      is SaveSyncResult.Conflict -> note("Both saves changed", true)
      is SaveSyncResult.ServerNewer -> note("RomM's save is newer", true)
      is SaveSyncResult.Resolved -> {
        val kept = if (result.kept == SaveCopy.Device) "this device's" else "RomM's"
        note("Kept $kept save; the other is in the save history at ${result.history}", true)
        result.uploadError?.let {
          note(
            "Kept $kept save. The other copy is in the save history on this device, but couldn't " +
              "go to RomM: $it",
            false,
          )
        }
      }
    }
  }
}
