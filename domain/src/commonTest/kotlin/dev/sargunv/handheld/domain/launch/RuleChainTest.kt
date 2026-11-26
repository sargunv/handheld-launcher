package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.launch.LaunchRule.CollectionOverride
import dev.sargunv.handheld.domain.launch.LaunchRule.FirstInstalled
import dev.sargunv.handheld.domain.launch.LaunchRule.GameOverride
import dev.sargunv.handheld.domain.launch.LaunchRule.PlatformDefault
import dev.sargunv.handheld.domain.launch.RuleStatus.NotInstalled
import dev.sargunv.handheld.domain.launch.RuleStatus.Outranked
import dev.sargunv.handheld.domain.launch.RuleStatus.Unset
import dev.sargunv.handheld.domain.launch.RuleStatus.Wins
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS2
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleChainTest {
  private val armsx2 = "com.armsx2"
  private val pocketGoal = "dev.sargunv.pocketgoal"
  private val jak = CollectionId("regular:1")
  private val favorites = CollectionId("regular:2")
  private val jak1 = LaunchSubject(gameId = 1, platform = PS2, collections = setOf(jak, favorites))

  @Test
  fun theCollectionRuleWinsOverThePlatformsDefaultEmulator() {
    val rules = LaunchRules(collectionRules = listOf(CollectionRule(jak, LaunchChoice(pocketGoal))))
    assertEquals(
      listOf(
        RuleStep(GameOverride, Unset),
        RuleStep(CollectionOverride(jak), Wins, pocketGoal),
        RuleStep(PlatformDefault, Unset),
        RuleStep(FirstInstalled, Outranked, armsx2),
      ),
      RuleChain.explain(jak1, rules, setOf(armsx2, pocketGoal)),
    )
  }

  @Test
  fun aRuleWhoseAppIsMissingIsSkipped() {
    val rules =
      LaunchRules(
        gameOverrides = mapOf(1L to LaunchChoice(pocketGoal)),
        platformDefaults = mapOf(PS2 to LaunchChoice(armsx2)),
      )
    val steps = RuleChain.explain(jak1, rules, setOf(armsx2))
    assertEquals(
      listOf(
        RuleStep(GameOverride, NotInstalled, pocketGoal),
        RuleStep(PlatformDefault, Wins, armsx2),
        RuleStep(FirstInstalled, Outranked, armsx2),
      ),
      steps,
    )
    // It agrees with the resolver, skipped rule included.
    val resolution = LaunchResolver.resolve(jak1, rules, setOf(armsx2))
    assertEquals(listOf(SkippedChoice(GameOverride, pocketGoal)), resolution.skipped)
  }

  @Test
  fun everyCollectionRuleThatAppliesIsShownInOrder() {
    val rules =
      LaunchRules(
        collectionRules =
          listOf(
            CollectionRule(favorites, LaunchChoice(armsx2), PS2),
            CollectionRule(jak, LaunchChoice(pocketGoal), PS2),
            // For the collection's games on another platform, so it doesn't apply.
            CollectionRule(jak, LaunchChoice("com.retroarch", core = "mgba_libretro"), GBA),
          ),
      )
    assertEquals(
      listOf(
        RuleStep(GameOverride, Unset),
        RuleStep(CollectionOverride(favorites), Wins, armsx2),
        RuleStep(CollectionOverride(jak), Outranked, pocketGoal),
        RuleStep(PlatformDefault, Unset),
        RuleStep(FirstInstalled, Outranked, armsx2),
      ),
      RuleChain.explain(jak1, rules, setOf(armsx2, pocketGoal)),
    )
  }

  @Test
  fun withNoRulesTheBestInstalledEmulatorWins() {
    assertEquals(
      RuleStep(FirstInstalled, Wins, armsx2),
      RuleChain.explain(jak1, LaunchRules(), setOf(armsx2)).last(),
    )
    assertEquals(
      RuleStep(FirstInstalled, Unset),
      RuleChain.explain(jak1, LaunchRules(), emptySet()).last(),
    )
  }
}
