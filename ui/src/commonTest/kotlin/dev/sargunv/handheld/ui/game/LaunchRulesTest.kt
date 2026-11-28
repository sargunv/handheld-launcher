package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchRulesTest {
  private val pocketGoal = LaunchChoice.App("dev.sargunv.pocketgoal")
  private val armsx2 = LaunchChoice.App("com.armsx2")
  private val jak = Collection(CollectionKind.Regular, 7, "Jak and Daxter", "", 3, false)

  @Test
  fun aNewChoiceReplacesTheLevelsChoiceAndClearingRemovesIt() {
    val scope = OpenWithScope.Collection(jak.launchId, PS2)
    val replaced = LaunchRules().withChoice(scope, armsx2).withChoice(scope, pocketGoal)
    assertEquals(pocketGoal, replaced.choiceAt(scope))
    assertEquals(1, replaced.collectionRules.size)
    // The same collection's games on another platform keep their own choice.
    assertNull(replaced.choiceAt(OpenWithScope.Collection(jak.launchId, GBA)))

    assertEquals(LaunchRules(), replaced.withChoice(scope, null))
    val game = OpenWithScope.Game(GameKey.Romm(1))
    assertEquals(LaunchRules(), LaunchRules().withChoice(game, armsx2).withChoice(game, null))
  }
}
