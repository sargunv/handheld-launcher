package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.CollectionRule
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.platform.PlatformSlug
import dev.sargunv.handheld.ui.GameKeySerializer
import kotlinx.serialization.Serializable

/** An app a rule names, and the libretro core it loads, by library name, for apps that load one. */
@Serializable
data class SavedChoice(val packageName: String, val core: String? = null) {
  fun toChoice() = LaunchChoice(packageName, core = core)
}

/** A collection's choice of app for its games on [platform]. */
@Serializable
data class SavedCollectionRule(
  val collection: String,
  val platform: String,
  val choice: SavedChoice,
)

/**
 * The apps the user picked to open games in, on the Open with screen: for single games by their
 * [GameKey], for collections, and for platforms by slug. A local game's choice goes to its ROM once
 * its file becomes one ([adopted]).
 */
@Serializable
data class LaunchSettings(
  val games: Map<@Serializable(with = GameKeySerializer::class) GameKey, SavedChoice> = emptyMap(),
  val collections: List<SavedCollectionRule> = emptyList(),
  val platforms: Map<String, SavedChoice> = emptyMap(),
) {
  /** These choices as the rules the resolver applies. */
  fun toRules(): LaunchRules =
    LaunchRules(
      gameOverrides = games.mapValues { it.value.toChoice() },
      collectionRules =
        collections.map {
          CollectionRule(
            CollectionId(it.collection),
            it.choice.toChoice(),
            PlatformSlug(it.platform),
          )
        },
      platformDefaults =
        platforms.entries.associate { PlatformSlug(it.key) to it.value.toChoice() },
    )

  /** The app picked for [scope], or null when there's none. */
  fun choiceAt(scope: OpenWithScope): SavedChoice? =
    when (scope) {
      is OpenWithScope.Game -> games[scope.game]
      is OpenWithScope.Collection -> collections.firstOrNull { it.matches(scope) }?.choice
      is OpenWithScope.Platform -> platforms[scope.platform.value]
    }

  /** These settings with [choice] picked for [scope], or with its choice cleared when null. */
  fun withChoice(scope: OpenWithScope, choice: SavedChoice?): LaunchSettings =
    when (scope) {
      is OpenWithScope.Game ->
        copy(games = if (choice == null) games - scope.game else games + (scope.game to choice))
      is OpenWithScope.Collection -> {
        val index = collections.indexOfFirst { it.matches(scope) }
        val rule = choice?.let {
          SavedCollectionRule(scope.collection.value, scope.platform.value, it)
        }
        copy(
          collections =
            when {
              index < 0 -> collections + listOfNotNull(rule)
              rule == null -> collections.filterIndexed { i, _ -> i != index }
              else -> collections.toMutableList().also { it[index] = rule }
            },
        )
      }
      is OpenWithScope.Platform -> {
        val key = scope.platform.value
        copy(platforms = if (choice == null) platforms - key else platforms + (key to choice))
      }
    }

  private fun SavedCollectionRule.matches(scope: OpenWithScope.Collection) =
    collection == scope.collection.value && platform == scope.platform.value
}

/** The collection's id, as launch rules name it: its kind and id, as each kind has its own ids. */
val Collection.launchId: CollectionId
  get() = CollectionId("${kind.name.lowercase()}:$id")
