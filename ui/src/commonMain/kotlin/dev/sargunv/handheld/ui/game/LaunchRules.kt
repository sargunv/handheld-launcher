package dev.sargunv.handheld.ui.game

import dev.sargunv.handheld.domain.launch.CollectionRule
import dev.sargunv.handheld.domain.launch.LaunchChoice
import dev.sargunv.handheld.domain.launch.LaunchRules

/** The app picked for [scope], or null when there's none. */
fun LaunchRules.choiceAt(scope: OpenWithScope): LaunchChoice? =
  when (scope) {
    is OpenWithScope.Game -> gameOverrides[scope.game]
    is OpenWithScope.Collection -> collectionRules.firstOrNull { it.matches(scope) }?.choice
    is OpenWithScope.Platform -> platformDefaults[scope.platform]
  }

/** These rules with [choice] picked for [scope], or with its choice cleared when null. */
fun LaunchRules.withChoice(scope: OpenWithScope, choice: LaunchChoice?): LaunchRules =
  when (scope) {
    is OpenWithScope.Game ->
      copy(
        gameOverrides =
          if (choice == null) gameOverrides - scope.game
          else gameOverrides + (scope.game to choice),
      )
    is OpenWithScope.Collection -> {
      val index = collectionRules.indexOfFirst { it.matches(scope) }
      val rule = choice?.let { CollectionRule(scope.collection, scope.platform, it) }
      copy(
        collectionRules =
          when {
            index < 0 -> collectionRules + listOfNotNull(rule)
            rule == null -> collectionRules.filterIndexed { i, _ -> i != index }
            else -> collectionRules.toMutableList().also { it[index] = rule }
          },
      )
    }
    is OpenWithScope.Platform ->
      copy(
        platformDefaults =
          if (choice == null) platformDefaults - scope.platform
          else platformDefaults + (scope.platform to choice),
      )
  }

private fun CollectionRule.matches(scope: OpenWithScope.Collection) =
  collection == scope.collection && platform == scope.platform
