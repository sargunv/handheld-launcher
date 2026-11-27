package dev.sargunv.handheld.ui

/** A note's text: [label], then `· detail` when there's a [detail]. */
fun labelled(label: String, detail: String?): String =
  if (detail.isNullOrBlank()) label else "$label · $detail"
