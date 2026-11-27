package dev.sargunv.handheld.achievements

import co.touchlab.kermit.Logger
import dev.sargunv.handheld.data.achievements.RetroAchievementsClient
import dev.sargunv.handheld.session.AchievementFailure
import dev.sargunv.handheld.session.AchievementStatus
import dev.sargunv.handheld.session.AchievementsHttp
import dev.sargunv.handheld.session.AchievementsRequest
import dev.sargunv.handheld.session.GameSession
import dev.sargunv.handheld.ui.play.AchievementItem
import dev.sargunv.handheld.ui.play.GameAchievements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Sends the session's RetroAchievements requests with the app's HTTP client, on [scope], and hands
 * each response back to the session once it has started.
 */
internal class SessionAchievementsHttp(
  private val client: RetroAchievementsClient,
  private val scope: CoroutineScope,
  private val session: Deferred<GameSession>,
) : AchievementsHttp {
  override fun request(id: ULong, request: AchievementsRequest) {
    scope.launch(Dispatchers.IO) {
      val response = client.send(request.url, request.postData, request.contentType)
      if (response == null) log.w { "RetroAchievements is out of reach" }
      // The first requests go out while the session is starting.
      session
        .await()
        .achievementsResponse(id, response?.status?.toUShort(), response?.body ?: ByteArray(0))
    }
  }
}

private val log = Logger.withTag("SessionAchievements")

/** What the player reads when the session's achievements fail. */
internal fun AchievementFailure.message(): String =
  when (this) {
    AchievementFailure.SignInExpired -> "Sign-in expired · Sign in again in Settings"
    is AchievementFailure.SignIn -> "Sign-in failed · $detail"
    is AchievementFailure.LoadGame -> "Achievements unavailable · $detail"
  }

/** The session's achievements as the quick menu shows them. */
internal fun AchievementStatus.toGameAchievements(): GameAchievements =
  when (this) {
    AchievementStatus.Off -> GameAchievements.Off
    AchievementStatus.Loading -> GameAchievements.Loading
    AchievementStatus.NoSet -> GameAchievements.NoSet
    is AchievementStatus.Failed -> GameAchievements.Failed(reason.message())
    is AchievementStatus.Ready ->
      GameAchievements.Ready(
        game = game,
        hardcore = hardcore,
        items =
          achievements.map {
            AchievementItem(
              id = it.id.toLong(),
              title = it.title,
              description = it.description,
              points = it.points.toInt(),
              unlocked = it.unlocked,
              progress = it.progress,
            )
          },
      )
  }
