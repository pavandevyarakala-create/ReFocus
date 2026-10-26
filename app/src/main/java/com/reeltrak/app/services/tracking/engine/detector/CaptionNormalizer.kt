package com.reeltrak.app.services.tracking.engine.detector

/**
 * Platform-agnostic caption normalizer for Instagram Reels and YouTube Shorts.
 *
 * Normalizes dynamic and volatile UI representations:
 * - Trims trailing UI expansion suffixes: "... more", "…more", "...", "…", trailing " more"
 * - Preserves hashtag keywords while stripping the leading '#' symbol (e.g. "#fitness" -> "fitness")
 * - Preserves ordinary words containing "more" (e.g. "More dogs", "Learn more", "smores")
 * - Collapses consecutive whitespace and trims newlines
 * - Retains enough text (up to [MAX_CAPTION_LENGTH] characters) to distinguish different reels from
 *   the same creator sharing opening words, eliminating false identity merges from arbitrary truncation.
 */
object CaptionNormalizer {

    const val MAX_CAPTION_LENGTH = 120

    private val HASHTAG_REGEX = Regex("""#([\p{L}\p{N}_]+)""")
    private val TRAILING_ELLIPSIS_MORE_REGEX = Regex("""(?:\.{3}|…)\s*(?:more)?\s*$""", RegexOption.IGNORE_CASE)
    private val TRAILING_STANDALONE_MORE_REGEX = Regex("""\s+more\s*$""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    fun normalize(rawCaption: String): String {
        if (rawCaption.isBlank()) return ""
        val trimmed = rawCaption.trim()
        if (trimmed.equals("more", ignoreCase = true)) return ""

        var clean = trimmed.replace('\n', ' ').replace('\r', ' ')
        // Strip '#' from hashtags to preserve the keyword text
        clean = HASHTAG_REGEX.replace(clean) { it.groupValues[1] }
        // Strip trailing UI expanders like "... more", "…more", "...", "…"
        clean = TRAILING_ELLIPSIS_MORE_REGEX.replace(clean, "").trim()
        // Strip trailing " more" button text
        clean = TRAILING_STANDALONE_MORE_REGEX.replace(clean, "").trim()
        // Collapse multiple whitespace
        clean = MULTI_SPACE_REGEX.replace(clean, " ").trim()

        return clean.take(MAX_CAPTION_LENGTH)
    }
}
