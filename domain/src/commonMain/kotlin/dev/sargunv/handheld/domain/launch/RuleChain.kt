package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.emulator.KnownEmulator
import dev.sargunv.handheld.domain.emulator.LibretroCore
import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.platform.PlatformSlug

/** A level of the rules that decide where a game opens. */
sealed interface LaunchRule {
  /** The game's own override. */
  data object GameOverride : LaunchRule

  /**
   * The rule of [collection], one of the game's collections. On the unset level that stands for the
   * collections when none of the game's has a rule, [collection] is the game's one collection, or
   * null when it's in none or several.
   */
  data class CollectionOverride(val collection: CollectionId?) : LaunchRule

  /** The default for the game's platform. */
  data object PlatformDefault : LaunchRule

  /** Nothing named an app, and a libretro core runs the platform, so the built-in session does. */
  data object BuiltInDefault : LaunchRule

  /** Nothing named an app, so the best installed known emulator for the platform runs it. */
  data object FirstInstalled : LaunchRule
}

/** What became of one level of the rules that decide where a game opens. */
enum class RuleStatus {
  /** The level names no app. */
  Unset,
  /** The level's app opens the game. */
  Wins,
  /** The level's app isn't installed, so the next level decides. */
  NotInstalled,
  /** The level picks the built-in session, but no libretro core runs the game's platform. */
  NoCore,
  /** A more specific level already decided. */
  Outranked,
}

/**
 * One level of the rules for a game. [choice] is its app, or null when it names none. For
 * [LaunchRule.FirstInstalled], it's the best installed known emulator for the game's platform, and
 * for [LaunchRule.BuiltInDefault], the built-in session. [isDefault] marks the platform's default,
 * which opens its games when no choice applies.
 */
data class RuleStep(
  val rule: LaunchRule,
  val status: RuleStatus,
  val choice: LaunchChoice? = null,
  val isDefault: Boolean = false,
)

/**
 * The app a game launches in, and how. [emulator] is the known emulator [packageName] is, or null
 * for an app the registry doesn't know. [core] is the libretro core it loads, or null when it loads
 * none or no core runs the game's platform.
 */
data class LaunchTarget(
  val packageName: String,
  val recipe: LaunchRecipe,
  val emulator: KnownEmulator?,
  val core: LibretroCore?,
) {
  /**
   * The command that launches the game at [romPath] on [platform] here, or null when the recipe
   * loads a core and there's none. [installedVersionName] is the app's version name.
   */
  fun commandFor(
    romPath: String,
    platform: PlatformSlug,
    installedVersionName: String?,
  ): LaunchCommand? = recipe.commandFor(packageName, romPath, platform, core, installedVersionName)
}

/** Where a game opens, and the [rule] that decided it. */
sealed interface LaunchResolution {
  val rule: LaunchRule

  /** The game opens in another app. */
  data class Standalone(val target: LaunchTarget, override val rule: LaunchRule) : LaunchResolution

  /** The game runs in the built-in session, in [core]. */
  data class BuiltIn(val core: LibretroCore, override val rule: LaunchRule) : LaunchResolution
}

/**
 * The rules that decide where a game on [platform] opens, from most to least specific, each with
 * what became of it, so the user can see why the game opens where it does. A rule may name any app
 * or the built-in session. One whose app isn't installed is skipped, as is one that picks the
 * built-in session for a platform no libretro core runs.
 */
data class RuleChain(val platform: PlatformSlug, val steps: List<RuleStep>) {
  /** The step whose app opens the game, or null when no app on the device can. */
  val winner: RuleStep?
    get() = steps.firstOrNull { it.status == RuleStatus.Wins }

