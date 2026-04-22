package ai.closepaw.protocol

/**
 * Compact a thought string for surfaces that explicitly opt into a single-line preview
 * (capsule reduced-motion fallback, error/status banners). Trims whitespace and clips
 * to ~80 chars with an ellipsis. Canonical pipeline preserves full text — only callers
 * that need a bounded form should use this.
 */
fun compactThought(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.length > COMPACT_THOUGHT_MAX) trimmed.take(COMPACT_THOUGHT_MAX) + "..." else trimmed
}

private const val COMPACT_THOUGHT_MAX = 80
