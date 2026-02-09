# Final Design: Screen State Equality

## Design Decision

After analyzing both the Claude 4.5 and Gemini proposals against the current codebase, this design takes a **surgical hybrid approach**: Claude 4.5's KISS philosophy with targeted structural improvements.

### Why This Approach

| Decision | Rationale |
|----------|-----------|
| **Keep `ScreenSnapshot` name** | Used in 15+ files. Rename adds churn with zero functional gain. |
| **Make `elements` nullable** (Claude 4.5) | One-line model change. Every consumer already handles `snapshot` via `?.` patterns. |
| **NOT wrapper types** (reject Gemini) | `snapshot.accessibility?.elements` is MORE churn than `snapshot.elements?.`. Wrappers add indirection for a codebase of this size with no proportional value. |
| **`PerceptionConfig` sealed class** (Claude 4.5) | Type-safe, exhaustive `when` handling, factory methods for debug. Enum is less expressive. |
| **Add to `SessionConfig`** (not `AgentExecutionConfig`) | Perception is session-level. `SessionConfig` already owns `enableScreenshotInput`. Unify there. |
| **Replace `enableScreenshotInput` boolean** | `PerceptionConfig` subsumes it. One source of truth, no boolean-config drift. |

### Core Principle

> Make the smallest change that achieves full modality equality. No new types that don't earn their keep. Every line of diff should serve the user's goal: "I want to easily switch between a11y-only, screenshot-only, and hybrid."

---

## 1. Domain Model Changes

### Current
```kotlin
data class ScreenSnapshot(
    val timestamp: Long,
    val elements: List<PerceptionElement>,  // Always present
    val image: ScreenImage? = null,
    val debug: ScreenSnapshotDebug? = null
)
```

### Proposed
```kotlin
data class ScreenSnapshot(
    val timestamp: Long,
    val elements: List<PerceptionElement>?,  // Nullable: absent in screenshot-only mode
    val image: ScreenImage? = null,
    val debug: ScreenSnapshotDebug? = null
) {
    init {
        require(elements != null || image != null) {
            "ScreenSnapshot must have at least one perception modality"
        }
    }

    /** True when accessibility tree data is available */
    val hasAccessibility: Boolean get() = !elements.isNullOrEmpty()

    /** True when a screenshot is available */
    val hasScreenshot: Boolean get() = image != null
}
```

**Key insight**: The `require()` invariant prevents invalid states at construction time. The convenience properties eliminate scattered null-check patterns.

---

## 2. PerceptionConfig

```kotlin
/**
 * Controls which perception modalities the agent captures each turn.
 *
 * Sealed class ensures exhaustive when-handling and prevents invalid states.
 * Replaces the boolean `enableScreenshotInput` in SessionConfig.
 */
sealed class PerceptionConfig {

    /** Accessibility tree only. Current production default. */
    data object AccessibilityOnly : PerceptionConfig()

    /** Screenshot only. For apps with poor a11y support. */
    data class ScreenshotOnly(
        val maxDimension: Int = 1024,
        val jpegQuality: Int = 70
    ) : PerceptionConfig()

    /** Both modalities. Richest perception, highest token cost. */
    data class Hybrid(
        val maxDimension: Int = 1024,
        val jpegQuality: Int = 70
    ) : PerceptionConfig()

    /** Whether this config captures accessibility data */
    val capturesAccessibility: Boolean get() = this !is ScreenshotOnly

    /** Whether this config captures screenshots */
    val capturesScreenshot: Boolean get() = this !is AccessibilityOnly

    companion object {
        val DEFAULT = AccessibilityOnly
    }
}
```

**Design choices**:
- `data object` for `AccessibilityOnly` — no parameters, singleton equality.
- Screenshot params (`maxDimension`, `jpegQuality`) live on the configs that capture screenshots. Not duplicated.
- `capturesAccessibility` / `capturesScreenshot` computed properties eliminate `when` in callers.

---

## 3. SessionConfig Integration

### Current
```kotlin
data class SessionConfig(
    // ...
    val enableScreenshotInput: Boolean = false,
    val screenshotMaxDimension: Int = 1024,
    val screenshotJpegQuality: Int = 70
)
```

### Proposed
```kotlin
data class SessionConfig(
    // ...
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT
    // Remove: enableScreenshotInput, screenshotMaxDimension, screenshotJpegQuality
)
```

Three booleans/ints collapse into one typed config. Backward-compatible: default is `AccessibilityOnly` (matches current `enableScreenshotInput = false`).

---

## 4. Platform Layer Changes

### AndroidPlatform interface
```kotlin
interface AndroidPlatform {
    suspend fun captureScreen(perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT): ScreenSnapshot
    // ... rest unchanged
}
```

### AccessibilityPlatform.captureScreen()
```kotlin
override suspend fun captureScreen(perceptionConfig: PerceptionConfig): ScreenSnapshot {
    // 1. Accessibility capture (conditional)
    val elements: List<PerceptionElement>? = if (perceptionConfig.capturesAccessibility) {
        val root = withContext(Dispatchers.Main) { service.rootInActiveWindow }
        // ... trace raw tree if enabled ...
        val snapshot = Perceptor.snapshot(root)
        // ... trace sanitized tree if enabled ...
        snapshot.elements
    } else null

    // 2. Screenshot capture (conditional)
    val image: ScreenImage? = if (perceptionConfig.capturesScreenshot || traceRecorder.enabled) {
        captureScreenshotIfEnabled(windowId, enabled = true)?.image
    } else null

    // 3. Only include image in snapshot if config requests it (trace may capture for debug only)
    val snapshotImage = if (perceptionConfig.capturesScreenshot) image else null

    return ScreenSnapshot(
        timestamp = System.currentTimeMillis(),
        elements = elements,
        image = snapshotImage,
        debug = buildDebugInfo(...)
    )
}
```

