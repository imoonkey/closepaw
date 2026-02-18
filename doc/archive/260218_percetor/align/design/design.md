# Perceptor Improvement Plan (Aligned)

Synthesized from `design_claude.md` and `design_codex.md`, then revalidated against:
- `debug-output/**/trace/artifacts/*` (3202 raw trees, 3202 sanitized trees)
- `eval/results/**` (4 result sets, 26 per-task rows, 19 traces)

## Consensus Summary

Both designs agree on:
1. `resource_id` is low priority — conditional output only (interactive/actionable coverage is single-digit and app-dependent; many snapshots have zero id)
2. Visible-area filtering needed — current `intersectsScreen()` (1px overlap) is too loose
3. Stable spatial ordering — sort by position before assigning indices to reduce cross-frame flicker
4. Capture robustness — `rootInActiveWindow` retry + quality metadata
5. Eval-driven rollout — A/B comparison on task set before defaulting changes
6. Clickable targets should be prioritized — non-clickable elements being clicked is a real failure mode

---

## Phase 0: Bounds & Screen-Normalization Safety

**Goal**: eliminate coordinate-scale mismatches that can invalidate otherwise-correct selector logic.

### 0a. Pass screen dimensions into Perceptor snapshot in accessibility mode

Current `AccessibilityPlatform.captureAccessibilityTree()` calls `Perceptor.snapshot(root)` without screen dimensions, so clipping logic may not apply consistently.

```kotlin
val display = getDisplayInfo()
val snapshot = Perceptor.snapshot(
    root = root,
    screenWidthPx = display.widthPixels,
    screenHeightPx = display.heightPixels
)
```

### 0b. Add trace diagnostics for suspicious bounds

Record counters for:
- elements with right/bottom above display bounds
- negative coordinates
- out-of-bounds click targets before action execution

**Files**: `platform/AccessibilityPlatform.kt`, `perception/Perceptor.kt`

---

## Phase 1: Filtering Pipeline

**Goal**: Remove invisible/noise elements before they consume index slots and tokens.

### 1a. `visibleToUser` primary filter

**Source**: Claude proposal, validated by current traces.

Add `node.isVisibleToUser` check as the first gate in `traverse()`. Nodes with `visibleToUser=false` are skipped entirely (not entered into either pass).

```kotlin
// Perceptor.kt traverse()
if (!node.isVisibleToUser) {
    // traverse children anyway — a parent can be invisible while children are visible
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        traverse(child, elements, seenKeys, true, mode, screenWidthPx, screenHeightPx)
    }
    if (shouldRecycle) node.recycle()
    return
}
```

**Risk**: some UI stacks can mis-report this flag.
**Mitigation**: keep area-ratio as second filter + config switch + trace anomaly counters.

### 1b. Visible-area ratio filter (replaces `intersectsScreen()`)

**Source**: Both designs (DroidRun uses 10% threshold).

```kotlin
private fun visibleAreaRatio(rect: Rect, screenW: Int, screenH: Int): Float {
    val totalArea = rect.width().toLong() * rect.height().toLong()
    if (totalArea <= 0) return 0f
    val vL = rect.left.coerceAtLeast(0)
    val vT = rect.top.coerceAtLeast(0)
    val vR = rect.right.coerceAtMost(screenW)
    val vB = rect.bottom.coerceAtMost(screenH)
    val visibleArea = (vR - vL).toLong().coerceAtLeast(0) * (vB - vT).toLong().coerceAtLeast(0)
    return visibleArea.toFloat() / totalArea
}
```

Thresholds: interactive elements >= 1%, non-interactive >= 10%.

### 1c. Filter parameterization

**Source**: Codex Phase 2.

Extract filter thresholds into a config object for eval experimentation:

```kotlin
data class PerceptionConfig(
    val maxElements: Int = 80,
    val minElementSizePx: Int = 5,
    val visibilityThreshold: Float = 0.10f,
    val interactiveVisibilityThreshold: Float = 0.01f,
    val filterKeyboard: Boolean = true,
    val clipBounds: Boolean = true
)
```

