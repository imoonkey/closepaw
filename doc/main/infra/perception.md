# Screen Perception

> Perceptor, `ScreenSnapshot`, prompt shaping, and text-targeting semantics.
> Last updated: 2026-03-26

## Scope

The perception layer converts Android accessibility roots into a stable, node-free model the agent can reason about. Platform code owns root collection, screenshot capture, and display-specific wiring. Perception owns tree traversal, filtering, prompt JSON shaping, and the shared text contract used by prompts and text targeting.

| Concern | Owner |
|---------|-------|
| Collect a11y roots / detect keyboard | `AccessibilityPlatform`, `VirtualDisplayWindowAccessor` |
| A11y tree -> `ScreenSnapshot.elements` | `Perceptor.snapshot()` |
| Screenshot bytes | `AccessibilityScreenshotCapturer`, `VirtualDisplayCaptureCoordinator` |
| Prompt/history JSON | `Perceptor.toPromptJson()` |
| Compact observation summary | `ScreenSnapshot.toSummary()` |
| `Target.Text` resolution | `TargetResolver` |

---

## Design Principles

- **Lossless capture first**: `PerceptionElement.text`, `description`, and `hintText` preserve raw accessibility values. Capture does not normalize whitespace or invent labels.
- **Visible-text contract**: prompt `text` and text targeting share the same visible/accessibility fallback chain: `text -> description -> hintText`.
- **Downstream canonicalization only**: matching helpers may normalize for comparison (`trim() + lowercase()`), but stored values and prompt output stay raw.
- **Node-free snapshots**: `ScreenSnapshot` never stores `AccessibilityNodeInfo`; action layers re-query the live tree when they need fresh nodes.
- **Token control by structure**: truncation, visibility filtering, and id density heuristics reduce noise without destructively rewriting text.

---

## Data Flow

```text
Accessibility roots + display bounds
        |
        v
Perceptor.snapshot(...)
        |
        v
ScreenSnapshot(elements, image?, textEnriched, keyboardVisible)
        |
        +--> Perceptor.toPromptJson()      # prompt / history / trace JSON
        +--> TargetResolver.resolve()      # text / element_index -> coordinates
        +--> ScreenSnapshot.toSummary()    # compact observation summary
```

`AccessibilityPlatform` and `VirtualDisplayPlatform` both reuse the same `Perceptor.snapshot()` pipeline. The platform layer adds screenshot bytes, `keyboardVisible`, and trace/debug artifacts around that shared snapshot boundary.

---

## Capture Pipeline

> See: `perception/Perceptor.kt`, `perception/PerceptorInternals.kt`

`Perceptor.snapshot()` runs the same pipeline for main-display accessibility capture and virtual-display capture.

1. **Root collection happens outside the Perceptor**
   `AccessibilityPlatform` gathers all relevant roots on the active display and filters out overlay / IME windows. `VirtualDisplayPlatform` gathers roots for a specific `displayId`.
2. **Two-pass traversal favors interactive nodes**
   The tree is traversed once in `INTERACTIVE_ONLY` mode and once in `ALL` mode with shared dedup state. This gives interactive nodes first claim on the candidate pool without losing non-interactive labels.
3. **Each accepted node becomes a raw `PerceptionElement`**
   The capture step copies text, description, hint text, resource id, class, booleans, bounds, center point, and optional range info. No `AccessibilityNodeInfo` references escape. **Password fields** (`node.isPassword == true`) have their text replaced with `"[password]"` and description suppressed — this applies to all downstream paths (prompts, history, traces).
4. **Bounds and visibility are sanitized structurally**
   `clipBoundsToScreen()` clips rectangles to display bounds. `visibleAreaRatio()` and size thresholds drop tiny or mostly off-screen nodes. Known keyboard package ids are filtered when `filterKeyboard` is enabled.
5. **Text enrichment fills empty interactive containers**
   `enrichEmptyTextElements()` bubbles up to 3 descendant labels from non-interactive children into empty clickable/editable containers. Scroll containers are intentionally excluded.
