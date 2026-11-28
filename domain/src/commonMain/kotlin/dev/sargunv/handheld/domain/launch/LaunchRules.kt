package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.jvm.JvmInline
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
 * An app picked to launch games: any installed app, known emulator or not, or [BuiltIn], the
 * launcher's own session. [core] is the libretro core's library name for apps that load one, and
 * for the built-in session; null runs the platform's recommended core.
 */
@Serializable
data class LaunchChoice(val packageName: String, val core: String? = null) {
  /** Whether this picks the built-in session rather than an app. */
  val isBuiltIn: Boolean
    get() = packageName == BUILT_IN

  companion object {
    /**
     * The package name that stands for the built-in session. An Android package name always has a
     * dot, so no app can have it.
     */
    const val BUILT_IN = "builtin"

    /** The built-in session, with the platform's core. */
    val BuiltIn = LaunchChoice(BUILT_IN)
  }
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
