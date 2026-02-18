package com.moonkey.androidagent.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Perceptor - The Perception Engine. Converts raw AccessibilityNodeInfo tree into a semantic
 * ScreenSnapshot.
 */
object Perceptor {

    private const val MAX_STRING_LENGTH = 60

    private enum class TraversalMode {
        INTERACTIVE_ONLY,
        ALL
    }

    /**
     * Create a ScreenSnapshot from the accessibility tree.
     *
     * Does not store AccessibilityNodeInfo references to prevent memory leaks.
     * All data needed for action execution (bounds, center, properties) is extracted
     * and stored in PerceptionElement.
     */
    fun snapshot(
        root: AccessibilityNodeInfo?,
        screenWidthPx: Int? = null,
        screenHeightPx: Int? = null,
        filterConfig: PerceptorFilterConfig = PerceptorFilterConfig.DEFAULT,
        diagnosticsCollector: PerceptorDiagnosticsCollector? = null
    ): ScreenSnapshot {
        val timestamp = System.currentTimeMillis()
        if (root == null) return ScreenSnapshot(timestamp, emptyList())

        val collected = mutableListOf<PerceptorCandidateElement>()
        val seenKeys = mutableSetOf<String>()
        // Root is owned by system, don't recycle it.
        traverse(
            node = root,
            elements = collected,
            seenKeys = seenKeys,
            shouldRecycle = false,
            mode = TraversalMode.INTERACTIVE_ONLY,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            filterConfig = filterConfig,
            diagnosticsCollector = diagnosticsCollector
        )
        traverse(
            node = root,
            elements = collected,
            seenKeys = seenKeys,
            shouldRecycle = false,
            mode = TraversalMode.ALL,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            filterConfig = filterConfig,
            diagnosticsCollector = diagnosticsCollector
        )

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
        return ScreenSnapshot(timestamp = timestamp, elements = indexed)
    }

    /** Convert Snapshot to JSON string for LLM Prompting */
    fun toPromptJson(
        snapshot: ScreenSnapshot,
        filterConfig: PerceptorFilterConfig = PerceptorFilterConfig.DEFAULT
    ): String {
        if (snapshot.elements.isEmpty()) return "[]"

        // Re-run enrichment at serialization so manually constructed snapshots (tests, tooling)
        // get the same text behavior as snapshots produced by Perceptor.snapshot().
        val elements =
            enrichEmptyTextElements(snapshot.elements.map { PerceptorCandidateElement(it, 1f) })
                .map { it.element }
        val outputResourceId =
            shouldOutputResourceIds(
                elements = elements,
                densityThreshold = filterConfig.resourceIdOutputDensityThreshold
            )
        val textCounts = mutableMapOf<String, Int>()
        val descCounts = mutableMapOf<String, Int>()
        val jsonArray = JSONArray()

        for (elem in elements) {
            val mergedText = mergedText(elem)
            val textIndex =
                getOccurrenceIndex(
                    value = mergedText,
                    counts = textCounts,
                    normalize = { it.trim().lowercase() }
                )
            val descIndex =
                getOccurrenceIndex(
                    value = elem.description,
                    counts = descCounts,
                    normalize = { it.trim().lowercase() }
                )
            val obj =
                JSONObject().apply {
                    put("index", elem.index)
                    put("text", mergedText)
                    if (textIndex != null) put("text_index", textIndex)
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
                    if (elem.isSelected) put("selected", true)
                    if (elem.isChecked) put("checked", true)
                    if (elem.isCheckable) put("checkable", true)
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
        shouldRecycle: Boolean = false,
        mode: TraversalMode,
        screenWidthPx: Int?,
        screenHeightPx: Int?,
        filterConfig: PerceptorFilterConfig,
        diagnosticsCollector: PerceptorDiagnosticsCollector?
    ) {
        val visibleToUser = node.isVisibleToUser
        if (filterConfig.useVisibleToUserFilter && !visibleToUser) {
            traverseChildren(
                node = node,
                elements = elements,
                seenKeys = seenKeys,
                mode = mode,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                filterConfig = filterConfig,
                diagnosticsCollector = diagnosticsCollector
            )
            if (shouldRecycle) node.recycle()
            return
        }

        val text = node.text?.toString()?.take(MAX_STRING_LENGTH) ?: ""
        val desc = node.contentDescription?.toString()?.take(MAX_STRING_LENGTH) ?: ""
        val hintText = node.hintText?.toString()?.take(MAX_STRING_LENGTH) ?: ""
        val resourceId = node.viewIdResourceName?.take(MAX_STRING_LENGTH) ?: ""
        val clickable = node.isClickable
        val scrollable = node.isScrollable
        val enabled = node.isEnabled
        val focused = node.isFocused
        val longClickable = node.isLongClickable
        val selected = node.isSelected
        val checked = node.isChecked
        val checkable = node.isCheckable

        if (filterConfig.filterKeyboard && isKnownKeyboardNode(resourceId)) {
            if (shouldRecycle) node.recycle()
            return
        }

        // isEditable is unreliable on some widgets; ACTION_SET_TEXT is a useful backup signal.
        val editable = node.isEditable || canAcceptTextInput(node)
        val hasContent =
            text.isNotBlank() ||
                desc.isNotBlank() ||
                hintText.isNotBlank() ||
                resourceId.isNotBlank()
        val shouldKeep = when (mode) {
            TraversalMode.INTERACTIVE_ONLY -> clickable || editable || scrollable
            TraversalMode.ALL -> clickable || editable || scrollable || hasContent
        }

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
            seenKeys.add(key)
            val boundsRect =
                if (filterConfig.clipBounds) clipBoundsToScreen(rect, screenWidthPx, screenHeightPx)
                else Rect(rect)
            if (boundsRect.width() > 0 && boundsRect.height() > 0) {
                val element =
                    PerceptionElement(
                        index = -1,
                        text = normalizeWhitespace(text),
                        resourceId = resourceId,
                        className = className,
                        description = normalizeWhitespace(desc),
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
                        hintText = normalizeWhitespace(hintText),
                        isChecked = checked,
                        isCheckable = checkable
                    )
                elements.add(PerceptorCandidateElement(element, visibilityRatio))
            }
        }

        traverseChildren(
            node = node,
            elements = elements,
            seenKeys = seenKeys,
            mode = mode,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            filterConfig = filterConfig,
            diagnosticsCollector = diagnosticsCollector
        )
        if (shouldRecycle) node.recycle()
    }

    private fun traverseChildren(
        node: AccessibilityNodeInfo,
        elements: MutableList<PerceptorCandidateElement>,
        seenKeys: MutableSet<String>,
        mode: TraversalMode,
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
                shouldRecycle = true,
                mode = mode,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                filterConfig = filterConfig,
                diagnosticsCollector = diagnosticsCollector
            )
        }
    }
}
