package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.emulator.LibretroCores
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
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GB
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.N64
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS2
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PSX
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SNES
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SWITCH
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuleChainTest {
  private val armsx2 = "com.armsx2"
  private val pocketGoal = "dev.sargunv.pocketgoal"
  private val jak = CollectionId("regular:1")
  private val favorites = CollectionId("regular:2")
  private val jak1 =
    LaunchSubject(game = GameKey.Romm(1), platform = PS2, collections = setOf(jak, favorites))
  private val ratchet = LaunchSubject(game = GameKey.Romm(3), platform = PS2)

  private fun steps(subject: LaunchSubject, rules: LaunchRules, installed: Set<String>) =
    RuleChain.explain(subject, rules, installed).steps

  private fun resolve(subject: LaunchSubject, rules: LaunchRules, installed: Set<String>) =
    RuleChain.explain(subject, rules, installed).resolve()

  private fun standalone(subject: LaunchSubject, rules: LaunchRules, installed: Set<String>) =
    assertIs<LaunchResolution.Standalone>(resolve(subject, rules, installed))

  // The vision's first acceptance test. PocketGOAL plays only the Jak games, so nothing picks it
  // for a PS2 game, but a rule naming it beats the platform's default at either level.
  @Test
  fun jakGamesOpenInPocketGoalAndOtherPs2GamesInArmsx2() {
    val jak2 = LaunchSubject(game = GameKey.Romm(2), platform = PS2, collections = setOf(jak))
    val okami = ratchet.copy(collections = setOf(CollectionId("regular:9")))
    val installed = setOf(armsx2, pocketGoal, "xyz.aethersx2.android")
    val byCollection =
      LaunchRules(
        collectionRules = listOf(CollectionRule(jak, PS2, LaunchChoice.App(pocketGoal))),
        platformDefaults = mapOf(PS2 to LaunchChoice.App(armsx2)),
      )
    val byGame =
      LaunchRules(
        gameOverrides =
          mapOf(
            GameKey.Romm(1) to LaunchChoice.App(pocketGoal),
            GameKey.Romm(2) to LaunchChoice.App(pocketGoal),
          ),
        platformDefaults = mapOf(PS2 to LaunchChoice.App(armsx2)),
      )

    for (game in listOf(jak1, jak2)) {
      val viaCollection = standalone(game, byCollection, installed)
      assertEquals(pocketGoal, viaCollection.target.packageName)
      assertEquals(CollectionOverride(jak), viaCollection.rule)
      assertEquals(EmulatorId("pocketgoal"), viaCollection.target.emulator?.id)
      assertEquals(LaunchRecipe.ViewRom, viaCollection.target.recipe)

      val viaGame = standalone(game, byGame, installed)
      assertEquals(pocketGoal, viaGame.target.packageName)
      assertEquals(GameOverride, viaGame.rule)
    }

    for (game in listOf(ratchet, okami)) {
      for (rules in listOf(byCollection, byGame)) {
        val other = standalone(game, rules, installed)
        assertEquals(armsx2, other.target.packageName)
        assertEquals(PlatformDefault, other.rule)
        assertEquals(EmulatorId("armsx2"), other.target.emulator?.id)
        assertEquals(other.target.emulator?.recipe, other.target.recipe)
      }
    }
  }

  @Test
  fun theCollectionRuleWinsOverThePlatformsDefaultEmulator() {
    val rules =
      LaunchRules(collectionRules = listOf(CollectionRule(jak, PS2, LaunchChoice.App(pocketGoal))))
    assertEquals(
      listOf(
        RuleStep(GameOverride, Unset),
        RuleStep(CollectionOverride(jak), Wins, LaunchChoice.App(pocketGoal)),
        RuleStep(BuiltInDefault, NoCore, LaunchChoice.BuiltIn()),
        RuleStep(FirstInstalled, Outranked, LaunchChoice.App(armsx2), isDefault = true),
      ),
      steps(jak1, rules, setOf(armsx2, pocketGoal)),
    )
  }

  @Test
  fun aRuleWhoseAppIsMissingIsSkipped() {
    val rules =
      LaunchRules(
        gameOverrides = mapOf(GameKey.Romm(1) to LaunchChoice.App(pocketGoal)),
        platformDefaults = mapOf(PS2 to LaunchChoice.App(armsx2)),
      )
    assertEquals(
      listOf(
        RuleStep(GameOverride, NotInstalled, LaunchChoice.App(pocketGoal)),
        RuleStep(CollectionOverride(null), Unset),
        RuleStep(PlatformDefault, Wins, LaunchChoice.App(armsx2)),
        RuleStep(BuiltInDefault, NoCore, LaunchChoice.BuiltIn()),
        RuleStep(FirstInstalled, Outranked, LaunchChoice.App(armsx2), isDefault = true),
      ),
      steps(jak1, rules, setOf(armsx2)),
    )
    assertNull(resolve(jak1, rules, setOf("com.example.notes")))
  }

  @Test
  fun everyCollectionRuleForTheGamesPlatformIsShownInOrder() {
    val rules =
      LaunchRules(
        collectionRules =
          listOf(
            CollectionRule(favorites, PS2, LaunchChoice.App(armsx2)),
            CollectionRule(jak, PS2, LaunchChoice.App(pocketGoal)),
            CollectionRule(jak, GBA, LaunchChoice.App("com.retroarch", core = "mgba_libretro")),
          ),
      )
    assertEquals(
      listOf(
        RuleStep(GameOverride, Unset),
        RuleStep(CollectionOverride(favorites), Wins, LaunchChoice.App(armsx2)),
        RuleStep(CollectionOverride(jak), Outranked, LaunchChoice.App(pocketGoal)),
        RuleStep(BuiltInDefault, NoCore, LaunchChoice.BuiltIn()),
        RuleStep(FirstInstalled, Outranked, LaunchChoice.App(armsx2), isDefault = true),
      ),
      steps(jak1, rules, setOf(armsx2, pocketGoal)),
    )
  }

  @Test
  fun aPlatformsGamesHaveOnlyThePlatformsLevels() {
    val rules =
      LaunchRules(
        gameOverrides = mapOf(GameKey.Romm(1) to LaunchChoice.App(pocketGoal)),
        collectionRules = listOf(CollectionRule(jak, PS2, LaunchChoice.App(pocketGoal))),
      )
    assertEquals(
      listOf(
        RuleStep(BuiltInDefault, NoCore, LaunchChoice.BuiltIn()),
        RuleStep(FirstInstalled, Wins, LaunchChoice.App(armsx2), isDefault = true),
      ),
      steps(LaunchSubject(game = null, platform = PS2), rules, setOf(armsx2)),
    )
  }

  @Test
  fun withNoRulesTheBestInstalledEmulatorWins() {
    val installed = setOf("net.pcsx2.emulator", "com.izzy2lost.psx2", armsx2)
    assertEquals(
      RuleStep(FirstInstalled, Wins, LaunchChoice.App(armsx2), isDefault = true),
      steps(jak1, LaunchRules(), installed).last(),
    )
    assertEquals(
      RuleStep(FirstInstalled, Unset, isDefault = true),
      steps(jak1, LaunchRules(), emptySet()).last(),
    )
  }

  @Test
  fun theFallbackFindsForksByPattern() {
    val game = LaunchSubject(game = GameKey.Romm(1), platform = SWITCH)
    val nightly = "dev.eden.eden_emulator.nightly"
    val resolution = standalone(game, LaunchRules(), setOf(nightly))
    assertEquals(nightly, resolution.target.packageName)
    assertEquals(EmulatorId("eden"), resolution.target.emulator?.id)
    assertEquals(LaunchRecipe.ViewRom, resolution.target.recipe)
  }

  @Test
  fun aPatternNeverClaimsAnotherKnownEmulatorsPackage() {
    val game = LaunchSubject(game = GameKey.Romm(1), platform = SNES)
    val rules =
      LaunchRules(platformDefaults = mapOf(SNES to LaunchChoice.App("com.retroarch.aarch64")))
    val target = standalone(game, rules, setOf("com.retroarch.aarch64")).target
    assertEquals(EmulatorId("retroarch_64"), target.emulator?.id)
    // Without a core of its own, it runs the platform's.
    assertEquals(LibretroCores.recommendedFor(SNES), target.core)
  }

  // Genshin Impact: some Eden builds take its package name to get its GPU driver tuning.
  @Test
  fun onlyAnExplicitChoiceSendsAGameToAnAppThatIsNotAnEmulator() {
    val genshin = "com.miHoYo.Yuanshen"
    val zelda = LaunchSubject(game = GameKey.Romm(5), platform = SWITCH)
    assertNull(resolve(zelda, LaunchRules(), setOf(genshin)))

    val rules = LaunchRules(platformDefaults = mapOf(SWITCH to LaunchChoice.App(genshin)))
    val target = standalone(zelda, rules, setOf(genshin)).target
    assertEquals(genshin, target.packageName)
    assertNull(target.emulator)
  }

  @Test
  fun choosingAKnownEmulatorUsesItsRecipe() {
    val rules =
      LaunchRules(platformDefaults = mapOf(PS2 to LaunchChoice.App("xyz.aethersx2.android")))
    val target = standalone(ratchet, rules, setOf(armsx2, "xyz.aethersx2.android")).target
    val nether = assertNotNull(EmulatorRegistry[EmulatorId("nethersx2")])
    assertEquals(nether, target.emulator)
    assertEquals(nether.recipe, target.recipe)
    assertNull(target.core)
  }

  @Test
  fun theBuiltInSessionIsTheDefaultWhereALibretroCoreRunsThePlatform() {
    val gaia = LaunchSubject(game = GameKey.Romm(2), platform = GBA)
    val installed = setOf("com.retroarch")
    assertEquals(
      listOf(
        RuleStep(GameOverride, Unset),
        RuleStep(CollectionOverride(null), Unset),
        RuleStep(BuiltInDefault, Wins, LaunchChoice.BuiltIn(), isDefault = true),
      ),
      steps(gaia, LaunchRules(), installed),
    )
    assertEquals(
      LaunchResolution.BuiltIn(LibretroCores.recommendedFor(GBA)!!, BuiltInDefault),
      resolve(gaia, LaunchRules(), installed),
    )
    // A choice of app outranks it.
    val rules =
      LaunchRules(gameOverrides = mapOf(GameKey.Romm(2) to LaunchChoice.App("com.retroarch")))
    assertEquals(
      RuleStep(BuiltInDefault, Outranked, LaunchChoice.BuiltIn(), isDefault = true),
      steps(gaia, rules, installed).last(),
    )
  }

  @Test
  fun anInstalledEmulatorIsTheDefaultWhereItPlaysBetter() {
    val game = LaunchSubject(game = GameKey.Romm(3), platform = GAMECUBE)
    assertEquals(
      RuleStep(
        FirstInstalled,
        Wins,
        LaunchChoice.App("org.dolphinemu.dolphinemu"),
        isDefault = true,
      ),
      steps(game, LaunchRules(), setOf("org.dolphinemu.dolphinemu")).last(),
    )
    assertEquals(
      RuleStep(BuiltInDefault, Wins, LaunchChoice.BuiltIn(), isDefault = true),
      steps(game, LaunchRules(), emptySet()).last(),
    )
  }

  @Test
  fun aBuiltInChoiceWinsWhereACoreRunsThePlatformAndIsSkippedElsewhere() {
    val rules =
      LaunchRules(
        gameOverrides =
          mapOf(
            GameKey.Romm(1) to LaunchChoice.BuiltIn(),
            GameKey.Romm(2) to LaunchChoice.BuiltIn("mgba_libretro"),
          ),
      )
    val pokemon = LaunchSubject(game = GameKey.Romm(2), platform = GB)
    assertEquals(
      LaunchResolution.BuiltIn(LibretroCores.byLibrary("mgba_libretro")!!, GameOverride),
      resolve(pokemon, rules, emptySet()),
    )
    assertEquals(
      listOf(
        RuleStep(GameOverride, NoCore, LaunchChoice.BuiltIn()),
        RuleStep(CollectionOverride(null), Unset),
        RuleStep(BuiltInDefault, NoCore, LaunchChoice.BuiltIn()),
        RuleStep(FirstInstalled, Wins, LaunchChoice.App(armsx2), isDefault = true),
      ),
      steps(jak1.copy(collections = emptySet()), rules, setOf(armsx2)),
    )
  }

  @Test
  fun aCollectionRuleAppliesToItsPlatformsGamesOnly() {
    val zelda = CollectionId("regular:3")
    val rules =
      LaunchRules(
        collectionRules = listOf(CollectionRule(zelda, SNES, LaunchChoice.App("app.snes"))),
      )
    val installed = setOf("app.snes", "com.retroarch")
    val snesGame = LaunchSubject(GameKey.Romm(1), SNES, setOf(zelda))
    val psxGame = LaunchSubject(GameKey.Romm(2), PSX, setOf(zelda))
    assertEquals("app.snes", standalone(snesGame, rules, installed).target.packageName)
    assertEquals(BuiltInDefault, resolve(psxGame, rules, installed)?.rule)
  }

  @Test
  fun unknownPlatformsStillTakeOverrides() {
    val virtualBoy = PlatformSlug("virtualboy")
    val game = LaunchSubject(game = GameKey.Romm(1), platform = virtualBoy)
    val rules =
      LaunchRules(platformDefaults = mapOf(virtualBoy to LaunchChoice.App("com.retroarch")))
    assertEquals(PlatformDefault, resolve(game, rules, setOf("com.retroarch"))?.rule)
    assertNull(resolve(game, LaunchRules(), setOf("com.retroarch")))
  }

  @Test
  fun aKnownCoreLoadsByItsAndroidLibraryName() {
    val choice = LaunchChoice.App("com.retroarch", core = "mupen64plus_next_libretro")
    val rules = LaunchRules(platformDefaults = mapOf(N64 to choice))
    val game = LaunchSubject(game = GameKey.Romm(1), platform = N64)
    val target = standalone(game, rules, setOf("com.retroarch")).target
    val command = assertNotNull(target.commandFor("/roms/mario.z64", N64, null))
    assertEquals(
      LaunchExtra.StringValue(
        "LIBRETRO",
        "/data/data/com.retroarch/cores/mupen64plus_next_gles3_libretro_android.so",
      ),
      command.extras.first { it.key == "LIBRETRO" },
    )
  }

  @Test
  fun aLocalGamesOwnChoiceKeysOnItsPath() {
    val path = "/storage/emulated/0/ROMs/ps2/Jak X.chd"
    val rules =
      LaunchRules(
        gameOverrides = mapOf(GameKey.Local(path) to LaunchChoice.App(pocketGoal)),
        platformDefaults = mapOf(PS2 to LaunchChoice.App(armsx2)),
      )
    val installed = setOf(armsx2, pocketGoal)
    val local = LaunchSubject(GameKey.Local(path), PS2)
    assertEquals(pocketGoal, standalone(local, rules, installed).target.packageName)
    // Another file, or a ROM, isn't covered by it.
    for (other in listOf(GameKey.Local("$path.copy"), GameKey.Romm(1))) {
      assertEquals(PlatformDefault, resolve(local.copy(game = other), rules, installed)?.rule)
    }
  }

  @Test
  fun aLocalGamesOverrideBecomesItsRomsUnlessTheRomHasOne() {
    val local = GameKey.Local("/storage/emulated/0/ROMs/ps2/Jak.iso")
    val rom = GameKey.Romm(42)
    val adoptions = listOf(Adoption(local, rom))
    fun rules(vararg games: Pair<GameKey, String>) =
      LaunchRules(gameOverrides = games.associate { (key, app) -> key to LaunchChoice.App(app) })
    assertEquals(rules(rom to pocketGoal), rules(local to pocketGoal).adopted(adoptions))
    assertEquals(rules(rom to armsx2), rules(local to pocketGoal, rom to armsx2).adopted(adoptions))
    val other = rules(GameKey.Romm(7) to armsx2)
    assertEquals(other, other.adopted(adoptions))
  }
}
