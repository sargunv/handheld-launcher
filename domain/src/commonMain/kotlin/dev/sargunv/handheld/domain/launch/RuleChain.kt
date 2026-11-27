package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.emulator.KnownEmulator
import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.platform.PlatformSlug

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
 * for [LaunchRule.BuiltInDefault], [LaunchChoice.BuiltIn]. [isDefault] marks the platform's
 * default, which opens its games when no choice applies.
 */
data class RuleStep(
  val rule: LaunchRule,
  val status: RuleStatus,
  val choice: LaunchChoice? = null,
  val isDefault: Boolean = false,
)

/**
 * The rules that decide where a game opens, from most to least specific, each with what became of
 * it, so the user can see why the game opens where it does. [LaunchResolver] opens the game where
 * the level that [RuleStatus.Wins] says.
 */
object RuleChain {
  /**
   * The game's override, then each of its collections' rules that applies, or one unset collection
   * level when none does, then its platform's choice when it has one, then the platform's default.
   * The default is the built-in session where a libretro core runs the platform, unless the best
   * installed known emulator plays it better ([LibretroCores.prefersStandalone]), and that emulator
   * elsewhere. Where no core runs the platform, the built-in session comes before the default,
   * skipped, to show why. A platform's games, which are no game and in no collection, have no game
   * or collection level, and a collection's games no game level.
   */
  fun explain(
    subject: LaunchSubject,
    rules: LaunchRules,
    installedPackages: Set<String>,
  ): List<RuleStep> {
    val platform = subject.platform
    val runsBuiltIn = LibretroCores.recommendedFor(platform) != null
    val emulator = firstInstalled(platform, installedPackages)?.let(::LaunchChoice)
    val default =
      if (runsBuiltIn && (emulator == null || !LibretroCores.prefersStandalone(platform))) {
        LaunchRule.BuiltInDefault to LaunchChoice.BuiltIn
      } else {
        LaunchRule.FirstInstalled to emulator
      }
    val levels = buildList {
      subject.game?.let { add(LaunchRule.GameOverride to rules.gameOverrides[it]) }
      val collectionRules = rules.collectionRulesFor(subject)
      for (rule in collectionRules) {
        add(LaunchRule.CollectionOverride(rule.collection) to rule.choice)
      }
      if (collectionRules.isEmpty() && (subject.game != null || subject.collections.isNotEmpty())) {
        add(LaunchRule.CollectionOverride(subject.collections.singleOrNull()) to null)
      }
      rules.platformDefaults[platform]?.let { add(LaunchRule.PlatformDefault to it) }
      if (!runsBuiltIn) add(LaunchRule.BuiltInDefault to LaunchChoice.BuiltIn)
      add(default)
    }
    var decided = false
    return levels.map { (rule, choice) ->
      val status =
        when {
          choice == null -> RuleStatus.Unset
          choice.isBuiltIn && !runsBuiltIn -> RuleStatus.NoCore
          decided -> RuleStatus.Outranked
          choice.isBuiltIn || choice.packageName in installedPackages ->
            RuleStatus.Wins.also { decided = true }
          else -> RuleStatus.NotInstalled
        }
      RuleStep(rule, status, choice, isDefault = rule == default.first)
    }
  }

  /** The installed package of the best known emulator for [platform], or null when none is. */
  private fun firstInstalled(platform: PlatformSlug, installed: Set<String>): String? =
    EmulatorRegistry.forPlatform(platform).firstNotNullOfOrNull { installedBuildOf(it, installed) }

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
