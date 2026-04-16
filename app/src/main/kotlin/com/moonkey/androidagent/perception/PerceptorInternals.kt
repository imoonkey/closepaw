package com.moonkey.androidagent.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import kotlin.math.max

internal data class PerceptorCandidateElement(
    val element: PerceptionElement,
    val visibleAreaRatio: Float
) {
    val isInteractive: Boolean
        get() = element.isClickable || element.isEditable || element.isScrollable
}

private const val ROW_SNAP_FALLBACK_PX = 24

internal fun enrichEmptyTextElements(
    candidates: List<PerceptorCandidateElement>
): List<PerceptorCandidateElement> {
    if (candidates.isEmpty()) return candidates
    val textSources =
        candidates.filter { candidate ->
            !candidate.isInteractive &&
                mergedText(candidate.element).isNotBlank()
        }
    return candidates.map { candidate ->
        val element = candidate.element
        val needsEnrichment =
            candidate.isInteractive &&
                !element.isScrollable &&
                mergedText(element).isBlank()
        if (!needsEnrichment) return@map candidate
        val bubbledText =
            textSources.asSequence()
                .filter { source -> contains(element.bounds, source.element.bounds) }
                .map { source -> mergedText(source.element) }
                .filter { text -> text.isNotBlank() }
                .take(3)
                .toList()
        if (bubbledText.isEmpty()) {
            candidate
        } else {
            candidate.copy(element = element.copy(text = bubbledText.joinToString(" | ")))
        }
    }
}

internal fun applyTruncation(
    candidates: List<PerceptorCandidateElement>,
    maxElements: Int,
    interactiveKeepRatio: Float
): List<PerceptorCandidateElement> {
    if (candidates.size <= maxElements) return candidates
    val interactiveCap = (maxElements * interactiveKeepRatio).toInt().coerceIn(1, maxElements)
    val nonInteractiveFloor = maxElements - interactiveCap
    val interactive = candidates.filter { it.isInteractive }.sortedByDescending { score(it) }
    val nonInteractive = candidates.filter { !it.isInteractive }.sortedByDescending { score(it) }

    val kept = ArrayList<PerceptorCandidateElement>(maxElements)
    val seen = HashSet<PerceptorCandidateElement>(maxElements)

    fun tryKeep(c: PerceptorCandidateElement) {
        if (seen.add(c)) kept.add(c)
    }

    interactive.asSequence().take(interactiveCap).forEach(::tryKeep)
    nonInteractive.asSequence().take(nonInteractiveFloor).forEach(::tryKeep)
    if (kept.size < maxElements) {
        for (c in interactive) {
            if (kept.size >= maxElements) break
            tryKeep(c)
        }
        for (c in nonInteractive) {
            if (kept.size >= maxElements) break
            tryKeep(c)
        }
    }
    return kept
}

private fun score(candidate: PerceptorCandidateElement): Float {
    val interactiveWeight = if (candidate.isInteractive) 3.0f else 1.0f
    val textWeight = if (mergedText(candidate.element).isNotBlank()) 1.5f else 1.0f
    return interactiveWeight * textWeight * candidate.visibleAreaRatio
}

internal fun spatialSort(
    candidates: List<PerceptorCandidateElement>,
    screenHeightPx: Int?,
    rowSnapRatio: Float
): List<PerceptorCandidateElement> {
    val snap = rowSnap(screenHeightPx, rowSnapRatio)
    return candidates.sortedWith(
        compareBy<PerceptorCandidateElement>(
            { it.element.bounds.top / snap },
            { it.element.bounds.left },
            { it.element.bounds.top },
            { it.element.bounds.right }
        )
    )
}

private fun rowSnap(screenHeightPx: Int?, rowSnapRatio: Float): Int {
    if (screenHeightPx == null || screenHeightPx <= 0) return ROW_SNAP_FALLBACK_PX
    return max(1, (screenHeightPx * rowSnapRatio).toInt())
}

