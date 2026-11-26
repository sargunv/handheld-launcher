package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.emulator.LibretroCores

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
 * One level of the rules for a game. [packageName] and [core] are its app, or null when it names
 * none. For [LaunchRule.FirstInstalled], the app is the best installed known emulator for the
 * game's platform, and for [LaunchRule.BuiltInDefault], it's [LaunchChoice.BUILT_IN].
 */
data class RuleStep(
  val rule: LaunchRule,
  val status: RuleStatus,
  val packageName: String? = null,
  val core: String? = null,
)

/**
 * The rules that decide where a game opens, from most to least specific, each with what became of
 * it, so the user can see why the game opens where it does. It agrees with [LaunchResolver].
 */
object RuleChain {
  /**
   * The game's override, then each of its collections' rules that applies, then its platform's
   * default, then the defaults in the order [LaunchResolver] tries them: the built-in session and
   * the best installed known emulator. Collections without a rule are left out, and so is a default
   * that can't be reached, such as the emulator where the built-in session always runs the game.
   * The built-in default stays where no core runs the platform, to show it's skipped.
   */
  fun explain(
    subject: LaunchSubject,
    rules: LaunchRules,
    installedPackages: Set<String>,
  ): List<RuleStep> {
    val levels = buildList {
      add(LaunchRule.GameOverride to rules.gameOverrides[subject.gameId])
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
      RuleStep(rule, status, choice?.packageName, choice?.core)
    }

    val emulator = LaunchResolver.firstInstalled(subject.platform, installedPackages)?.packageName
    val builtIn =
      RuleStep(
        LaunchRule.BuiltInDefault,
        when {
          !runsBuiltIn -> RuleStatus.NoCore
          decided -> RuleStatus.Outranked
          else -> RuleStatus.Wins
        },
        LaunchChoice.BUILT_IN,
      )
    val firstInstalled =
      RuleStep(
        LaunchRule.FirstInstalled,
        when {
          emulator == null -> RuleStatus.Unset
          decided -> RuleStatus.Outranked
          else -> RuleStatus.Wins
        },
        emulator,
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
}
