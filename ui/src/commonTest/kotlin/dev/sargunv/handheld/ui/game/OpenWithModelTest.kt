package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.launch.CollectionRule
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.launch.LaunchSubject
import dev.sargunv.handheld.domain.launch.RuleChain
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS2
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenWithModelTest {
  private val apps =
    listOf(
      InstalledApp("dev.sargunv.pocketgoal", "PocketGOAL"),
      InstalledApp("com.armsx2", "ARMSX2"),
      InstalledApp("com.retroarch", "RetroArch"),
      InstalledApp("xyz.aethersx2.android", "NetherSX2"),
      InstalledApp("com.example.notes", "notes"),
    )
  private val jak = Collection(CollectionKind.Regular, 7, "Jak and Daxter", "", 3, false)
  private val jak1 = LaunchSubject(1, PS2, setOf(jak.launchId))

  private fun lines(rules: LaunchRules, installed: List<InstalledApp> = apps) =
    chainLines(
      RuleChain.explain(jak1, rules, installed.mapTo(mutableSetOf()) { it.packageName }),
      listOf(jak),
      "PS2",
    ) { packageName, core ->
      appName(packageName, core, installed)
    }

  @Test
  fun offersTheKnownEmulatorsFirstThenEveryOtherAppByName() {
    assertEquals(
      listOf("NetherSX2", "ARMSX2", "notes", "PocketGOAL", "RetroArch"),
      appOptions(PS2, apps).map { it.label },
    )
    // RetroArch runs the Game Boy Advance, with its known core.
    assertEquals(
      AppOption(SavedChoice("com.retroarch", "mgba_libretro"), "RetroArch · mGBA", known = true),
      appOptions(GBA, apps).first(),
    )
  }

  @Test
  fun explainsThatTheCollectionsChoiceWins() {
    val rules =
      LaunchRules(
        collectionRules =
          listOf(CollectionRule(jak.launchId, LaunchChoice("dev.sargunv.pocketgoal"), PS2)),
      )
    assertEquals(
      listOf(
        ChainLine("Game: none"),
        ChainLine("Collection 'Jak and Daxter': PocketGOAL · opens here", wins = true),
        ChainLine("Platform PS2: NetherSX2 (default) · overridden"),
      ),
      lines(rules),
    )
  }

  @Test
  fun explainsAChoiceSkippedBecauseItsAppIsMissing() {
    val rules =
      LaunchRules(
        gameOverrides = mapOf(1L to LaunchChoice("dev.sargunv.pocketgoal")),
        platformDefaults = mapOf(PS2 to LaunchChoice("com.armsx2")),
      )
    val withoutPocketGoal = apps.filter { it.packageName != "dev.sargunv.pocketgoal" }
    assertEquals(
      listOf(
        ChainLine("Game: dev.sargunv.pocketgoal · not installed, skipped"),
        ChainLine("Collection: none"),
        ChainLine("Platform PS2: ARMSX2 · opens here", wins = true),
      ),
      lines(rules, withoutPocketGoal),
    )
  }

  @Test
  fun fallsBackToThePlatformsDefaultWhenThePlatformsChoiceIsMissing() {
    val rules = LaunchRules(platformDefaults = mapOf(PS2 to LaunchChoice("com.example.gone")))
    assertEquals(
      listOf(
        ChainLine("Game: none"),
        ChainLine("Collection: none"),
        ChainLine("Platform PS2: com.example.gone · not installed, skipped"),
        ChainLine("Default for PS2: NetherSX2 · opens here", wins = true),
      ),
      lines(rules),
    )
    assertEquals(
      ChainLine("Platform PS2: no emulator installed"),
      lines(LaunchRules(), emptyList()).last(),
    )
  }
}