**Key**: Trace always captures screenshot for debugging even when not in snapshot. This preserves existing debug-trace behavior.

---

## 5. PromptBuilder Adaptation

```kotlin
internal fun buildObservationText(
    snapshot: ScreenSnapshot,
    image: ScreenImage?,
    warnings: List<String>
): String {
    return buildString {
        // Warnings first
        for (warning in warnings) { appendLine(warning) }
        if (warnings.isNotEmpty()) appendLine()

        // Accessibility section
        if (snapshot.hasAccessibility) {
            val screenJson = Perceptor.toPromptJson(snapshot)
            appendLine("Screen state (${snapshot.elements!!.size} elements):")
            appendLine("```json")
            appendLine(screenJson)
            append("```")
        } else {
            appendLine("No accessibility tree available.")
            append("Use coordinate-based actions or visual analysis.")
        }

        // Screenshot section
        if (image != null && llmBackend == LLMBackendType.OPENAI) {
            if (snapshot.hasAccessibility) appendLine()
            appendLine()
            append("Screenshot attached (analyze visually if needed).")
        }
    }.trim()
}
```

---

## 6. Tool Implications

### TargetResolver
When `snapshot.elements` is null:
- `Target.ElementIndex` → resolve returns `null`, describeFailure says: "Element-based actions unavailable in screenshot-only mode. Use coordinate (x, y)."
- `Target.Text` → same pattern.
- `Target.Coordinate` → always works, regardless of perception mode.

### Other tools
- `UIActionInvocation`: scroll boundary detection uses `elements` — guard with `?.` , skip boundary detection when null.
- `UiChangeDetector`: element-based diff uses `elements` — return "unknown" change when null.
- `ObservationBuilder`: `elements.size` → `elements?.size ?: 0`.

---

## 7. Perceptor.toPromptJson()

Currently takes `ScreenSnapshot`. When `elements` is null, it should return an empty array or not be called at all. The caller (`PromptBuilder`) already gates on `hasAccessibility`, so `toPromptJson` is only called when elements exist. Add a guard:

```kotlin
fun toPromptJson(snapshot: ScreenSnapshot): String {
    val elements = snapshot.elements ?: return "[]"
    // ... existing logic using elements ...
}
```

---

## 8. Migration Strategy & Execution Plan

### Phase 1: Domain Model + Configuration (Foundation)
**Files**: `Models.kt`, `PerceptionConfig.kt` (new), `Op.kt`

1. Make `ScreenSnapshot.elements` nullable, add `require()`, add convenience properties
2. Create `PerceptionConfig` sealed class in `perception/` package
3. Add `perceptionConfig` to `SessionConfig`, remove 3 old screenshot fields
4. Update `AppSettingsState` / `AppSettingsStore` for new config shape
5. Unit test `ScreenSnapshot` invariant and `PerceptionConfig` properties

### Phase 2: Platform Layer (Capture Logic)
**Files**: `AndroidPlatform.kt`, `AccessibilityPlatform.kt`

1. Add `perceptionConfig` parameter to `AndroidPlatform.captureScreen()`
2. Refactor `AccessibilityPlatform.captureScreen()` to conditionally capture based on config
3. Preserve trace-always-captures-screenshot behavior
4. Unit test: mock platform returns correct snapshot shape per config

### Phase 3: Consumer Adaptation (Pipeline)
**Files**: `PromptBuilder.kt`, `AgentTurnRunner.kt`, `TargetResolver.kt`, `UIActionInvocation.kt`, `UiChangeDetector.kt`, `ObservationBuilder.kt`, `Perceptor.kt`

1. Update `PromptBuilder.buildObservationText()` for nullable elements
2. Update `Perceptor.toPromptJson()` guard
3. Update `TargetResolver` — null-safe element resolution + clear error messages
4. Update `AgentTurnRunner` — pass config, handle null elements in logging/tracking
5. Update remaining tool files for null safety
6. Update `compressScreenContent()` regex for new observation format

### Phase 4: Wiring + Settings UI
**Files**: `MainActivity.kt`, settings UI composables, `AgentTurnRunner` config passing

1. Wire `PerceptionConfig` from UI settings dropdown → `SessionConfig` → `AccessibilityPlatform`
2. Three-option selector: "Accessibility Only" / "Screenshot Only" / "Hybrid"
3. Show screenshot quality settings only when relevant (screenshot/hybrid modes)
4. Update existing tests for new config shape

---

## 9. Open Questions (Deferred)

These are explicitly out of scope for this implementation:

1. **VLM for screenshot-only mode** — future: use VLM to generate pseudo-elements from screenshots
2. **Auto-degradation** — future: if a11y capture fails, auto-switch to screenshot mode
3. **Token-aware smart resize** — future: MobileAgent-v3 style dynamic resize based on VLM patch size

---

## 10. Risk Assessment

| Risk | Mitigation |
|------|------------|
| Null safety regressions | Kotlin compiler enforces null checks. CI build catches all `elements.` → `elements?.` misses. |
| Existing tests break | Phase 1 default is `AccessibilityOnly` — existing behavior unchanged. |
| Screenshot quality loss | Carry over existing `maxDimension=1024`, `jpegQuality=70` defaults. |
| Tool failures in screenshot-only | Clear error messages guide user to coordinate-based actions. |
