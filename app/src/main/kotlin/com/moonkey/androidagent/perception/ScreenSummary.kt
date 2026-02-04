package com.moonkey.androidagent.perception

import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.ScreenSnapshot

private const val MAX_LABELS = 4
private const val MAX_LABEL_LENGTH = 40
private const val MIN_LABEL_LENGTH = 3

private val GMAIL_STOPWORDS =
    setOf(
        "Search in mail",
        "Open navigation drawer",
        "Compose",
        "Meet",
        "Inbox",
        "Mail",
        "Navigate up",
        "More options",
        "Archive",
        "Delete",
        "Mark unread",
        "Add star",
        "Unsubscribe",
        "Gemini"
    )

fun ScreenSnapshot.toSummary(packageName: String? = null): String {
    val total = elements.size
    val clickable = elements.count { it.isClickable }
    val editable = elements.count { it.isEditable }
    val focusedLabel =
        elements.firstOrNull { it.isFocused }
            ?.let { it.text.ifBlank { it.description } }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.truncate(MAX_LABEL_LENGTH)

    val labels = buildLabels(elements, packageName)

    val appName = packageName?.takeIf { it.isNotBlank() } ?: "unknown app"
    val labelsText = if (labels.isNotEmpty()) labels.joinToString(", ") else "none"
    val focusedText = focusedLabel ?: "none"

    return "$appName | elements=$total, clickable=$clickable, editable=$editable, focused=$focusedText, labels=$labelsText"
}

private fun buildLabels(elements: List<PerceptionElement>, packageName: String?): List<String> {
    val stopwords =
        if (packageName == "com.google.android.gm") {
            GMAIL_STOPWORDS
        } else {
            emptySet()
        }

    return elements
        .asSequence()
        .map { element -> element.center.y to element.text.ifBlank { element.description } }
        .map { (y, text) -> y to text.trim() }
        .filter { (_, text) -> text.isNotBlank() }
        .filter { (_, text) -> text.length >= MIN_LABEL_LENGTH }
        .filter { (_, text) -> !isStopword(text, stopwords) }
        .sortedBy { (y, _) -> y }
        .map { (_, text) -> text.truncate(MAX_LABEL_LENGTH) }
        .distinct()
        .take(MAX_LABELS)
        .toList()
}

private fun isStopword(text: String, stopwords: Set<String>): Boolean {
    if (stopwords.isEmpty()) return false
    return stopwords.any { stopword -> text.equals(stopword, ignoreCase = true) }
}

private fun String.truncate(maxLength: Int): String {
    return if (length <= maxLength) this else take(maxLength - 3) + "..."
}
