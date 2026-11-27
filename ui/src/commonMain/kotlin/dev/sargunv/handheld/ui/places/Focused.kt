package dev.sargunv.handheld.ui.places

import androidx.compose.runtime.Immutable
import com.mikepenz.aboutlibraries.entity.Library
import dev.sargunv.handheld.domain.Collection
import dev.sargunv.handheld.domain.LibraryGame

/** What the lower screen describes: the item focused on the upper one. */
@Immutable
sealed interface Focused {
  data class OnGame(val game: LibraryGame) : Focused

  data class OnCollection(val collection: Collection) : Focused

  data class OnComponent(val library: Library) : Focused
}
