package ai.closepaw.protocol

/**
 * Sanitize raw text for compact capsule display.
 * Trims whitespace and truncates to 40 characters with ellipsis.
 *
 * Used by CapsuleStateHolder (UI layer) and AgentTurnRunner (agent layer).
 * Lives in protocol/ so both layers can depend on it without cross-layer imports.
 */
fun sanitizeThought(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.length > 40) trimmed.take(40) + "..." else trimmed
}