6. **Candidate truncation keeps the prompt bounded**
   `applyTruncation()` scores nodes by interactivity, text presence, and visible area, then preserves up to `maxElements` with an `interactiveKeepRatio` floor for actionable elements.
7. **Spatial sort and final indexing happen last**
   `spatialSort()` groups by rows using `rowSnapScreenRatio`, then sorts left-to-right / top-to-bottom. Only after that does the final zero-based `index` get assigned.

The returned snapshot sets `textEnriched = true`. If tests or tooling build a `ScreenSnapshot` manually with `textEnriched = false`, `toPromptJson()` reruns enrichment on demand.

---

## Snapshot Boundary

> See: `model/Models.kt`

```kotlin
data class ScreenSnapshot(
    val timestamp: Long,
    val elements: List<PerceptionElement>,
    val image: ScreenImage? = null,
    val debug: ScreenSnapshotDebug? = null,
    val textEnriched: Boolean = false,
    val keyboardVisible: Boolean = false
)
```

Important semantics:

- `elements` is always present and may be empty.
- `image` is optional and depends on `PerceptionConfig`.
- `keyboardVisible` is set by the platform layer, not by `Perceptor`; prompt builders use it to warn that `BACK` dismisses the keyboard before navigating.
- `debug` stores relative trace artifact paths such as raw tree JSON, sanitized prompt JSON, screenshot output, and capture-quality diagnostics.

---

## Text Semantics

> See: `perception/PerceptorInternals.kt`, `tool/action/TargetResolver.kt`

| Signal | Stored raw in snapshot | Can become prompt `text` | Can satisfy `Target.Text` |
|--------|------------------------|--------------------------|---------------------------|
| `text` | Yes | First choice | Yes |
| `description` | Yes | Fallback | Yes, fallback path only |
| `hintText` | Yes | Fallback | Yes, fallback path only |
| `resourceId` | Yes | No | No |

Rules:

- `mergedText(element)` is `text -> description -> hintText`.
- `resourceId` never falls back into prompt `text`.
- `resourceId` is grounding metadata only. Action targeting modes remain `element_index`, `text`, or coordinates.
- `normalizeForMatching(value)` is `trim().lowercase()`. It is used only for duplicate indexing and text matching.

This is the core fidelity contract: perception is the source of truth, so capture keeps raw values intact and downstream layers normalize only when they have a narrow comparison purpose.

---

## Prompt JSON Contract

> See: `perception/Perceptor.kt`

`Perceptor.toPromptJson()` is the prompt-facing view of a snapshot.

- `text` is emitted only when `mergedText(element)` is non-blank.
- `text_index` is emitted only when `text` is emitted.
- `desc` and `desc_index` are emitted only when `description` is non-blank.
- `id` is emitted only when actionable-node id density crosses `resourceIdOutputDensityThreshold` (default `0.20`).
- `index`, `class`, `clickable`, `editable`, `scrollable`, `focused`, `long_clickable`, `bounds`, and `center` are always emitted.
- `enabled` is emitted only when false. `selected`, `checked`, `checkable`, `range_*`, and `hint_text` are conditional.

Duplicate indexes are canonicalized for matching only. For example, `" Save "` and `"save"` preserve their raw output values but share the same normalized duplicate bucket for `text_index`.

---

## Text Targeting Contract

> See: `tool/action/TargetResolver.kt`

`TargetResolver.resolve(Target.Text(...))` intentionally follows prompt semantics before using hidden accessibility fields.

1. Normalize the target with `normalizeForMatching()`.
2. Search elements whose normalized `mergedText(element)` matches.
3. If that search finds at least one match, use only those matches plus `textIndex`.
4. Only when the prompt-text search finds zero matches, retry against normalized `description` / `hintText` directly.

That behavior keeps prompt selection and action execution aligned. If a node has no visible/accessibility label and only a `resourceId`, it should be targeted by `element_index` or coordinates, not by text.

---

## Configuration

> See: `perception/PerceptionConfig.kt`, `perception/PerceptorFilterConfig.kt`

### PerceptionConfig

Session-level modality choice:

