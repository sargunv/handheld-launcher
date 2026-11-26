package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GAMECUBE
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GB
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.N64
import dev.sargunv.handheld.domain.platform.PlatformCatalog.NDS
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PS2
import dev.sargunv.handheld.domain.platform.PlatformCatalog.PSX
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SNES
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SUPER_FAMICOM
import dev.sargunv.handheld.domain.platform.PlatformCatalog.SWITCH
import dev.sargunv.handheld.domain.platform.PlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LaunchResolverTest {
  private val armsx2 = "com.armsx2"
  private val pocketGoal = "dev.sargunv.pocketgoal"
  private val jak = CollectionId("jak-and-daxter")

  private val jak1 = LaunchSubject(gameId = 1, platform = PS2, collections = setOf(jak))
  private val jak2 = LaunchSubject(gameId = 2, platform = PS2, collections = setOf(jak))
  private val ratchet = LaunchSubject(gameId = 3, platform = PS2)
  private val okami =
    LaunchSubject(gameId = 4, platform = PS2, collections = setOf(CollectionId("rpg")))

  private fun resolved(resolution: LaunchResolution): LaunchResolution.Resolved =
    assertIs<LaunchResolution.Resolved>(resolution)

  // The vision's first acceptance test. PocketGOAL is an app the registry doesn't know, and it
  // still beats the platform's known default at either level.
  @Test
  fun jakGamesOpenInPocketGoalAndOtherPs2GamesInArmsx2() {
    val installed = setOf(armsx2, pocketGoal, "xyz.aethersx2.android")
    val byCollection =
      LaunchRules(
        collectionRules = listOf(CollectionRule(jak, LaunchChoice(pocketGoal))),
        platformDefaults = mapOf(PS2 to LaunchChoice(armsx2)),
      )
    val byGame =
      LaunchRules(
        gameOverrides = mapOf(1L to LaunchChoice(pocketGoal), 2L to LaunchChoice(pocketGoal)),
        platformDefaults = mapOf(PS2 to LaunchChoice(armsx2)),
      )

    for (game in listOf(jak1, jak2)) {
      val viaCollection = resolved(LaunchResolver.resolve(game, byCollection, installed))
      assertEquals(pocketGoal, viaCollection.target.packageName)
      assertEquals(LaunchRule.CollectionOverride(jak), viaCollection.rule)
      assertNull(viaCollection.target.emulator)
      assertEquals(LaunchRecipe.ViewRom, viaCollection.target.recipe)

      val viaGame = resolved(LaunchResolver.resolve(game, byGame, installed))
      assertEquals(pocketGoal, viaGame.target.packageName)
      assertEquals(LaunchRule.GameOverride, viaGame.rule)
    }

    for (game in listOf(ratchet, okami)) {
      for (rules in listOf(byCollection, byGame)) {
        val other = resolved(LaunchResolver.resolve(game, rules, installed))
        assertEquals(armsx2, other.target.packageName)
        assertEquals(LaunchRule.PlatformDefault, other.rule)
        assertEquals(EmulatorId("armsx2"), other.target.emulator?.id)
        assertEquals(other.target.emulator?.recipe, other.target.recipe)
      }
    }
  }

  // Genshin Impact: some Eden builds take its package name to get its GPU driver tuning.
  @Test
  fun onlyAnExplicitChoiceSendsAGameToAnAppThatIsNotAnEmulator() {
    val genshin = "com.miHoYo.Yuanshen"
    val zelda = LaunchSubject(gameId = 5, platform = SWITCH)

    assertIs<LaunchResolution.NoEmulatorInstalled>(
      LaunchResolver.resolve(zelda, LaunchRules(), setOf(genshin)),
    )

    val chosen =
      resolved(
        LaunchResolver.resolve(
          zelda,
          LaunchRules(platformDefaults = mapOf(SWITCH to LaunchChoice(genshin))),
          setOf(genshin),
        ),
      )
    assertEquals(genshin, chosen.target.packageName)
    assertNull(chosen.target.emulator)
  }

  @Test
  fun gameOverrideBeatsCollectionBeatsPlatform() {
    val installed = setOf("a.game", "b.collection", "c.platform")
    val rules =
      LaunchRules(
        gameOverrides = mapOf(1L to LaunchChoice("a.game")),
        collectionRules = listOf(CollectionRule(jak, LaunchChoice("b.collection"))),
        platformDefaults = mapOf(PS2 to LaunchChoice("c.platform")),
      )
    assertEquals(
      "a.game",
      resolved(LaunchResolver.resolve(jak1, rules, installed)).target.packageName,
    )
    assertEquals(
      "b.collection",
      resolved(LaunchResolver.resolve(jak2, rules, installed)).target.packageName,
    )
    assertEquals(
      "c.platform",
      resolved(LaunchResolver.resolve(ratchet, rules, installed)).target.packageName,
    )
  }

  @Test
  fun theFirstApplicableCollectionRuleWins() {
    val favorites = CollectionId("favorites")
    val game = LaunchSubject(gameId = 9, platform = PS2, collections = setOf(jak, favorites))
    val rules =
      LaunchRules(
        collectionRules =
          listOf(
            CollectionRule(favorites, LaunchChoice("app.favorites")),
            CollectionRule(jak, LaunchChoice(pocketGoal)),
          ),
      )
    val resolution =
      resolved(LaunchResolver.resolve(game, rules, setOf("app.favorites", pocketGoal)))
    assertEquals("app.favorites", resolution.target.packageName)
    assertEquals(LaunchRule.CollectionOverride(favorites), resolution.rule)
  }

  @Test
  fun aCollectionRuleCanBeLimitedToAPlatform() {
    val zelda = CollectionId("zelda")
    val rules =
      LaunchRules(
        collectionRules = listOf(CollectionRule(zelda, LaunchChoice("app.snes"), platform = SNES)),
      )
    val installed = setOf("app.snes", "com.retroarch")
    val snesGame = LaunchSubject(gameId = 1, platform = SNES, collections = setOf(zelda))
    val psxGame = LaunchSubject(gameId = 2, platform = PSX, collections = setOf(zelda))
    assertEquals(
      "app.snes",
      resolved(LaunchResolver.resolve(snesGame, rules, installed)).target.packageName,
    )
    assertEquals(
      LaunchResolution.BuiltIn(LaunchRule.BuiltInDefault),
      LaunchResolver.resolve(psxGame, rules, installed),
    )
  }

  @Test
  fun choicesForMissingAppsAreSkippedAndReported() {
    val rules =
      LaunchRules(
        gameOverrides = mapOf(1L to LaunchChoice(pocketGoal)),
        platformDefaults = mapOf(PS2 to LaunchChoice("net.pcsx2.emulator")),
      )
    val resolution = resolved(LaunchResolver.resolve(jak1, rules, setOf(armsx2)))
    assertEquals(armsx2, resolution.target.packageName)
    assertEquals(LaunchRule.FirstInstalled, resolution.rule)
    assertEquals(
      listOf(
        SkippedChoice(LaunchRule.GameOverride, pocketGoal),
        SkippedChoice(LaunchRule.PlatformDefault, "net.pcsx2.emulator"),
      ),
      resolution.skipped,
    )
  }

  @Test
  fun theFallbackFollowsThePreferenceOrder() {
    val installed = setOf("net.pcsx2.emulator", "com.izzy2lost.psx2", armsx2)
    val resolution = resolved(LaunchResolver.resolve(ratchet, LaunchRules(), installed))
    assertEquals(armsx2, resolution.target.packageName)
    assertEquals(LaunchRule.FirstInstalled, resolution.rule)
  }

  @Test
  fun theFallbackFindsForksByPattern() {
    val game = LaunchSubject(gameId = 1, platform = SWITCH)
    val resolution =
      resolved(LaunchResolver.resolve(game, LaunchRules(), setOf("dev.eden.eden_emulator.nightly")))
    assertEquals("dev.eden.eden_emulator.nightly", resolution.target.packageName)
    assertEquals(EmulatorId("eden"), resolution.target.emulator?.id)
    assertEquals(LaunchRecipe.ViewRom, resolution.target.recipe)
  }

  @Test
  fun aPatternNeverClaimsAnotherKnownEmulatorsPackage() {
    val game = LaunchSubject(gameId = 1, platform = SNES)
    val rules = LaunchRules(platformDefaults = mapOf(SNES to LaunchChoice("com.retroarch.aarch64")))
    val resolution = resolved(LaunchResolver.resolve(game, rules, setOf("com.retroarch.aarch64")))
    assertEquals(EmulatorId("retroarch_64"), resolution.target.emulator?.id)
  }

  @Test
  fun aTargetWithoutACoreRunsThePlatformsRecommendedOne() {
    val game = LaunchSubject(gameId = 1, platform = SNES)
    val rules = LaunchRules(platformDefaults = mapOf(SNES to LaunchChoice("com.retroarch.aarch64")))
    val resolution = resolved(LaunchResolver.resolve(game, rules, setOf("com.retroarch.aarch64")))
    assertNull(resolution.target.core)
    assertEquals("snes9x_libretro", resolution.target.coreFor(SNES))
  }

  // Installed emulators wait behind the built-in session wherever a libretro core runs the
  // platform, twins included.
  @Test
  fun aPlatformALibretroCoreRunsPlaysBuiltInByDefault() {
    val installed = setOf("com.retroarch.aarch64", "com.zsnes.superzsnes", "com.example.gba")
    for (platform in listOf(GBA, SNES, SUPER_FAMICOM)) {
      assertEquals(
        LaunchResolution.BuiltIn(LaunchRule.BuiltInDefault),
        LaunchResolver.resolve(
          LaunchSubject(gameId = 1, platform = platform),
          LaunchRules(),
          installed,
        ),
      )
    }
  }

  // GameCube, Wii, 3DS and PSP play better in their own emulators, so the built-in session waits
  // for none to be installed.
  @Test
  fun anInstalledEmulatorComesFirstWhereItPlaysBetter() {
    val game = LaunchSubject(gameId = 1, platform = GAMECUBE)
    val withDolphin =
      resolved(LaunchResolver.resolve(game, LaunchRules(), setOf("org.dolphinemu.dolphinemu")))
    assertEquals("org.dolphinemu.dolphinemu", withDolphin.target.packageName)
    assertEquals(LaunchRule.FirstInstalled, withDolphin.rule)
    assertEquals(
      LaunchResolution.BuiltIn(LaunchRule.BuiltInDefault),
      LaunchResolver.resolve(game, LaunchRules(), emptySet()),
    )
  }

  // melonDS DS plays the DS built-in, on both screens, even with a DS emulator installed.
  @Test
  fun theDsPlaysBuiltInEvenWithAnEmulatorInstalled() {
    val game = LaunchSubject(gameId = 1, platform = NDS)
    assertEquals(
      LaunchResolution.BuiltIn(LaunchRule.BuiltInDefault),
      LaunchResolver.resolve(game, LaunchRules(), setOf("com.dsemu.drastic", "me.magnum.melonds")),
    )
  }

  @Test
  fun anyRuleBeatsTheBuiltInDefault() {
    val pokemon = CollectionId("pokemon")
    val game = LaunchSubject(gameId = 1, platform = GBA, collections = setOf(pokemon))
    val installed = setOf("com.example.game", "com.example.collection", "com.example.platform")
    fun resolve(rules: LaunchRules) = resolved(LaunchResolver.resolve(game, rules, installed))

    val byGame = resolve(LaunchRules(gameOverrides = mapOf(1L to LaunchChoice("com.example.game"))))
    assertEquals(LaunchRule.GameOverride, byGame.rule)
    val byCollection =
      resolve(
        LaunchRules(
          collectionRules = listOf(CollectionRule(pokemon, LaunchChoice("com.example.collection"))),
        ),
      )
    assertEquals(LaunchRule.CollectionOverride(pokemon), byCollection.rule)
    val byPlatform =
      resolve(LaunchRules(platformDefaults = mapOf(GBA to LaunchChoice("com.example.platform"))))
    assertEquals(LaunchRule.PlatformDefault, byPlatform.rule)
  }

  @Test
  fun theBuiltInSessionCanBeChosenAtEveryLevel() {
    val pokemon = CollectionId("pokemon")
    val game = LaunchSubject(gameId = 1, platform = GB, collections = setOf(pokemon))
    val installed = setOf("com.retroarch")
    val app = LaunchChoice("com.retroarch")
    val mgba = LaunchChoice.BuiltIn.copy(core = "mgba_libretro")

    assertEquals(
      LaunchResolution.BuiltIn(LaunchRule.GameOverride, "mgba_libretro"),
      LaunchResolver.resolve(
        game,
        LaunchRules(gameOverrides = mapOf(1L to mgba), platformDefaults = mapOf(GB to app)),
        installed,
      ),
    )
    assertEquals(
      LaunchResolution.BuiltIn(LaunchRule.CollectionOverride(pokemon)),
      LaunchResolver.resolve(
        game,
        LaunchRules(
          collectionRules = listOf(CollectionRule(pokemon, LaunchChoice.BuiltIn)),
          platformDefaults = mapOf(GB to app),
        ),
        installed,
      ),
    )
    assertEquals(
      LaunchResolution.BuiltIn(LaunchRule.PlatformDefault),
      LaunchResolver.resolve(
        game,
        LaunchRules(platformDefaults = mapOf(GB to LaunchChoice.BuiltIn)),
        installed,
      ),
    )
  }

  @Test
  fun theBuiltInSessionIsSkippedWhereNoLibretroCoreRuns() {
    val rules = LaunchRules(gameOverrides = mapOf(3L to LaunchChoice.BuiltIn))
    val resolution = resolved(LaunchResolver.resolve(ratchet, rules, setOf(armsx2)))
    assertEquals(armsx2, resolution.target.packageName)
    assertEquals(LaunchRule.FirstInstalled, resolution.rule)
    assertEquals(
      listOf(SkippedChoice(LaunchRule.GameOverride, LaunchChoice.BUILT_IN)),
      resolution.skipped,
    )
  }

  @Test
  fun choosingAKnownEmulatorUsesItsRecipe() {
    val rules = LaunchRules(platformDefaults = mapOf(PS2 to LaunchChoice("xyz.aethersx2.android")))
    val resolution =
      resolved(LaunchResolver.resolve(ratchet, rules, setOf(armsx2, "xyz.aethersx2.android")))
    val nether = assertNotNull(EmulatorRegistry[EmulatorId("nethersx2")])
    assertEquals(nether, resolution.target.emulator)
    assertEquals(nether.recipe, resolution.target.recipe)
  }

  @Test
  fun aChoiceCanCarryItsOwnRecipe() {
    val rules =
      LaunchRules(
        gameOverrides = mapOf(1L to LaunchChoice(pocketGoal, recipe = LaunchRecipe.OpenApp)),
      )
    val resolution = resolved(LaunchResolver.resolve(jak1, rules, setOf(pocketGoal)))
    assertEquals(LaunchRecipe.OpenApp, resolution.target.recipe)
  }

  @Test
  fun noEmulatorInstalledSuggestsTheKnownOnes() {
    val rules = LaunchRules(gameOverrides = mapOf(3L to LaunchChoice(pocketGoal)))
    val resolution = LaunchResolver.resolve(ratchet, rules, setOf("com.example.notes"))
    val none = assertIs<LaunchResolution.NoEmulatorInstalled>(resolution)
    assertEquals(PS2, none.platform)
    assertEquals(EmulatorRegistry.forPlatform(PS2), none.suggestions)
    assertEquals(listOf(SkippedChoice(LaunchRule.GameOverride, pocketGoal)), none.skipped)
  }

  @Test
  fun unknownPlatformsStillTakeOverrides() {
    val virtualBoy = PlatformSlug("virtualboy")
    val game = LaunchSubject(gameId = 1, platform = virtualBoy)
    val rules = LaunchRules(platformDefaults = mapOf(virtualBoy to LaunchChoice("com.retroarch")))
    assertEquals(
      LaunchRule.PlatformDefault,
      resolved(LaunchResolver.resolve(game, rules, setOf("com.retroarch"))).rule,
    )
    val none = LaunchResolver.resolve(game, LaunchRules(), setOf("com.retroarch"))
    assertEquals(LaunchResolution.NoEmulatorInstalled(virtualBoy, emptyList()), none)
  }

  @Test
  fun aTargetBuildsItsCommandWithItsCore() {
    val rules =
      LaunchRules(
        platformDefaults = mapOf(SNES to LaunchChoice("com.retroarch", core = "bsnes_libretro")),
      )
    val game = LaunchSubject(gameId = 1, platform = SNES)
    val target = resolved(LaunchResolver.resolve(game, rules, setOf("com.retroarch"))).target
    val command =
      assertIs<LaunchBuild.Ready>(target.commandFor(LaunchInput("/roms/zelda.sfc", SNES))).command
    assertEquals(
      LaunchExtra.StringValue(
        "LIBRETRO",
        "/data/data/com.retroarch/cores/bsnes_libretro_android.so",
      ),
      command.extras.first { it.key == "LIBRETRO" },
    )
  }

  @Test
  fun aKnownCoreLoadsByItsAndroidLibraryName() {
    val rules =
      LaunchRules(
        platformDefaults =
          mapOf(N64 to LaunchChoice("com.retroarch", core = "mupen64plus_next_libretro")),
      )
    val game = LaunchSubject(gameId = 1, platform = N64)
    val target = resolved(LaunchResolver.resolve(game, rules, setOf("com.retroarch"))).target
    val command =
      assertIs<LaunchBuild.Ready>(target.commandFor(LaunchInput("/roms/mario.z64", N64))).command
    assertEquals(
      LaunchExtra.StringValue(
        "LIBRETRO",
        "/data/data/com.retroarch/cores/mupen64plus_next_gles3_libretro_android.so",
      ),
      command.extras.first { it.key == "LIBRETRO" },
    )
  }
}
