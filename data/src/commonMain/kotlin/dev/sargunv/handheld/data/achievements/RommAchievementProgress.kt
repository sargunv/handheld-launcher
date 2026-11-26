package dev.sargunv.handheld.data.achievements

import dev.sargunv.handheld.data.romm.RommClient

/**
 * The signed-in user's RetroAchievements progress in a game, as RomM syncs it from
 * RetroAchievements. RomM fetches it when the user refreshes it there, so it may be behind what the
 * user has earned since.
 */
data class RommAchievementProgress(
  /** The achievements earned, in either mode. */
  val unlocked: Int,
  /** The achievements earned in hardcore. */
  val unlockedHardcore: Int,
  /** The achievements the game has. */
  val total: Int,
)

/**
 * The signed-in user's RetroAchievements progress in ROM [romId], read from RomM, or null when
 * there's none to show: the user hasn't linked a RetroAchievements account in RomM, or RomM knows
 * no achievements for the game. It only reads: RomM keeps the progress, and the app never changes
 * it.
 */
suspend fun RommClient.achievementProgress(romId: Long): RommAchievementProgress? {
  val user = me()
  if (user.raUsername.isNullOrBlank()) return null
  val rom = rom(romId)
  val raId = rom.raId ?: return null
  val game = user.raProgression?.results?.find { it.romRaId == raId }
  val total = game?.maxPossible ?: rom.raMetadata?.achievements?.size ?: 0
  if (total <= 0) return null
  return RommAchievementProgress(
    unlocked = game?.numAwarded ?: 0,
    unlockedHardcore = game?.numAwardedHardcore ?: 0,
    total = total,
  )
}
