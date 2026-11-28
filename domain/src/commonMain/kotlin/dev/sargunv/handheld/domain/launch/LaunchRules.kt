package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A RomM collection, by its kind and id, as `regular:3` or `smart:3`. */
@Serializable
@JvmInline
value class CollectionId(val value: String) {
  override fun toString(): String = value
}

/**
 * The facts about a game that decide which app launches it. [game] is null for the games of a
 * platform or a collection, which no game's own choice covers.
 */
data class LaunchSubject(
  val game: GameKey?,
  val platform: PlatformSlug,
  val collections: Set<CollectionId> = emptySet(),
)

/**
 * What a rule picks to launch games in: an [App], or [BuiltIn], the launcher's own session. [core]
 * is the libretro core's library name, for the built-in session and apps that load one; null runs
 * the platform's recommended core.
 */
@Serializable
sealed interface LaunchChoice {
  val core: String?

  /** Any installed app, known emulator or not. */
  @Serializable
  @SerialName("app")
  data class App(val packageName: String, override val core: String? = null) : LaunchChoice

  @Serializable
  @SerialName("builtin")
  data class BuiltIn(override val core: String? = null) : LaunchChoice
}

/** A collection's choice of app for its games on [platform]. */
@Serializable
data class CollectionRule(
  val collection: CollectionId,
  val platform: PlatformSlug,
  val choice: LaunchChoice,
)

/**
 * The user's choices of app, from most to least specific, as the Open with screen makes them. A
 * game override beats a collection rule, which beats a platform default. Among [collectionRules],
 * the first that applies to a game wins.
 */
@Serializable
data class LaunchRules(
  val gameOverrides: Map<GameKey, LaunchChoice> = emptyMap(),
  val collectionRules: List<CollectionRule> = emptyList(),
  val platformDefaults: Map<PlatformSlug, LaunchChoice> = emptyMap(),
) {
  /** These rules with each local game's override moved to its ROM, unless the ROM has its own. */
  fun adopted(adoptions: List<Adoption>): LaunchRules {
    val games = gameOverrides.toMutableMap()
    for ((local, rom) in adoptions) {
      val choice = games.remove(local) ?: continue
      games.getOrPut(rom) { choice }
    }
    return copy(gameOverrides = games)
  }
}
