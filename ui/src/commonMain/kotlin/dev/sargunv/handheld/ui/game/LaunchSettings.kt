package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.GameKey
import dev.sargunv.handheld.domain.launch.CollectionId
import dev.sargunv.handheld.domain.launch.CollectionRule
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules
import dev.sargunv.handheld.domain.platform.PlatformSlug
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

/** How far a choice of app reaches. */
sealed interface RuleLevel {
  data class Game(val game: GameKey) : RuleLevel

  /** The collection's games on [platform], since the app picked for one platform's games. */
  data class InCollection(val collection: CollectionId, val platform: PlatformSlug) : RuleLevel

  data class Platform(val platform: PlatformSlug) : RuleLevel
}

/**
 * The apps the user picked to open games in, on the Open with screen: for single games by ROM id,
 * for local games by their file's path, for collections, and for platforms by slug.
 */
@Serializable
data class LaunchSettings(
  val games: Map<Long, SavedChoice> = emptyMap(),
  /** The choices for local games, by their file's path, which they keep while the file stays. */
  val localGames: Map<String, SavedChoice> = emptyMap(),
  val collections: List<SavedCollectionRule> = emptyList(),
  val platforms: Map<String, SavedChoice> = emptyMap(),
) {
  /** These choices as the rules the resolver applies. */
  fun toRules(): LaunchRules =
    LaunchRules(
      gameOverrides =
        games.entries.associate { GameKey.Romm(it.key) to it.value.toChoice() } +
          localGames.entries.associate { GameKey.Local(it.key) to it.value.toChoice() },
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

  /** The app picked at [level], or null when there's none. */
  fun choiceAt(level: RuleLevel): SavedChoice? =
    when (level) {
      is RuleLevel.Game ->
        when (val game = level.game) {
          is GameKey.Romm -> games[game.romId]
          is GameKey.Local -> localGames[game.path]
        }
      is RuleLevel.InCollection -> collections.firstOrNull { it.matches(level) }?.choice
      is RuleLevel.Platform -> platforms[level.platform.value]
    }

  /** These settings with [choice] picked at [level], or with its choice cleared when null. */
  fun withChoice(level: RuleLevel, choice: SavedChoice?): LaunchSettings =
    when (level) {
      is RuleLevel.Game ->
        when (val game = level.game) {
          is GameKey.Romm ->
            copy(
              games = if (choice == null) games - game.romId else games + (game.romId to choice),
            )
          is GameKey.Local ->
            copy(
              localGames =
                if (choice == null) localGames - game.path else localGames + (game.path to choice),
            )
        }
      is RuleLevel.InCollection -> {
        val index = collections.indexOfFirst { it.matches(level) }
        val rule = choice?.let {
          SavedCollectionRule(level.collection.value, level.platform.value, it)
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
      is RuleLevel.Platform -> {
        val key = level.platform.value
        copy(platforms = if (choice == null) platforms - key else platforms + (key to choice))
      }
    }

  private fun SavedCollectionRule.matches(level: RuleLevel.InCollection) =
    collection == level.collection.value && platform == level.platform.value
}

/** The collection's id, as launch rules name it: its kind and id, as each kind has its own ids. */
val Collection.launchId: CollectionId
  get() = CollectionId("${kind.name.lowercase()}:$id")
