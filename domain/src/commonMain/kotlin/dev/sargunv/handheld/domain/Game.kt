package dev.sargunv.handheld.domain

/** A game in the library, identified by its RomM ROM id. */
data class Game(val rommId: Long, val title: String, val platformSlug: String)