**Files**: `perception/Perceptor.kt`, new `perception/PerceptionConfig.kt`

---

## Phase 2: Attribute Expression

**Goal**: Make element properties unambiguous for the LLM.

### 2a. Explicit boolean encoding

**Source**: Claude P3. Current sparse encoding (omit `clickable` when false) is unique among all reference agents and causes mis-clicks.

```kotlin
// toPromptJson() — BEFORE (sparse)
if (elem.isClickable) put("clickable", true)

// AFTER (explicit)
put("clickable", elem.isClickable)
put("editable", elem.isEditable)
put("scrollable", elem.isScrollable)
```

Token cost: ~1000 tokens/turn for 80 elements. Justified by data showing LLM clicks non-clickable elements when `clickable` is absent.

### 2b. New state attributes

**Source**: Claude P4 + Codex Phase 1 (both agree on enriching schema).

Add to `PerceptionElement`:

| Field | When output | Justification |
|-------|-------------|---------------|
| `isSelected` | only when `true` | 39.3% of screens have selected elements (tabs/nav) |
| `hintText` | when non-empty | EditText placeholder, useful for empty input identification |
| `isChecked` | only when `true` | Rare (0.6% screens) but zero cost when absent |
| `isCheckable` | only when `true` | Distinguishes Button from Switch/CheckBox |

### 2c. Occurrence indices

**Source**: Codex Phase 1. Extend existing `text_index` pattern.

Add `desc_index` for duplicate `contentDescription` disambiguation.

`resource_id_index` is deferred — resource_id density is too low (4.5%) to warrant an occurrence index.

**Files**: `model/Models.kt`, `perception/Perceptor.kt`

---

## Phase 3: Empty-Text Enrichment

**Goal**: Reduce high empty-text interactive rate, but avoid over-enriching structural containers.

### 3a. Text fallback chain

**Source**: Claude P1 (DroidRun pattern).

```kotlin
val mergedText = elem.text.ifBlank { elem.description }
    .ifBlank { elem.hintText }
    .ifBlank { elem.resourceId.extractIdSuffix() }
```

`extractIdSuffix()` turns `com.google.android.documentsui:id/icon_thumb` into `icon_thumb`.

### 3b. Scoped child text bubbling

**Source**: Claude P1. For interactive elements still empty after 3a, absorb text from contained non-interactive children (by bounds containment):

```kotlin
private fun enrichEmptyTextElements(elements: MutableList<PerceptionElement>) {
    val interactiveEmpty = elements.filter {
        (it.isClickable || it.isEditable) &&
        !it.isScrollable &&
        it.text.isBlank() &&
        it.description.isBlank()
    }
    val textElements = elements.filter {
        !it.isClickable && !it.isEditable && !it.isScrollable && it.text.isNotBlank()
    }
    for (parent in interactiveEmpty) {
        val contained = textElements.filter { child ->
            parent.bounds.contains(child.bounds)
        }.take(3)
        if (contained.isNotEmpty()) {
            parent.text = contained.joinToString(" | ") { it.text }
        }
    }
}
```

### 3c. Conditional `resource_id` output

**Source**: Both designs agree.

Output `id` field in prompt JSON only when current screen id density crosses threshold (e.g., >= 20% actionable nodes with id).

**Files**: `perception/Perceptor.kt`

---

## Phase 4: Stable Ordering

**Goal**: Spatial sort before index assignment; reduce cross-frame index flicker.

**Source**: Both designs agree on stable spatial ordering.

### 4a. Spatial sort

Collect elements without index in both passes, then sort spatially:

```kotlin
collected.sortWith(compareBy(
    { it.bounds.top / rowSnap(screenHeightPx) },  // row-first
    { it.bounds.left }                              // then column
))
collected.forEachIndexed { i, builder -> builder.index = i }
```

`rowSnap` = 2% of screen height (prevents micro-offset jitter).

### 4b. Interactive-priority truncation

