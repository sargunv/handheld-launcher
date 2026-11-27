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
 * for [LaunchRule.BuiltInDefault], [LaunchChoice.BuiltIn].
 */
data class RuleStep(val rule: LaunchRule, val status: RuleStatus, val choice: LaunchChoice? = null)

/**
 * The rules that decide where a game opens, from most to least specific, each with what became of
 * it, so the user can see why the game opens where it does. [LaunchResolver] opens the game where
 * the level that [RuleStatus.Wins] says.
 */
object RuleChain {
  /**
   * The game's override, then each of its collections' rules that applies, then its platform's
   * default, then the defaults: the built-in session where a libretro core runs the platform, then
   * the best installed known emulator, which comes first for the platforms it plays better
   * ([LibretroCores.prefersStandalone]). Collections without a rule are left out, and so is a
   * default that can't be reached, such as the emulator where the built-in session always runs the
   * game. The built-in default stays where no core runs the platform, to show it's skipped.
   */
  fun explain(
    subject: LaunchSubject,
    rules: LaunchRules,
    installedPackages: Set<String>,
  ): List<RuleStep> {
    val levels = buildList {
      add(LaunchRule.GameOverride to subject.game?.let(rules.gameOverrides::get))
      for (rule in rules.collectionRulesFor(subject)) {
        add(LaunchRule.CollectionOverride(rule.collection) to rule.choice)
      }
      add(LaunchRule.PlatformDefault to rules.platformDefaults[subject.platform])
    }
    val runsBuiltIn = LibretroCores.recommendedFor(subject.platform) != null
    var decided = false
    val steps = levels.map { (rule, choice) ->
      val status =
        when {
          choice == null -> RuleStatus.Unset
          decided -> RuleStatus.Outranked
          choice.isBuiltIn && !runsBuiltIn -> RuleStatus.NoCore
          choice.isBuiltIn || choice.packageName in installedPackages ->
            RuleStatus.Wins.also { decided = true }
          else -> RuleStatus.NotInstalled
        }
      RuleStep(rule, status, choice)
    }

    val emulator = firstInstalled(subject.platform, installedPackages)
    val builtIn =
      RuleStep(
        LaunchRule.BuiltInDefault,
        when {
          !runsBuiltIn -> RuleStatus.NoCore
          decided -> RuleStatus.Outranked
          else -> RuleStatus.Wins
        },
        LaunchChoice.BuiltIn,
      )
    val firstInstalled =
      RuleStep(
        LaunchRule.FirstInstalled,
        when {
          emulator == null -> RuleStatus.Unset
          decided -> RuleStatus.Outranked
          else -> RuleStatus.Wins
        },
        emulator?.let(::LaunchChoice),
      )
    val defaults =
      when {
        !runsBuiltIn -> listOf(builtIn, firstInstalled)
        !LibretroCores.prefersStandalone(subject.platform) -> listOf(builtIn)
        emulator == null -> listOf(builtIn)
        else -> listOf(firstInstalled)
      }
    return steps + defaults
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