  /** Where [winner] opens the game, or null when nothing wins. */
  fun resolve(): LaunchResolution? {
    val winner = winner ?: return null
    val choice = checkNotNull(winner.choice)
    val core = LibretroCores.resolve(choice.core, platform)
    return when (choice) {
      // The built-in session wins only on a platform with a recommended core.
      is LaunchChoice.BuiltIn -> LaunchResolution.BuiltIn(checkNotNull(core), winner.rule)
      is LaunchChoice.App -> {
        val emulator = EmulatorRegistry.forPackage(choice.packageName)
        val recipe = emulator?.recipeFor(choice.packageName) ?: LaunchRecipe.ViewRom
        val target =
          LaunchTarget(choice.packageName, recipe, emulator, core.takeIf { recipe.loadsCore })
        LaunchResolution.Standalone(target, winner.rule)
      }
    }
  }

  companion object {
    /**
     * The game's override, then each of its collections' rules that applies, or one unset
     * collection level when none does, then its platform's choice when it has one, then the
     * platform's default. The default is the built-in session where a libretro core runs the
     * platform, unless the best installed known emulator plays it better
     * ([LibretroCores.prefersStandalone]), and that emulator elsewhere. Where no core runs the
     * platform, the built-in session comes before the default, skipped, to show why. A platform's
     * games, which are no game and in no collection, have no game or collection level, and a
     * collection's games no game level.
     */
    fun explain(
      subject: LaunchSubject,
      rules: LaunchRules,
      installedPackages: Set<String>,
    ): RuleChain {
      val platform = subject.platform
      val runsBuiltIn = LibretroCores.recommendedFor(platform) != null
      val emulator = firstInstalled(platform, installedPackages)?.let { LaunchChoice.App(it) }
      val default =
        if (runsBuiltIn && (emulator == null || !LibretroCores.prefersStandalone(platform))) {
          LaunchRule.BuiltInDefault to LaunchChoice.BuiltIn()
        } else {
          LaunchRule.FirstInstalled to emulator
        }
      val levels = buildList {
        subject.game?.let { add(LaunchRule.GameOverride to rules.gameOverrides[it]) }
        val collectionRules =
          rules.collectionRules.filter {
            it.collection in subject.collections && it.platform == platform
          }
        for (rule in collectionRules) {
          add(LaunchRule.CollectionOverride(rule.collection) to rule.choice)
        }
        if (
          collectionRules.isEmpty() && (subject.game != null || subject.collections.isNotEmpty())
        ) {
          add(LaunchRule.CollectionOverride(subject.collections.singleOrNull()) to null)
        }
        rules.platformDefaults[platform]?.let { add(LaunchRule.PlatformDefault to it) }
        if (!runsBuiltIn) add(LaunchRule.BuiltInDefault to LaunchChoice.BuiltIn())
        add(default)
      }
      var decided = false
      val steps = levels.map { (rule, choice) ->
        val status =
          when {
            choice == null -> RuleStatus.Unset
            choice is LaunchChoice.BuiltIn && !runsBuiltIn -> RuleStatus.NoCore
            decided -> RuleStatus.Outranked
            choice is LaunchChoice.App && choice.packageName !in installedPackages ->
              RuleStatus.NotInstalled
            else -> RuleStatus.Wins.also { decided = true }
          }
        RuleStep(rule, status, choice, isDefault = rule == default.first)
      }
      return RuleChain(platform, steps)
    }

    /** The installed package of the best known emulator for [platform], or null when none is. */
    private fun firstInstalled(platform: PlatformSlug, installed: Set<String>): String? =
      EmulatorRegistry.forPlatform(platform).firstNotNullOfOrNull {
        installedBuildOf(it, installed)
      }

    /**
     * The installed package that is [emulator]: its own package if installed, else the first, by
     * name, of the installed builds its patterns match that aren't another known emulator.
     */
    private fun installedBuildOf(emulator: KnownEmulator, installed: Set<String>): String? =
      emulator.packageName.takeIf { it in installed }
        ?: installed
          .filter { emulator.matchesPattern(it) && EmulatorRegistry.forPackage(it) == emulator }
          .minOrNull()
  }
}