| Variant | Description |
|---------|-------------|
| `AccessibilityOnly` | A11y tree only. Current default. |
| `ScreenshotOnly(maxDimension, jpegQuality)` | Screenshot only. No a11y tree is shown to the LLM. |
| `Hybrid(maxDimension, jpegQuality)` | A11y tree + screenshot. Highest fidelity and highest token cost. |

Helpers:

- `capturesAccessibility`
- `capturesScreenshot`
- `screenshotMaxDimension`
- `screenshotJpegQuality`

### PerceptorFilterConfig

Snapshot-shaping knobs inside the a11y pipeline:

| Field | Purpose |
|-------|---------|
| `maxElements` | Hard cap after scoring/truncation |
| `minElementSizePx` | Drops tiny nodes |
| `visibilityThreshold` | Minimum visible area ratio for non-interactive nodes |
| `interactiveVisibilityThreshold` | Lower visibility floor for actionable nodes |
| `filterKeyboard` | Filters known IME package ids |
| `clipBounds` | Clips node rects to screen bounds |
| `resourceIdOutputDensityThreshold` | Controls when prompt JSON exposes `id` |
| `rowSnapScreenRatio` | Controls row grouping for spatial sort |
| `interactiveKeepRatio` | Minimum share reserved for actionable nodes during truncation |
| `useVisibleToUserFilter` | Applies `isVisibleToUser` gating before extraction |

---

## Downstream Consumers

- `PromptBuilder` and `TurnPlanningPhaseRunner` inject `Perceptor.toPromptJson(snapshot)` into the turn prompt or history only when `PerceptionConfig.capturesAccessibility` is true.
- `ObservationBuilder` uses the same prompt JSON after tool execution; in screenshot-only mode it emits a placeholder string instead of a fake tree.
- `UiChangeDetector` includes `textEnriched` and `keyboardVisible` in snapshot fingerprints.
- `ScreenSummary.toSummary()` produces a compact, intentionally lossy summary for history and observations.

### Security Masking

Before snapshots reach any downstream consumer, `AppClassifier.maskIfBlocked()` may replace them with empty snapshots (no elements, no image) when the foreground app is classified as `BLOCKED` (financial/auth). This masking is applied at two capture points:

1. **Pre-turn** (`AgentTurnRunner.capturePreTurnSnapshot`) — prevents the LLM from seeing BLOCKED app content
2. **Post-action** (`TurnExecutionPhaseRunner.captureObservationWithSnapshot`) — prevents observation leaks after tool execution

The Perceptor itself is unaware of security tiers; masking happens in the turn pipeline layer above it.

---

## Diagnostics And Trace

> See: `perception/PerceptorDiagnostics.kt`, `platform/AccessibilityPlatform.kt`

- `PerceptorDiagnosticsCollector` records negative or out-of-bounds coordinates during a single snapshot pass.
- `AccessibilityPlatform` can persist raw a11y tree JSON, sanitized prompt JSON, screenshots, and capture-quality artifacts into trace output.
- `ScreenSnapshotDebug` carries the relative paths to those artifacts so downstream tooling can inspect exactly what the agent saw.

Platform-specific transport details still live in [platform.md](platform.md).

---

## File Structure

```text
perception/
├── PerceptionConfig.kt        # Session-level modality selection
├── Perceptor.kt               # A11y tree -> ScreenSnapshot + prompt JSON
├── PerceptorFilterConfig.kt   # Filtering and prompt-shaping knobs
├── PerceptorInternals.kt      # Traversal helpers, enrichment, truncation, matching
├── PerceptorDiagnostics.kt    # Bounds diagnostics collector
└── ScreenSummary.kt           # Compact summary for history/observations
```

External consumers:

- `model/Models.kt` - snapshot and element data classes
- `tool/action/TargetResolver.kt` - text/element targeting
- `agent/cognition/prompt/PromptBuilder.kt` - prompt assembly

---

## Related Docs

- [Platform](platform.md) - platform selection, action execution, and capture transports
- [Tools](tools.md) - mobile action execution and post-action observations
- [Loop](../agent/loop.md) - perception inside the ReAct turn loop
