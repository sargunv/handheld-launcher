package dev.sargunv.handheld.ui

import dev.sargunv.handheld.domain.Adoption
import dev.sargunv.handheld.domain.GameKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A [GameKey] as settings store it: a ROM by its id, a local game as `local:<path>`. */
object GameKeySerializer : KSerializer<GameKey> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("dev.sargunv.handheld.GameKey", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: GameKey) = encoder.encodeString(value.stored())

  override fun deserialize(decoder: Decoder): GameKey {
    val text = decoder.decodeString()
    if (text.startsWith(LOCAL)) return GameKey.Local(text.removePrefix(LOCAL))
    return GameKey.Romm(text.toLongOrNull() ?: throw SerializationException("No game key: $text"))
  }

  fun GameKey.stored(): String =
    when (this) {
      is GameKey.Romm -> romId.toString()
      is GameKey.Local -> LOCAL + path
    }

  private const val LOCAL = "local:"
}

/**
 * [value] with what each of the [adoptions]' local games keeps in it handed to its ROM. Every map
 * keyed by [GameKey] in [value] is found from its serial form, so a setting kept per game is handed
 * over without being listed anywhere: the local game's entry moves to its ROM, unless the ROM has
 * one of its own, which stays.
 */
internal fun <T> adoptedIn(serializer: KSerializer<T>, value: T, adoptions: List<Adoption>): T {
  if (adoptions.isEmpty()) return value
  val tree = json.encodeToJsonElement(serializer, value)
  val adopted = adoptedIn(tree, serializer.descriptor, adoptions)
  return if (adopted == tree) value else json.decodeFromJsonElement(serializer, adopted)
}

private fun adoptedIn(
  element: JsonElement,
  descriptor: SerialDescriptor,
  adoptions: List<Adoption>,
): JsonElement {
  if (descriptor.isInline) return adoptedIn(element, descriptor.getElementDescriptor(0), adoptions)
  return when (descriptor.kind) {
    StructureKind.MAP -> {
      if (element !is JsonObject) return element
      val values = descriptor.getElementDescriptor(1)
      val entries = element.mapValuesTo(LinkedHashMap()) { adoptedIn(it.value, values, adoptions) }
      if (
        descriptor.getElementDescriptor(0).serialName == GameKeySerializer.descriptor.serialName
      ) {
        with(GameKeySerializer) {
          for (adoption in adoptions) {
            val kept = entries.remove(adoption.local.stored()) ?: continue
            entries.getOrPut(adoption.rom.stored()) { kept }
          }
        }
      }
      JsonObject(entries)
    }
    StructureKind.CLASS,
    StructureKind.OBJECT -> {
      if (element !is JsonObject) return element
      JsonObject(
        element.mapValues { (name, child) ->
          val index = descriptor.getElementIndex(name)
          if (index == CompositeDecoder.UNKNOWN_NAME) child
          else adoptedIn(child, descriptor.getElementDescriptor(index), adoptions)
        },
      )
    }
    StructureKind.LIST -> {
      if (element !is JsonArray) return element
      JsonArray(element.map { adoptedIn(it, descriptor.getElementDescriptor(0), adoptions) })
    }
    // Primitives hold no maps. Nothing in the settings is polymorphic.
    else -> element
  }
}

private val json = Json { encodeDefaults = true }
