package dev.sargunv.handheld.data.romm

import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Reads RomM's ISO 8601 timestamps, which may come without an offset. */
internal object RommInstantSerializer : KSerializer<Instant> {
  override val descriptor = PrimitiveSerialDescriptor("RommInstant", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): Instant {
    val text = decoder.decodeString()
    // RomM stores times in UTC, and writes a few of them without saying so.
    return Instant.parseOrNull(text) ?: Instant.parse(text + "Z")
  }

  override fun serialize(encoder: Encoder, value: Instant) {
    encoder.encodeString(value.toString())
  }
}
