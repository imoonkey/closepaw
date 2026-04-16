package com.moonkey.androidagent.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.RangeInfo
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.util.isCheckedCompat
import com.moonkey.androidagent.util.recycleCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Perceptor - The Perception Engine. Converts raw AccessibilityNodeInfo tree into a semantic
 * ScreenSnapshot.
 */
object Perceptor {

    private class PoolCounters(var interactive: Int = 0, var nonInteractive: Int = 0)

    /**
     * Create a ScreenSnapshot from a single accessibility tree root.
     * Delegates to the multi-root overload.
     */
    fun snapshot(
        root: AccessibilityNodeInfo?,
        screenWidthPx: Int? = null,
        screenHeightPx: Int? = null,
        filterConfig: PerceptorFilterConfig = PerceptorFilterConfig.DEFAULT,
        diagnosticsCollector: PerceptorDiagnosticsCollector? = null
    ): ScreenSnapshot {
        if (root == null) return ScreenSnapshot(System.currentTimeMillis(), emptyList())
        return snapshot(
            roots = listOf(root),
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            filterConfig = filterConfig,
            diagnosticsCollector = diagnosticsCollector
        )
    }

    /**
     * Create a ScreenSnapshot from multiple accessibility tree roots (multi-window).
     *
     * Traverses every root once, collecting interactive and content-bearing nodes
     * with shared dedup state. applyTruncation handles interactive priority
     * downstream via interactiveKeepRatio.
     *
     * Does not store AccessibilityNodeInfo references to prevent memory leaks.
     * Roots are NOT recycled — caller is responsible for lifecycle.
     */
    fun snapshot(
        roots: List<AccessibilityNodeInfo>,
        screenWidthPx: Int? = null,
        screenHeightPx: Int? = null,
        filterConfig: PerceptorFilterConfig = PerceptorFilterConfig.DEFAULT,
        diagnosticsCollector: PerceptorDiagnosticsCollector? = null
    ): ScreenSnapshot {
        val timestamp = System.currentTimeMillis()
        if (roots.isEmpty()) return ScreenSnapshot(timestamp, emptyList())

        val collected = mutableListOf<PerceptorCandidateElement>()
        val seenKeys = mutableSetOf<String>()
        val counters = PoolCounters()

        // Over-collect both pools so applyTruncation has score-based headroom
        // even when a screen is almost entirely interactive or almost entirely
        // non-interactive. Matches the pre-refactor two-pass collection pool.
        val interactiveCap = filterConfig.maxElements * 2
        val nonInteractiveCap = filterConfig.maxElements * 2

        for (root in roots) {
            traverse(
                node = root,
                elements = collected,
                seenKeys = seenKeys,
                counters = counters,
                shouldRecycle = false,
                interactiveCap = interactiveCap,
                nonInteractiveCap = nonInteractiveCap,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                filterConfig = filterConfig,
                diagnosticsCollector = diagnosticsCollector
            )
        }

        val enriched = enrichEmptyTextElements(collected)
        val truncated =
            applyTruncation(
                candidates = enriched,
                maxElements = filterConfig.maxElements,
                interactiveKeepRatio = filterConfig.interactiveKeepRatio
            )
        val sorted =
            spatialSort(
                candidates = truncated,
                screenHeightPx = screenHeightPx,
                rowSnapRatio = filterConfig.rowSnapScreenRatio
            )
        val indexed = sorted.mapIndexed { index, candidate -> candidate.element.copy(index = index) }
        return ScreenSnapshot(
            timestamp = timestamp,
            elements = indexed,
            textEnriched = true
        )
    }

