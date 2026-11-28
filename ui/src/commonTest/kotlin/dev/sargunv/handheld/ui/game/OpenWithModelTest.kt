package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.CollectionKind
import dev.sargunv.handheld.domain.GameKey
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
  private val jak1 = LaunchSubject(GameKey.Romm(1), PS2, setOf(jak.launchId))

  private fun lines(
    rules: LaunchRules,
    installed: List<InstalledApp> = apps,
    subject: LaunchSubject = jak1,
    platformName: String = "PS2",
  ) =
    chainLines(
      RuleChain.explain(subject, rules, installed.mapTo(mutableSetOf()) { it.packageName }).steps,
      listOf(jak),
      platformName,
    ) {
      appName(it, installed, subject.platform)
    }

  @Test
  fun offersTheKnownEmulatorsFirstThenEveryOtherAppByName() {
    assertEquals(
      listOf("NetherSX2", "ARMSX2", "notes", "PocketGOAL", "RetroArch"),
      appOptions(PS2, apps).map { it.label },
    )
    // The built-in session runs the Game Boy Advance, and so does RetroArch, with its known core.
    assertEquals(
      listOf(
        AppOption(LaunchChoice.BuiltIn(), "Built-in · mGBA", known = true),
        AppOption(
          LaunchChoice.App("com.retroarch", "mgba_libretro"),
          "RetroArch · mGBA",
          known = true,
        ),
      ),
      appOptions(GBA, apps).take(2),
    )
  }

  @Test
  fun explainsThatTheCollectionsChoiceWins() {
    val rules =
      LaunchRules(
        collectionRules =
          listOf(CollectionRule(jak.launchId, PS2, LaunchChoice.App("dev.sargunv.pocketgoal"))),
      )
    assertEquals(
      listOf(
        ChainLine("Game: none"),
        ChainLine("Collection 'Jak and Daxter': PocketGOAL · opens here", wins = true),
        ChainLine("Platform PS2: Built-in · no core for PS2, skipped"),
        ChainLine("Platform PS2: NetherSX2 (default) · overridden"),
      ),
      lines(rules),
    )
  }

  @Test
  fun explainsAChoiceSkippedBecauseItsAppIsMissing() {
    val rules =
      LaunchRules(
        gameOverrides = mapOf(GameKey.Romm(1) to LaunchChoice.App("dev.sargunv.pocketgoal")),
        platformDefaults = mapOf(PS2 to LaunchChoice.App("com.armsx2")),
      )
    val withoutPocketGoal = apps.filter { it.packageName != "dev.sargunv.pocketgoal" }
    assertEquals(
      listOf(
        ChainLine("Game: PocketGOAL · not installed, skipped"),
        ChainLine("Collection 'Jak and Daxter': none"),
        ChainLine("Platform PS2: ARMSX2 · opens here", wins = true),
        ChainLine("Platform PS2: Built-in · no core for PS2, skipped"),
        ChainLine("Platform PS2: NetherSX2 (default) · overridden"),
      ),
      lines(rules, withoutPocketGoal),
    )
  }

  @Test
  fun fallsBackToThePlatformsDefaultWhenThePlatformsChoiceIsMissing() {
    val rules = LaunchRules(platformDefaults = mapOf(PS2 to LaunchChoice.App("com.example.gone")))
    assertEquals(
      listOf(
        ChainLine("Game: none"),
        ChainLine("Collection 'Jak and Daxter': none"),
        ChainLine("Platform PS2: com.example.gone · not installed, skipped"),
        ChainLine("Platform PS2: Built-in · no core for PS2, skipped"),
        ChainLine("Platform PS2: NetherSX2 (default) · opens here", wins = true),
      ),
      lines(rules),
    )
    assertEquals(
      ChainLine("Platform PS2: none (default)"),
      lines(LaunchRules(), emptyList()).last(),
    )
    // A choice of the built-in session, where no core runs the platform, is skipped too.
    val builtIn = LaunchRules(gameOverrides = mapOf(GameKey.Romm(1) to LaunchChoice.BuiltIn()))
    assertEquals(
      ChainLine("Game: Built-in · no core for PS2, skipped"),
      lines(builtIn).first(),
    )
  }

  @Test
  fun explainsTheBuiltInSession() {
    val gaia = LaunchSubject(GameKey.Romm(2), GBA)
    assertEquals(
      listOf(
        ChainLine("Game: none"),
        ChainLine("Collection: none"),
        ChainLine("Platform GBA: Built-in · mGBA (default) · opens here", wins = true),
      ),
      lines(LaunchRules(), subject = gaia, platformName = "GBA"),
    )
    val retroArch =
      LaunchRules(gameOverrides = mapOf(GameKey.Romm(2) to LaunchChoice.App("com.retroarch")))
    assertEquals(
      listOf(
        ChainLine("Game: RetroArch · opens here", wins = true),
        ChainLine("Collection: none"),
        ChainLine("Platform GBA: Built-in · mGBA (default) · overridden"),
      ),
      lines(retroArch, subject = gaia, platformName = "GBA"),
    )
    val builtIn = LaunchRules(gameOverrides = mapOf(GameKey.Romm(2) to LaunchChoice.BuiltIn()))
    assertEquals(
      ChainLine("Game: Built-in · mGBA · opens here", wins = true),
      lines(builtIn, subject = gaia, platformName = "GBA").first(),
    )
  }
}
