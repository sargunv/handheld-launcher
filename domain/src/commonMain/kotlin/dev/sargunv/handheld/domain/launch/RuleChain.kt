package dev.sargunv.handheld.domain.launch

/** What became of one level of the rules that decide where a game opens. */
enum class RuleStatus {
  /** The level names no app. */
  Unset,
  /** The level's app opens the game. */
  Wins,
  /** The level's app isn't installed, so the next level decides. */
  NotInstalled,
  /** A more specific level already decided. */
  Outranked,
}

/**
 * One level of the rules for a game. [packageName] and [core] are its app, or null when it names
 * none. For [LaunchRule.FirstInstalled], the app is the best installed known emulator for the
 * game's platform.
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
   * default, then the best installed known emulator. Collections without a rule are left out.
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
    var decided = false
    val steps = levels.map { (rule, choice) ->
      val status =
        when {
          choice == null -> RuleStatus.Unset
          decided -> RuleStatus.Outranked
          choice.packageName in installedPackages -> RuleStatus.Wins.also { decided = true }
          else -> RuleStatus.NotInstalled
        }
      RuleStep(rule, status, choice?.packageName, choice?.core)
    }
    val fallback =
      LaunchResolver.resolve(subject, LaunchRules(), installedPackages)
        as? LaunchResolution.Resolved
    val fallbackStatus =
      when {
        fallback == null -> RuleStatus.Unset
        decided -> RuleStatus.Outranked
        else -> RuleStatus.Wins
      }
    return steps +
      RuleStep(LaunchRule.FirstInstalled, fallbackStatus, fallback?.target?.packageName)
  }
}