    /** Convert Snapshot to JSON string for LLM Prompting */
    fun toPromptJson(
        snapshot: ScreenSnapshot,
        filterConfig: PerceptorFilterConfig = PerceptorFilterConfig.DEFAULT
    ): String {
        if (snapshot.elements.isEmpty()) return "[]"

        // Only run enrichment for manually constructed snapshots (tests, tooling).
        // Snapshots from Perceptor.snapshot() are already enriched.
        val elements = if (snapshot.textEnriched) {
            snapshot.elements
        } else {
            enrichEmptyTextElements(snapshot.elements.map { PerceptorCandidateElement(it, 1f) })
                .map { it.element }
        }
        val outputResourceId =
            shouldOutputResourceIds(
                elements = elements,
                densityThreshold = filterConfig.resourceIdOutputDensityThreshold
            )
        val textCounts = mutableMapOf<String, Int>()
        val descCounts = mutableMapOf<String, Int>()
        val jsonArray = JSONArray()

        for (elem in elements) {
            val promptText = mergedText(elem)
            val textIndex =
                getOccurrenceIndex(
                    value = promptText,
                    counts = textCounts,
                    normalize = ::normalizeForMatching
                )
            val descIndex =
                getOccurrenceIndex(
                    value = elem.description,
                    counts = descCounts,
                    normalize = ::normalizeForMatching
                )
            val obj =
                JSONObject().apply {
                    put("index", elem.index)
                    if (promptText.isNotBlank()) {
                        put("text", promptText)
                        if (textIndex != null) put("text_index", textIndex)
                    }
                    if (elem.description.isNotBlank()) {
                        put("desc", elem.description)
                        if (descIndex != null) put("desc_index", descIndex)
                    }
                    if (outputResourceId && elem.resourceId.isNotBlank()) {
                        put("id", elem.resourceId)
                    }
                    put("class", elem.className)
                    put("clickable", elem.isClickable)
                    put("editable", elem.isEditable)
                    put("scrollable", elem.isScrollable)
                    if (!elem.isEnabled) put("enabled", false)
                    if (elem.isSelected) put("selected", true)
                    if (elem.isChecked) put("checked", true)
                    if (elem.isCheckable) put("checkable", true)
                    elem.rangeInfo?.let { rangeInfo ->
                        put("range_current", rangeInfo.current)
                        put("range_min", rangeInfo.min)
                        put("range_max", rangeInfo.max)
                        if (rangeInfo.max > rangeInfo.min) {
                            val percent =
                                ((rangeInfo.current - rangeInfo.min) / (rangeInfo.max - rangeInfo.min))
                                    .coerceIn(0f, 1f) * 100f
                            put("range_percent", percent)
                        }
                    }
                    if (elem.hintText.isNotBlank()) put("hint_text", elem.hintText)
                    put("focused", elem.isFocused)
                    put("long_clickable", elem.isLongClickable)
                    put(
                        "bounds",
                        JSONArray(
                            listOf(
                                elem.bounds.left,
                                elem.bounds.top,
                                elem.bounds.right,
                                elem.bounds.bottom
                            )
                        )
                    )
                    put("center", JSONArray(listOf(elem.center.x, elem.center.y)))
                }
            jsonArray.put(obj)
        }
        return jsonArray.toString(2)
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        elements: MutableList<PerceptorCandidateElement>,
        seenKeys: MutableSet<String>,
        counters: PoolCounters,
        shouldRecycle: Boolean = false,
        interactiveCap: Int,
        nonInteractiveCap: Int,
        screenWidthPx: Int?,
        screenHeightPx: Int?,
        filterConfig: PerceptorFilterConfig,
        diagnosticsCollector: PerceptorDiagnosticsCollector?
    ) {
        // Collection cap: stop entirely once both pools are full.
        if (counters.interactive >= interactiveCap && counters.nonInteractive >= nonInteractiveCap) {
            if (shouldRecycle) node.recycleCompat()
            return
        }

        val visibleToUser = node.isVisibleToUser
        if (filterConfig.useVisibleToUserFilter && !visibleToUser) {
            traverseChildren(
                node = node,
                elements = elements,
                seenKeys = seenKeys,
                counters = counters,
                interactiveCap = interactiveCap,
                nonInteractiveCap = nonInteractiveCap,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                filterConfig = filterConfig,
                diagnosticsCollector = diagnosticsCollector
            )
            if (shouldRecycle) node.recycleCompat()
            return
        }

        val isPassword = node.isPassword
        val text = if (isPassword) "[password]" else node.text?.toString() ?: ""
        val desc = if (isPassword) "" else node.contentDescription?.toString() ?: ""
        val hintText = node.hintText?.toString() ?: ""
        val resourceId = node.viewIdResourceName ?: ""
        val clickable = node.isClickable
        val scrollable = node.isScrollable
        val enabled = node.isEnabled
        val focused = node.isFocused
        val longClickable = node.isLongClickable
        val selected = node.isSelected
        val checked = node.isCheckedCompat()
        val checkable = node.isCheckable
        val rangeInfo = node.rangeInfo?.let { RangeInfo(current = it.current, min = it.min, max = it.max) }

        if (filterConfig.filterKeyboard && isKnownKeyboardNode(resourceId)) {
            if (shouldRecycle) node.recycleCompat()
            return
        }

        // isEditable is unreliable on some widgets; ACTION_SET_TEXT is a useful backup signal.
        val editable = node.isEditable || canAcceptTextInput(node)
        val hasContent =
            text.isNotBlank() ||
                desc.isNotBlank() ||
                hintText.isNotBlank() ||
                resourceId.isNotBlank()
        val isInteractive = clickable || editable || scrollable || checkable
        val shouldKeep = isInteractive || hasContent

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (shouldKeep) diagnosticsCollector?.recordNodeBounds(rect, screenWidthPx, screenHeightPx)
        val visibilityThreshold =
            if (clickable || editable || scrollable) {
                filterConfig.interactiveVisibilityThreshold
            } else {
                filterConfig.visibilityThreshold
            }
        val visibilityRatio = visibleAreaRatio(rect, screenWidthPx, screenHeightPx)
        val meetsVisibility = visibilityRatio >= visibilityThreshold
        val meetsMinSize =
            rect.width() > filterConfig.minElementSizePx &&
                rect.height() > filterConfig.minElementSizePx

        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val key =
            buildElementKey(
                resourceId = resourceId,
                className = className,
                text = text,
                desc = desc,
                rect = rect,
                isClickable = clickable,
                isEditable = editable,
                isScrollable = scrollable
            )
        val alreadySeen = seenKeys.contains(key)
        if (shouldKeep && !alreadySeen && meetsVisibility && meetsMinSize) {
            // Per-pool cap: non-interactive stops accruing once its pool is full,
            // but interactive elements keep being collected up to their cap.
            val poolHasRoom = if (isInteractive) {
                counters.interactive < interactiveCap
            } else {
                counters.nonInteractive < nonInteractiveCap
            }
            if (poolHasRoom) {
                seenKeys.add(key)
                val boundsRect =
                    if (filterConfig.clipBounds) clipBoundsToScreen(rect, screenWidthPx, screenHeightPx)
                    else Rect(rect)
                if (boundsRect.width() > 0 && boundsRect.height() > 0) {
                    val element =
                        PerceptionElement(
                            index = -1,
                            text = text,
                            resourceId = resourceId,
                            className = className,
                            description = desc,
                            isClickable = clickable,
                            isEditable = editable,
                            isScrollable = scrollable,
                            isEnabled = enabled,
                            isFocused = focused,
                            isLongClickable = longClickable,
                            bounds =
                                Bounds(
                                    left = boundsRect.left,
                                    top = boundsRect.top,
                                    right = boundsRect.right,
                                    bottom = boundsRect.bottom
                                ),
                            center =
                                Point(
                                    x = (boundsRect.left + boundsRect.right) / 2,
                                    y = (boundsRect.top + boundsRect.bottom) / 2
                                ),
                            isSelected = selected,
                            hintText = hintText,
                            isChecked = checked,
                            isCheckable = checkable,
                            rangeInfo = rangeInfo
                        )
                    elements.add(PerceptorCandidateElement(element, visibilityRatio))
                    if (isInteractive) counters.interactive++ else counters.nonInteractive++
                }
            }
        }

        traverseChildren(
            node = node,
            elements = elements,
            seenKeys = seenKeys,
            counters = counters,
            interactiveCap = interactiveCap,
            nonInteractiveCap = nonInteractiveCap,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            filterConfig = filterConfig,
            diagnosticsCollector = diagnosticsCollector
        )
        if (shouldRecycle) node.recycleCompat()
    }

    private fun traverseChildren(
        node: AccessibilityNodeInfo,
        elements: MutableList<PerceptorCandidateElement>,
        seenKeys: MutableSet<String>,
        counters: PoolCounters,
        interactiveCap: Int,
        nonInteractiveCap: Int,
        screenWidthPx: Int?,
        screenHeightPx: Int?,
        filterConfig: PerceptorFilterConfig,
        diagnosticsCollector: PerceptorDiagnosticsCollector?
    ) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(
                node = child,
                elements = elements,
                seenKeys = seenKeys,
                counters = counters,
                shouldRecycle = true,
                interactiveCap = interactiveCap,
                nonInteractiveCap = nonInteractiveCap,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                filterConfig = filterConfig,
                diagnosticsCollector = diagnosticsCollector
            )
        }
    }
}
