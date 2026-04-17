package ai.closepaw.perception

import ai.closepaw.model.PerceptionElement
import ai.closepaw.model.ScreenSnapshot

/** Maximum labels to include in summary to keep context compact. */
private const val MAX_LABELS = 4

/** Truncate label text beyond this length to reduce prompt noise. */
private const val MAX_LABEL_LENGTH = 40

/** Ignore tiny labels that are usually punctuation/icons. */
private const val MIN_LABEL_LENGTH = 3

fun ScreenSnapshot.toSummary(packageName: String? = null): String {
    val els = elements
    val total = els.size
    val clickable = els.count { it.isClickable }
    val editable = els.count { it.isEditable }
    val focusedLabel =
        els.firstOrNull { it.isFocused }
            ?.let { it.text.ifBlank { it.description } }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.truncate(MAX_LABEL_LENGTH)

    val labels = buildLabels(els)

    val appName = packageName?.takeIf { it.isNotBlank() } ?: "unknown app"
    val labelsText = if (labels.isNotEmpty()) labels.joinToString(", ") else "none"
    val focusedText = focusedLabel ?: "none"

    return "$appName | elements=$total, clickable=$clickable, editable=$editable, focused=$focusedText, labels=$labelsText"
}

private fun buildLabels(elements: List<PerceptionElement>): List<String> {
    return elements
        .asSequence()
        .map { element -> element.center.y to element.text.ifBlank { element.description } }
        .map { (y, text) -> y to text.trim() }
        .filter { (_, text) -> text.isNotBlank() }
        .filter { (_, text) -> text.length >= MIN_LABEL_LENGTH }
        .sortedBy { (y, _) -> y }
        .map { (_, text) -> text.truncate(MAX_LABEL_LENGTH) }
        .distinct()
        .take(MAX_LABELS)
        .toList()
}

private fun String.truncate(maxLength: Int): String {
    return if (length <= maxLength) this else take(maxLength - 3) + "..."
}
