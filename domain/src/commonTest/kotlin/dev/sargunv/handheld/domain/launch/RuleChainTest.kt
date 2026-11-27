package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.launch.LaunchRule.BuiltInDefault
import dev.sargunv.handheld.domain.launch.LaunchRule.CollectionOverride
import dev.sargunv.handheld.domain.launch.LaunchRule.FirstInstalled
import dev.sargunv.handheld.domain.launch.LaunchRule.GameOverride
import dev.sargunv.handheld.domain.launch.LaunchRule.PlatformDefault
import dev.sargunv.handheld.domain.launch.RuleStatus.NoCore
import dev.sargunv.handheld.domain.launch.RuleStatus.NotInstalled
import dev.sargunv.handheld.domain.launch.RuleStatus.Outranked
import dev.sargunv.handheld.domain.launch.RuleStatus.Unset
import dev.sargunv.handheld.domain.launch.RuleStatus.Wins
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GAMECUBE
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS2
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleChainTest {
  private val armsx2 = "com.armsx2"
  private val pocketGoal = "dev.sargunv.pocketgoal"
  private val jak = CollectionId("regular:1")
  private val favorites = CollectionId("regular:2")
  private val jak1 =
    LaunchSubject(game = GameKey.Romm(1), platform = PS2, collections = setOf(jak, favorites))

  @Test
  fun theCollectionRuleWinsOverThePlatformsDefaultEmulator() {
    val rules = LaunchRules(collectionRules = listOf(CollectionRule(jak, LaunchChoice(pocketGoal))))
    assertEquals(
      listOf(
        RuleStep(GameOverride, Unset),
        RuleStep(CollectionOverride(jak), Wins, LaunchChoice(pocketGoal)),
        RuleStep(BuiltInDefault, NoCore, LaunchChoice.BuiltIn),
        RuleStep(FirstInstalled, Outranked, LaunchChoice(armsx2), isDefault = true),
      ),
      RuleChain.explain(jak1, rules, setOf(armsx2, pocketGoal)),
    )
  }

  @Test
  fun aRuleWhoseAppIsMissingIsSkipped() {
    val rules =
      LaunchRules(
        gameOverrides = mapOf(GameKey.Romm(1) to LaunchChoice(pocketGoal)),
        platformDefaults = mapOf(PS2 to LaunchChoice(armsx2)),
      )
    val steps = RuleChain.explain(jak1, rules, setOf(armsx2))
    assertEquals(
      listOf(
        RuleStep(GameOverride, NotInstalled, LaunchChoice(pocketGoal)),
        RuleStep(CollectionOverride(null), Unset),
        RuleStep(PlatformDefault, Wins, LaunchChoice(armsx2)),
        RuleStep(BuiltInDefault, NoCore, LaunchChoice.BuiltIn),
        RuleStep(FirstInstalled, Outranked, LaunchChoice(armsx2), isDefault = true),
      ),
      steps,
    )
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
        RuleStep(CollectionOverride(favorites), Wins, LaunchChoice(armsx2)),
        RuleStep(CollectionOverride(jak), Outranked, LaunchChoice(pocketGoal)),
        RuleStep(BuiltInDefault, NoCore, LaunchChoice.BuiltIn),
        RuleStep(FirstInstalled, Outranked, LaunchChoice(armsx2), isDefault = true),
      ),
      RuleChain.explain(jak1, rules, setOf(armsx2, pocketGoal)),
    )
  }

  @Test
  fun aPlatformsGamesHaveOnlyThePlatformsLevels() {
    val rules =
      LaunchRules(
        gameOverrides = mapOf(GameKey.Romm(1) to LaunchChoice(pocketGoal)),
        collectionRules = listOf(CollectionRule(jak, LaunchChoice(pocketGoal))),
      )
    assertEquals(
      listOf(
        RuleStep(BuiltInDefault, NoCore, LaunchChoice.BuiltIn),
        RuleStep(FirstInstalled, Wins, LaunchChoice(armsx2), isDefault = true),
      ),
      RuleChain.explain(LaunchSubject(game = null, platform = PS2), rules, setOf(armsx2)),
    )
  }

  @Test
  fun withNoRulesTheBestInstalledEmulatorWins() {
    assertEquals(
      RuleStep(FirstInstalled, Wins, LaunchChoice(armsx2), isDefault = true),
      RuleChain.explain(jak1, LaunchRules(), setOf(armsx2)).last(),
    )
    assertEquals(
      RuleStep(FirstInstalled, Unset, isDefault = true),
      RuleChain.explain(jak1, LaunchRules(), emptySet()).last(),
    )
  }

  @Test
  fun theBuiltInSessionIsTheDefaultWhereALibretroCoreRunsThePlatform() {
    val gaia = LaunchSubject(game = GameKey.Romm(2), platform = GBA)
    val installed = setOf("com.retroarch")
    assertEquals(
      listOf(
        RuleStep(GameOverride, Unset),
        RuleStep(CollectionOverride(null), Unset),
        RuleStep(BuiltInDefault, Wins, LaunchChoice.BuiltIn, isDefault = true),
      ),
      RuleChain.explain(gaia, LaunchRules(), installed),
    )
    // A choice of app outranks it.
    val rules = LaunchRules(gameOverrides = mapOf(GameKey.Romm(2) to LaunchChoice("com.retroarch")))
    assertEquals(
      RuleStep(BuiltInDefault, Outranked, LaunchChoice.BuiltIn, isDefault = true),
      RuleChain.explain(gaia, rules, installed).last(),
    )
  }

  @Test
  fun anInstalledEmulatorIsTheDefaultWhereItPlaysBetter() {
    val game = LaunchSubject(game = GameKey.Romm(3), platform = GAMECUBE)
    assertEquals(
      RuleStep(FirstInstalled, Wins, LaunchChoice("org.dolphinemu.dolphinemu"), isDefault = true),
      RuleChain.explain(game, LaunchRules(), setOf("org.dolphinemu.dolphinemu")).last(),
    )
    assertEquals(
      RuleStep(BuiltInDefault, Wins, LaunchChoice.BuiltIn, isDefault = true),
      RuleChain.explain(game, LaunchRules(), emptySet()).last(),
    )
  }

  @Test
  fun aBuiltInChoiceWinsWhereACoreRunsThePlatformAndIsSkippedElsewhere() {
    val rules =
      LaunchRules(
        gameOverrides =
          mapOf(GameKey.Romm(1) to LaunchChoice.BuiltIn, GameKey.Romm(2) to LaunchChoice.BuiltIn),
      )
    val gaia = LaunchSubject(game = GameKey.Romm(2), platform = GBA)
    assertEquals(
      RuleStep(GameOverride, Wins, LaunchChoice.BuiltIn),
      RuleChain.explain(gaia, rules, emptySet()).first(),
    )
    assertEquals(
      listOf(
        RuleStep(GameOverride, NoCore, LaunchChoice.BuiltIn),
        RuleStep(CollectionOverride(null), Unset),
        RuleStep(BuiltInDefault, NoCore, LaunchChoice.BuiltIn),
        RuleStep(FirstInstalled, Wins, LaunchChoice(armsx2), isDefault = true),
      ),
      RuleChain.explain(jak1.copy(collections = emptySet()), rules, setOf(armsx2)),
    )
  }
}
