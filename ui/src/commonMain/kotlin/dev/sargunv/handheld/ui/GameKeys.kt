package dev.sargunv.handheld.ui

import dev.sargunv.handheld.domain.GameKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A [GameKey] as settings store it: `romm:<id>` or `local:<path>`. */
object GameKeySerializer : KSerializer<GameKey> {
  override val descriptor =
    PrimitiveSerialDescriptor("dev.sargunv.handheld.GameKey", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: GameKey) =
    encoder.encodeString(
      when (value) {
        is GameKey.Romm -> "$ROMM${value.romId}"
        is GameKey.Local -> "$LOCAL${value.path}"
      },
    )

  override fun deserialize(decoder: Decoder): GameKey {
    val text = decoder.decodeString()
    return when {
      text.startsWith(ROMM) -> text.removePrefix(ROMM).toLongOrNull()?.let(GameKey::Romm)
      text.startsWith(LOCAL) -> GameKey.Local(text.removePrefix(LOCAL))
      else -> null
    } ?: throw SerializationException("No game key: $text")
  }

  private const val ROMM = "romm:"
  private const val LOCAL = "local:"
}
