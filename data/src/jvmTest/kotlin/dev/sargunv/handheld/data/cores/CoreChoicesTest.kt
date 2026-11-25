package dev.sargunv.handheld.data.cores

import dev.sargunv.handheld.domain.emulator.LibretroCores
import dev.sargunv.handheld.domain.platform.PlatformCatalog
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class CoreChoicesTest {
  private val dir = Files.createTempDirectory("core-choices-test").toFile()
  private val path = Path(dir.path, "choices.json")

  @AfterTest fun deleteDir() = dir.deleteRecursively().let {}

  @Test
  fun keepsAChoiceAndGoesBackToTheRecommendedCore() = runTest {
    val mgba = LibretroCores.byLibrary("mgba_libretro")!!
    CoreChoices(path).choose(PlatformCatalog.GB, mgba)

    val choices = CoreChoices(path)
    assertEquals(mgba, choices.coreFor(PlatformCatalog.GB))
    assertEquals("gambatte_libretro", choices.coreFor(PlatformCatalog.GBC)?.library)
    assertEquals(mapOf(PlatformCatalog.GB to "mgba_libretro"), choices.overrides())

    choices.choose(PlatformCatalog.GB, null)
    assertEquals("gambatte_libretro", choices.coreFor(PlatformCatalog.GB)?.library)
    assertEquals(emptyMap(), choices.overrides())
  }
}
