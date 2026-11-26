package dev.sargunv.handheld.data.saves

import dev.sargunv.handheld.data.romm.RommException
import java.io.IOException

/**
 * What the player chose when RomM has a newer save for a game that an emulator may still hold in
 * memory, which it would write over the pulled save when it resumes or closes.
 */
enum class StillRunningChoice {
  /** Cancel the launch, so the player can quit the emulator from its own menu first. */
  QuitFirst,

  /** Launch with the device's save, and leave RomM's where it is for now. */
  PlayDeviceSave,

  /** Close the emulator now, then pull the save and launch. */
  CloseNow,
}

/** What [beforeLaunch] did. */
sealed interface BeforeLaunch {
  /** The save synced as [result] says, and the game can launch. */
  data class Synced(val result: SaveSyncResult) : BeforeLaunch

  /**
   * RomM's save stayed where it is, as the player chose, since the emulator may still hold the
   * game. [result] is what the sync found, which wasn't acted on. The game can launch.
   */
  data class Skipped(val result: SaveSyncResult) : BeforeLaunch

  /** The player canceled the launch at the save conflict. Nothing changed. */
  data object Canceled : BeforeLaunch

  /** The player canceled the launch to quit the emulator from its own menu. Nothing changed. */
  data object QuitFirst : BeforeLaunch

  /** The emulator couldn't be closed, so the launch stopped. Nothing changed. */
  data object CloseFailed : BeforeLaunch
}

/**
 * Syncs [game]'s save before it launches, when [emulator] is what's known about the emulator that
 * keeps it.
 *
 * When the emulator may still hold the game, it never pulls without asking: a newer save on RomM,
 * or the player choosing RomM's copy in a conflict, asks [askStillRunning] first, which offers to
 * close the emulator when [canClose]. [close] closes it and returns whether it's gone. When the
 * emulator may be running with another game, a pull goes ahead but remembers the copy it replaced,
 * for [SaveSync.sync]'s check, unless the save is on shared PS2 cards, which any game holds.
 *
 * @param chooseSave asks which copy to keep when both changed. Null cancels the launch.
 * @throws RommException when the server can't be reached or refuses a call.
 * @throws IOException when a save file can't be read or written.
 */
suspend fun SaveSync.beforeLaunch(
  game: GameSave,
  emulator: EmulatorState,
  canClose: Boolean,
  chooseSave: suspend (SaveSyncResult.Conflict) -> SaveCopy?,
  askStillRunning: suspend (canClose: Boolean) -> StillRunningChoice,
  close: suspend () -> Boolean,
): BeforeLaunch {
  val mayRun = emulator is EmulatorState.MayBeRunning
  // Shared PS2 cards hold every game's saves, so an emulator running any game holds them.
  val holds =
    emulator is EmulatorState.MayBeRunning && (emulator.holds(game.romId) || game.cards != null)

  // Pulls with [pull], asking first when the emulator holds the game.
  suspend fun guarded(found: SaveSyncResult, pull: suspend () -> BeforeLaunch): BeforeLaunch {
    if (!holds) return pull()
    return when (askStillRunning(canClose)) {
      StillRunningChoice.QuitFirst -> BeforeLaunch.QuitFirst
      StillRunningChoice.PlayDeviceSave -> BeforeLaunch.Skipped(found)
      StillRunningChoice.CloseNow -> if (canClose && close()) pull() else BeforeLaunch.CloseFailed
    }
  }

  // While the emulator may hold the game, the first look writes nothing to the device.
  return when (val found = if (holds) push(game) else sync(game, emulatorMayRun = mayRun)) {
    is SaveSyncResult.ServerNewer ->
      guarded(found) {
        // The emulator is closed by now, so this looks again as if it never ran.
        beforeLaunch(
          game,
          EmulatorState.NotRunning("closed"),
          canClose = false,
          chooseSave,
          askStillRunning,
          close,
        )
      }
    is SaveSyncResult.Conflict -> {
      when (chooseSave(found) ?: return BeforeLaunch.Canceled) {
        SaveCopy.Device -> BeforeLaunch.Synced(resolve(game, found, SaveCopy.Device))
        SaveCopy.Server ->
          guarded(found) {
            BeforeLaunch.Synced(
              resolve(game, found, SaveCopy.Server, emulatorMayRun = mayRun && !holds),
            )
          }
      }
    }
    else -> BeforeLaunch.Synced(found)
  }
}

/**
 * Syncs [game]'s save once the player is back from the game: it sends what the game saved, and
 * never writes to the device. The emulator may still be running, or still writing its save as it
 * goes to the background, and would write over a pulled save, so a newer save on RomM waits for
 * [beforeLaunch].
 *
 * @throws RommException when the server can't be reached or refuses a call.
 * @throws IOException when the save file can't be read.
 */
suspend fun SaveSync.afterReturn(game: GameSave): SaveSyncResult = push(game)
