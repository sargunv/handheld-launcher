package dev.sargunv.handheld.domain.launch

import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.emulator.EmulatorId
import dev.sargunv.handheld.domain.emulator.EmulatorRegistry
import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GB
import dev.sargunv.handheld.domain.platform.PlatformCatalog.GBA
import dev.sargunv.handheld.domain.platform.PlatformCatalog.N64
import dev.sargunv.handheld.domain.platform.PlatformCatalog.NDS
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

class LaunchResolverTest {
  private val armsx2 = "com.armsx2"
  private val pocketGoal = "dev.sargunv.pocketgoal"
  private val jak = CollectionId("jak-and-daxter")

  private val jak1 = LaunchSubject(game = GameKey.Romm(1), platform = PS2, collections = setOf(jak))
  private val jak2 = LaunchSubject(game = GameKey.Romm(2), platform = PS2, collections = setOf(jak))
  private val ratchet = LaunchSubject(game = GameKey.Romm(3), platform = PS2)
  private val okami =
    LaunchSubject(game = GameKey.Romm(4), platform = PS2, collections = setOf(CollectionId("rpg")))

  private fun resolved(resolution: LaunchResolution): LaunchResolution.Resolved =
    assertIs<LaunchResolution.Resolved>(resolution)

  // The vision's first acceptance test. PocketGOAL plays only the Jak games, so nothing picks it
  // for a PS2 game, but a rule naming it beats the platform's default at either level.
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
        gameOverrides =
          mapOf(
            GameKey.Romm(1) to LaunchChoice(pocketGoal),
            GameKey.Romm(2) to LaunchChoice(pocketGoal),
          ),
        platformDefaults = mapOf(PS2 to LaunchChoice(armsx2)),
      )

    for (game in listOf(jak1, jak2)) {
      val viaCollection = resolved(LaunchResolver.resolve(game, byCollection, installed))
      assertEquals(pocketGoal, viaCollection.target.packageName)
      assertEquals(LaunchRule.CollectionOverride(jak), viaCollection.rule)
      assertEquals(EmulatorId("pocketgoal"), viaCollection.target.emulator?.id)
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
    val zelda = LaunchSubject(game = GameKey.Romm(5), platform = SWITCH)

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
  fun aCollectionRuleCanBeLimitedToAPlatform() {
    val zelda = CollectionId("zelda")
    val rules =
      LaunchRules(
        collectionRules = listOf(CollectionRule(zelda, LaunchChoice("app.snes"), platform = SNES)),
      )
    val installed = setOf("app.snes", "com.retroarch")
    val snesGame =
      LaunchSubject(game = GameKey.Romm(1), platform = SNES, collections = setOf(zelda))
    val psxGame = LaunchSubject(game = GameKey.Romm(2), platform = PSX, collections = setOf(zelda))
    assertEquals(
      "app.snes",
      resolved(LaunchResolver.resolve(snesGame, rules, installed)).target.packageName,
    )
    assertEquals(
      LaunchResolution.BuiltIn(LaunchRule.BuiltInDefault, LibretroCores.recommendedFor(PSX)!!),
      LaunchResolver.resolve(psxGame, rules, installed),
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
    val game = LaunchSubject(game = GameKey.Romm(1), platform = SWITCH)
    val resolution =
      resolved(LaunchResolver.resolve(game, LaunchRules(), setOf("dev.eden.eden_emulator.nightly")))
    assertEquals("dev.eden.eden_emulator.nightly", resolution.target.packageName)
    assertEquals(EmulatorId("eden"), resolution.target.emulator?.id)
    assertEquals(LaunchRecipe.ViewRom, resolution.target.recipe)
  }

  @Test
  fun aPatternNeverClaimsAnotherKnownEmulatorsPackage() {
    val game = LaunchSubject(game = GameKey.Romm(1), platform = SNES)
    val rules = LaunchRules(platformDefaults = mapOf(SNES to LaunchChoice("com.retroarch.aarch64")))
    val resolution = resolved(LaunchResolver.resolve(game, rules, setOf("com.retroarch.aarch64")))
    assertEquals(EmulatorId("retroarch_64"), resolution.target.emulator?.id)
  }

  @Test
  fun aTargetWithoutACoreRunsThePlatformsRecommendedOne() {
    val game = LaunchSubject(game = GameKey.Romm(1), platform = SNES)
    val rules = LaunchRules(platformDefaults = mapOf(SNES to LaunchChoice("com.retroarch.aarch64")))
    val resolution = resolved(LaunchResolver.resolve(game, rules, setOf("com.retroarch.aarch64")))
    assertNull(resolution.target.core)
    assertEquals("snes9x_libretro", resolution.target.coreFor(SNES))
  }

  // melonDS DS plays the DS built-in, on both screens, even with a DS emulator installed.
  @Test
  fun theDsPlaysBuiltInEvenWithAnEmulatorInstalled() {
    val game = LaunchSubject(game = GameKey.Romm(1), platform = NDS)
    assertEquals(
      LaunchResolution.BuiltIn(LaunchRule.BuiltInDefault, LibretroCores.recommendedFor(NDS)!!),
      LaunchResolver.resolve(game, LaunchRules(), setOf("com.dsemu.drastic", "me.magnum.melonds")),
    )
  }

  @Test
  fun anyRuleBeatsTheBuiltInDefault() {
    val pokemon = CollectionId("pokemon")
    val game = LaunchSubject(game = GameKey.Romm(1), platform = GBA, collections = setOf(pokemon))
    val installed = setOf("com.example.game", "com.example.collection", "com.example.platform")
    fun resolve(rules: LaunchRules) = resolved(LaunchResolver.resolve(game, rules, installed))

    val byGame =
      resolve(
        LaunchRules(gameOverrides = mapOf(GameKey.Romm(1) to LaunchChoice("com.example.game"))),
      )
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
  fun aBuiltInChoiceKeepsItsCore() {
    val mgba = LaunchChoice.BuiltIn.copy(core = "mgba_libretro")
    assertEquals(
      LaunchResolution.BuiltIn(LaunchRule.GameOverride, LibretroCores.byLibrary("mgba_libretro")!!),
      LaunchResolver.resolve(
        LaunchSubject(game = GameKey.Romm(1), platform = GB),
        LaunchRules(gameOverrides = mapOf(GameKey.Romm(1) to mgba)),
        emptySet(),
      ),
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
  fun noEmulatorInstalledSuggestsTheKnownOnes() {
    val rules = LaunchRules(gameOverrides = mapOf(GameKey.Romm(3) to LaunchChoice(pocketGoal)))
    val resolution = LaunchResolver.resolve(ratchet, rules, setOf("com.example.notes"))
    val none = assertIs<LaunchResolution.NoEmulatorInstalled>(resolution)
    assertEquals(PS2, none.platform)
    assertEquals(EmulatorRegistry.forPlatform(PS2), none.suggestions)
  }

  @Test
  fun unknownPlatformsStillTakeOverrides() {
    val virtualBoy = PlatformSlug("virtualboy")
    val game = LaunchSubject(game = GameKey.Romm(1), platform = virtualBoy)
    val rules = LaunchRules(platformDefaults = mapOf(virtualBoy to LaunchChoice("com.retroarch")))
    assertEquals(
      LaunchRule.PlatformDefault,
      resolved(LaunchResolver.resolve(game, rules, setOf("com.retroarch"))).rule,
    )
    val none = LaunchResolver.resolve(game, LaunchRules(), setOf("com.retroarch"))
    assertEquals(LaunchResolution.NoEmulatorInstalled(virtualBoy, emptyList()), none)
  }

  @Test
  fun aKnownCoreLoadsByItsAndroidLibraryName() {
    val rules =
      LaunchRules(
        platformDefaults =
          mapOf(N64 to LaunchChoice("com.retroarch", core = "mupen64plus_next_libretro")),
      )
    val game = LaunchSubject(game = GameKey.Romm(1), platform = N64)
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

  @Test
  fun aLocalGamesOwnChoiceKeysOnItsPath() {
    val path = "/storage/emulated/0/ROMs/ps2/Jak X.chd"
    val rules =
      LaunchRules(
        gameOverrides = mapOf(GameKey.Local(path) to LaunchChoice(pocketGoal)),
        platformDefaults = mapOf(PS2 to LaunchChoice(armsx2)),
      )
    val installed = setOf(armsx2, pocketGoal)
    val local = LaunchSubject(GameKey.Local(path), PS2)
    val viaGame = resolved(LaunchResolver.resolve(local, rules, installed))
    assertEquals(pocketGoal, viaGame.target.packageName)
    assertEquals(LaunchRule.GameOverride, viaGame.rule)
    // Another file, or a ROM, isn't covered by it.
    for (other in listOf(GameKey.Local("$path.copy"), GameKey.Romm(1))) {
      val viaPlatform = resolved(LaunchResolver.resolve(local.copy(game = other), rules, installed))
      assertEquals(LaunchRule.PlatformDefault, viaPlatform.rule)
    }
  }
}