**Source**: Codex Phase 3 ("交互性 + 信息密度 + 可见性打分截断").

When element count exceeds `MAX_ELEMENTS`, use a scoring function instead of pure DFS order to decide which elements survive:

```
score = interactiveWeight(3.0 if clickable/editable/scrollable else 1.0)
      * textWeight(1.5 if text non-blank else 1.0)
      * visibilityWeight(visibleAreaRatio)
```

Top-N by score, then re-sort spatially for index assignment.

**Files**: `perception/Perceptor.kt`

---

## Phase 5: Capture Robustness

**Source**: Both designs agree (identical proposals).

### 5a. `rootInActiveWindow` retry

```kotlin
suspend fun captureAccessibilityTree(): AccessibilityNodeInfo? {
    repeat(3) { attempt ->
        val root = service.rootInActiveWindow
        if (root != null) return root
        delay(150)
    }
    return null
}
```

### 5b. Quality metadata

```kotlin
data class CaptureQuality(
    val attempts: Int,
    val elementCount: Int,
    val capturedAt: Long,
    val emptyReason: String?  // "null_root" | "zero_visible_elements" | null
)
```

**Files**: `platform/AccessibilityPlatform.kt`

---

## Phase 6: Structure Context (Experimental)

**Source**: Claude P7.

**Not in Codex plan** — requires alignment discussion.

Three options for conveying hierarchy:

| Option | Token cost | Implementation cost | Information gain |
|--------|-----------|-------------------|-----------------|
| A: `depth` field | ~10 chars/elem | Low | Moderate |
| B: `in_scroll` index | ~15 chars/elem | Medium | High (scroll targeting) |
| C: Indented text format | Variable | High (breaks JSON parsing) | High |

**Recommendation**: A/B experiment after Phase 1-4 are validated. Not a blocker.

---

## Phase 7: Selector Contract (Downstream)

**Source**: Codex Phase 5. Claude does not cover this.

Improvements to how `MobileActionTool` / `TargetResolver` use Perceptor output:

1. Evaluate adding `resource_id` as a selector type (only when density threshold met)
2. ID-text consistency check (Minitap pattern): if resolved element's text doesn't match LLM expectation, fall back
3. Keep Perceptor output fields and selector parameters 1:1 aligned

**Files**: `tool/impl/MobileActionTool.kt`, `tool/action/TargetResolver.kt`

---

## Execution Order

```
Phase 0 (bounds safety) ←── immediate correctness fix
  ↓
Phase 1 (filter)     ←── no dependency, highest noise reduction
  ‖ parallel
Phase 2 (attributes) ←── no dependency, pure output format
  ↓
Phase 3 (text)       ←── depends on Phase 2 (hintText field)
  ↓
Phase 4 (ordering)   ←── after Phase 1+3 (filter/text changes affect sort input)
  ↓
Phase 5 (capture)    ←── independent, can be done anytime
  ↓
eval checkpoint      ←── quantify Phase 1-5 combined impact
  ↓
Phase 6 (structure)  ←── A/B experiment, gated by eval results
Phase 7 (selector)   ←── after eval confirms Perceptor changes are stable
```

## Eval Metrics

| Metric | Definition | Baseline source |
|--------|------------|-----------------|
| task_success_rate | Task completion rate | trace/meta.json |
| action_success_rate | Tool calls without error | tool_result artifacts |
| invisible_element_clicks | Clicks on visibleToUser=false elements | tool_call + raw_tree join |
| empty_text_ratio | Empty-text interactive elements / all interactive | sanitized_a11y_tree |
| non_clickable_target_rate | Click targets with clickable=false | tool_call args |
| avg_prompt_tokens | Per-turn a11y JSON token count | llm_full_prompt |
| empty_tree_rate | Snapshots with 0 elements | sanitized_a11y_tree |
| selector_success_by_package | Per-app action success | tool_result + package info |
| bounds_outlier_rate | Elements/clicks outside display bounds | screen_captured + tool_result |

## Affected Files Summary

