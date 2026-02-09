package com.moonkey.androidagent.agent.cognition.context

import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.ScreenSnapshot

private const val MAX_SIGNATURE_HISTORY = 10
private const val MAX_ACTION_HISTORY = 5
private const val MAX_SIGNATURE_ELEMENTS = 32
private const val POSITION_BUCKET_PX = 120

/**
 * Tracks recent navigation history (screens and actions) to detect loops and execution stalls.
 *
 * It acts as the agent's short-term spatial memory, using simplified [ScreenSignature]s to robustly
 * identify if the agent is revisiting the same states or repeating actions.
 */
internal data class NavigationState(
        val recentSignatures: List<ScreenSignature> = emptyList(),
        val consecutiveScrollActions: Int = 0,
        val recentActions: List<String> = emptyList()
) {
    fun advance(snapshot: ScreenSnapshot, previousAction: String?): NavigationState {
        val signature = snapshot.toSignature()
        val updatedSignatures = (recentSignatures + signature).takeLast(MAX_SIGNATURE_HISTORY)
        val updatedActions =
                previousAction?.takeIf { it.isNotBlank() }?.let {
                    (recentActions + it).takeLast(MAX_ACTION_HISTORY)
                }
                        ?: recentActions
        val updatedScrollCount =
                if (previousAction?.startsWith("scroll:") == true) {
                    consecutiveScrollActions + 1
                } else {
                    0
                }
        return copy(
                recentSignatures = updatedSignatures,
                consecutiveScrollActions = updatedScrollCount,
                recentActions = updatedActions
        )
    }
}

internal data class ScreenSignature(val fingerprint: String, val tokens: Set<String>) {
    fun similarityTo(other: ScreenSignature): Double {
        if (tokens.isEmpty() && other.tokens.isEmpty()) return 1.0
        if (tokens.isEmpty() || other.tokens.isEmpty()) return 0.0
        val overlap = tokens.intersect(other.tokens).size.toDouble()
        val union = tokens.union(other.tokens).size.toDouble()
        if (union <= 0.0) return 0.0
        return overlap / union
    }
}

internal enum class LoopWarningSeverity {
    WARNING,
    CRITICAL
}

internal data class LoopWarning(val message: String, val severity: LoopWarningSeverity)

private fun ScreenSnapshot.toSignature(): ScreenSignature {
    val tokens =
            elements.orEmpty().asSequence()
                    .map { it.toSignatureToken() }
                    .filter { it.isNotBlank() }
                    .take(MAX_SIGNATURE_ELEMENTS)
                    .toCollection(linkedSetOf())
    val fingerprint = tokens.joinToString(separator = "|").hashCode().toString()
    return ScreenSignature(fingerprint = fingerprint, tokens = tokens)
}

private fun PerceptionElement.toSignatureToken(): String {
    val normalizedText = normalizeTokenValue(text, 24)
    val normalizedDesc = normalizeTokenValue(description, 24)
    val normalizedResourceId = normalizeTokenValue(resourceId.substringAfterLast('/'), 36)
    val normalizedClass = normalizeTokenValue(className, 24)
    val xBucket = bounds.left / POSITION_BUCKET_PX
    val yBucket = bounds.top / POSITION_BUCKET_PX
    val flags =
            buildString {
                if (isClickable) append("c")
                if (isEditable) append("e")
                if (isScrollable) append("s")
                if (isFocused) append("f")
                if (isLongClickable) append("l")
            }
                    .ifEmpty { "-" }

    return listOf(
                    "id=$normalizedResourceId",
                    "text=$normalizedText",
                    "desc=$normalizedDesc",
                    "class=$normalizedClass",
                    "flags=$flags",
                    "pos=$xBucket,$yBucket"
            )
            .joinToString(separator = "|")
}

private fun normalizeTokenValue(value: String, maxLength: Int): String {
    return value.trim().lowercase().replace(Regex("\\s+"), " ").take(maxLength)
}
