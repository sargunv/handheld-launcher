package dev.sargunv.handheld.data

import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.serializer

class JsonFileTest {
  private val dir = createTempDirectory("json-file-test")
  private val file = dir.resolve("value.json")
  private val jsonFile = JsonFile(Path(file.toString()), Int.serializer())

  @AfterTest
  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  fun deleteDir() = dir.deleteRecursively()

  @Test
  fun aFileThatDoesntDecodeReadsAsMissingUntilTheNextWrite() = runTest {
    file.writeText("""{"damaged":""")
    assertNull(jsonFile.read())
    jsonFile.write(1)
    assertEquals(1, jsonFile.read())
  }

  @Test
  fun aFileThatCantBeOpenedReadsAsMissing() = runTest {
    file.toFile().mkdirs()
    assertNull(jsonFile.read())
  }
}