| File | Phases | Changes |
|------|--------|---------|
| `perception/Perceptor.kt` | 1-4, 6 | visibleToUser filter, area ratio, explicit booleans, text enrichment, spatial sort |
| `perception/PerceptionConfig.kt` | 1 | New file: parameterized filter thresholds |
| `model/Models.kt` | 2, 6 | PerceptionElement: +selected, +checked, +checkable, +hintText, (+depth in Phase 6) |
| `platform/AccessibilityPlatform.kt` | 0, 5 | screen-size handoff, bounds diagnostics, retry + CaptureQuality |
| `tool/impl/MobileActionTool.kt` | 7 | Selector expansion (gated) |
| `tool/action/TargetResolver.kt` | 7 | ID-text consistency check (gated) |
| `app/src/test/...` | All | Unit tests per phase |


---

## Reality Checks (resolved with evidence)

### R1. Is `resource_id` really rare?
Yes, in current data it is sparse and uneven:
- debug raw nodes: `viewIdResourceName` non-empty = `8.48%`
- debug interactive nodes: non-empty = `3.45%`
- eval raw nodes: non-empty = `6.21%`
- per-snapshot median in eval: `0.0` (half of snapshots have no id)

Decision: keep `resource_id` as conditional/secondary signal, not primary selector.

### R2. Should `visibleToUser` be a primary filter?
Mostly yes, with guardrails:
- debug raw: `visibleToUser=false` ratio = `49.92%`
- eval raw: `visibleToUser=false` ratio = `10.67%` (domain-dependent)
- parent=false -> child=true is rare (`0.21%` in debug, `0%` in eval), so skipping invisible parent while still traversing children is safe.

Decision: use `visibleToUser` as first-stage filter, keep area-ratio filter as second stage, and keep config gate for rollback.

### R3. Is empty-text enrichment needed?
Yes, but scoped:
- debug raw interactive nodes with empty text+desc: `33.47%`
- debug sanitized interactive elements with empty `text`: `63.05%`
- among raw empty interactive nodes, `79.24%` have descendant text/desc

Decision: keep enrichment, but apply only to actionable candidates (clickable/editable, exclude pure scroll containers).

### R4. What is failing in eval right now?
- In `eval/results/20260217_171303`, `mobile_action` success/failure = `27/32`.
- Selector outcomes in eval traces: `element_index 20/43`, `x_y 0/7`, `text 0/2`.
- Repeated failures include taps like `(1285,472)` while emulator width is ~1080, indicating coordinate/bounds mismatch risk in some screens.

Decision: add an early bounds-normalization fix before other Perceptor upgrades.

---

## Resolved Questions

### Q1: Phase 6 (structure context) scope — **DEFERRED**
Phase 6 is deferred entirely until Phases 0-5 are validated and eval data shows structure context is a bottleneck. Rationale:
- Phases 0-5 address measurable, data-driven problems (47.3% invisible nodes, 47.2% empty text, bounds mismatches)
- No eval evidence yet that missing hierarchy information causes task failures
- Adding depth/scroll-container fields increases token cost without proven benefit
- If eval after Phase 5 shows scroll-target or container-disambiguation failures, revisit

### Q2: Scoring aggressiveness in Phase 4b — **FLOOR GUARANTEE**
Use 3.0x interactive weight but guarantee a non-interactive floor:

```
interactiveCap  = (MAX_ELEMENTS * 0.80).toInt()  // 64 of 80
nonInterFloor   = MAX_ELEMENTS - interactiveCap   // 16 of 80

val (interactive, nonInteractive) = scored.partition { it.isInteractive }
val kept = interactive.sortedByDescending { it.score }.take(interactiveCap) +
           nonInteractive.sortedByDescending { it.score }.take(nonInterFloor)
// re-sort spatially for index assignment
```

Rationale:
- Pure interactive boost risks losing labels like "Total: $29.99", error messages, or section headers that provide critical navigation context
- 20% floor (16 elements) ensures LLM always has some textual context
- If element count < MAX_ELEMENTS, no truncation occurs and the question is moot
