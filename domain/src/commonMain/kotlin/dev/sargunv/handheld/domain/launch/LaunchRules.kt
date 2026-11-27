package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.jvm.JvmInline

/** A RomM collection, by its kind and id, as `regular:3` or `smart:3`. */
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
 * launcher's own session.
 *
 * [recipe] says how to hand it a game; null uses the known emulator's recipe for [packageName], or
 * [LaunchRecipe.ViewRom] for an app the registry doesn't know. [core] is the libretro core's
 * library name for apps that load one, and for the built-in session.
 */
data class LaunchChoice(
  val packageName: String,
  val recipe: LaunchRecipe? = null,
  val core: String? = null,
) {
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

/**
 * A collection's choice of app. [platform] limits it to the collection's games on that platform,
 * for collections that span platforms; null applies it to all of them.
 */
data class CollectionRule(
  val collection: CollectionId,
  val choice: LaunchChoice,
  val platform: PlatformSlug? = null,
)

/**
 * The user's choices of app, from most to least specific. A game override beats a collection rule,
 * which beats a platform default. Among [collectionRules], the first that applies to a game wins.
 */
data class LaunchRules(
  val gameOverrides: Map<GameKey, LaunchChoice> = emptyMap(),
  val collectionRules: List<CollectionRule> = emptyList(),
  val platformDefaults: Map<PlatformSlug, LaunchChoice> = emptyMap(),
) {
  /** The [collectionRules] that apply to [subject], in the order they're tried. */
  fun collectionRulesFor(subject: LaunchSubject): List<CollectionRule> = collectionRules.filter {
    it.collection in subject.collections && (it.platform == null || it.platform == subject.platform)
  }
}