internal fun mergedText(elem: PerceptionElement): String {
    return elem.text
        .ifBlank { elem.description }
        .ifBlank { elem.hintText }
}

internal fun shouldOutputResourceIds(
    elements: List<PerceptionElement>,
    densityThreshold: Float
): Boolean {
    val actionable = elements.filter { it.isClickable || it.isEditable || it.isScrollable }
    if (actionable.isEmpty()) return false
    val withId = actionable.count { it.resourceId.isNotBlank() }
    return (withId.toFloat() / actionable.size.toFloat()) >= densityThreshold
}

internal fun normalizeForMatching(value: String): String = value.trim().lowercase()

internal fun clipBoundsToScreen(
    rect: Rect,
    screenWidthPx: Int?,
    screenHeightPx: Int?
): Rect {
    if (screenWidthPx == null || screenHeightPx == null || screenWidthPx <= 0 || screenHeightPx <= 0) {
        return Rect(rect)
    }
    return Rect(
        rect.left.coerceAtLeast(0),
        rect.top.coerceAtLeast(0),
        rect.right.coerceAtMost(screenWidthPx),
        rect.bottom.coerceAtMost(screenHeightPx)
    )
}

internal fun visibleAreaRatio(rect: Rect, screenWidthPx: Int?, screenHeightPx: Int?): Float {
    if (screenWidthPx == null || screenHeightPx == null || screenWidthPx <= 0 || screenHeightPx <= 0) {
        return 1f
    }
    val totalArea = rect.width().toLong() * rect.height().toLong()
    if (totalArea <= 0L) return 0f
    val visibleLeft = rect.left.coerceAtLeast(0)
    val visibleTop = rect.top.coerceAtLeast(0)
    val visibleRight = rect.right.coerceAtMost(screenWidthPx)
    val visibleBottom = rect.bottom.coerceAtMost(screenHeightPx)
    val visibleWidth = (visibleRight - visibleLeft).coerceAtLeast(0)
    val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(0)
    val visibleArea = visibleWidth.toLong() * visibleHeight.toLong()
    return visibleArea.toFloat() / totalArea.toFloat()
}

internal fun canAcceptTextInput(node: AccessibilityNodeInfo): Boolean {
    val actions = node.actionList ?: return false
    return actions.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
}

internal fun isKnownKeyboardNode(resourceId: String): Boolean {
    if (resourceId.isBlank()) return false
    return KEYBOARD_RESOURCE_PREFIXES.any { resourceId.startsWith(it) }
}

private val KEYBOARD_RESOURCE_PREFIXES =
    listOf(
        "com.google.android.inputmethod.latin:id/",
        "com.android.inputmethod.latin:id/",
        "com.samsung.android.honeyboard:id/",
        "com.swiftkey.swiftkey:id/",
        "com.touchtype.swiftkey:id/"
    )

internal fun getOccurrenceIndex(
    value: String,
    counts: MutableMap<String, Int>,
    normalize: (String) -> String
): Int? {
    if (value.isBlank()) return null
    val key = normalize(value)
    val index = counts[key] ?: 0
    counts[key] = index + 1
    return index
}

internal fun buildElementKey(
    resourceId: String,
    className: String,
    text: String,
    desc: String,
    rect: Rect,
    isClickable: Boolean,
    isEditable: Boolean,
    isScrollable: Boolean
): String {
    return buildString {
        append(resourceId)
        append('|')
        append(className)
        append('|')
        append(text)
        append('|')
        append(desc)
        append('|')
        append(if (isClickable) '1' else '0')
        append(if (isEditable) '1' else '0')
        append(if (isScrollable) '1' else '0')
        append('|')
        append(rect.left)
        append(',')
        append(rect.top)
        append(',')
        append(rect.right)
        append(',')
        append(rect.bottom)
    }
}

private fun contains(container: Bounds, child: Bounds): Boolean {
    return container.left <= child.left &&
        container.top <= child.top &&
        container.right >= child.right &&
        container.bottom >= child.bottom
}
