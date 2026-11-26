package dev.sargunv.handheld.ui.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class InputSettingsTest {
  @Test
  fun readsAConfirmButtonStoredByPosition() {
    // Settings stored before the face buttons were named by key code.
    val stored = """{"confirmButton":"East"}"""
    assertEquals(
      InputSettings(confirmButton = ConfirmButton.B, faceButtonLayout = FaceButtonLayout.Xbox),
      Json.decodeFromString(InputSettings.serializer(), stored),
    )
  }

  @Test
  fun keepsTheFaceButtonLayout() {
    val settings = InputSettings(faceButtonLayout = FaceButtonLayout.Nintendo)
    val json = Json.encodeToString(InputSettings.serializer(), settings)
    assertEquals(settings, Json.decodeFromString(InputSettings.serializer(), json))
  }
}
