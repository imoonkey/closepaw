package ai.closepaw.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import ai.closepaw.model.Bounds
import ai.closepaw.model.PerceptionElement
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

    // Cache mergedText() per candidate — called repeatedly otherwise.
    val mergedTextOf = HashMap<PerceptorCandidateElement, String>(candidates.size)
    for (c in candidates) mergedTextOf[c] = mergedText(c.element)

    val sourceBounds = ArrayList<Bounds>()
    val sourceText = ArrayList<String>()
    for (c in candidates) {
        if (!c.isInteractive) {
            val text = mergedTextOf.getValue(c)
            if (text.isNotBlank()) {
                sourceBounds.add(c.element.bounds)
                sourceText.add(text)
            }
        }
    }
    if (sourceBounds.isEmpty()) return candidates

    // Sort sources by top so each candidate only scans the vertical slice of
    // sources whose top falls within its bounds. Keep the original index so we
    // can restore candidate-order within matched hits — preserving the
    // pre-refactor join order.
    val order = (0 until sourceBounds.size).sortedBy { sourceBounds[it].top }
    val sortedTops = IntArray(order.size) { sourceBounds[order[it]].top }
    val sortedBounds = Array(order.size) { sourceBounds[order[it]] }
    val sortedText = Array(order.size) { sourceText[order[it]] }
    val sortedOriginalIndex = IntArray(order.size) { order[it] }

    return candidates.map { candidate ->
        val element = candidate.element
        val needsEnrichment =
            candidate.isInteractive &&
                !element.isScrollable &&
                mergedTextOf.getValue(candidate).isBlank()
        if (!needsEnrichment) return@map candidate

        val cb = element.bounds
        val start = lowerBound(sortedTops, cb.top)
        // Collect (originalIndex, text) so we can restore original order.
        val hits = ArrayList<IntArray>(8)
        val hitTexts = ArrayList<String>(8)
        for (i in start until sortedTops.size) {
            if (sortedTops[i] > cb.bottom) break
            if (contains(cb, sortedBounds[i])) {
                hits.add(intArrayOf(sortedOriginalIndex[i], hits.size))
                hitTexts.add(sortedText[i])
            }
        }
        if (hits.isEmpty()) return@map candidate

        val ordered = hits.indices.sortedBy { hits[it][0] }
        val bubbledText = ArrayList<String>(3)
        for (idx in ordered) {
            bubbledText.add(hitTexts[idx])
            if (bubbledText.size >= 3) break
        }
        candidate.copy(element = element.copy(text = bubbledText.joinToString(" | ")))
    }
}

private fun lowerBound(sorted: IntArray, target: Int): Int {
    var lo = 0
    var hi = sorted.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (sorted[mid] < target) lo = mid + 1 else hi = mid
    }
    return lo
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
