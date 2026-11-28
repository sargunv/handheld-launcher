package dev.sargunv.handheld.data

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.builtins.serializer

class JsonFileTest {
  private val dir = createTempDirectory("json-file-test").toFile()
  private val file = File(dir, "value.json")
  private val jsonFile = JsonFile(file, Int.serializer())

  @AfterTest fun cleanUp() = check(dir.deleteRecursively())

  @Test
  fun aFileThatDoesntDecodeReadsAsMissingUntilTheNextWrite() {
    file.writeText("""{"damaged":""")
    assertNull(jsonFile.read())
    jsonFile.write(1)
    assertEquals(1, jsonFile.read())
  }

  @Test
  fun aFileThatCantBeOpenedReadsAsMissing() {
    file.mkdirs()
    assertNull(jsonFile.read())
  }
}
