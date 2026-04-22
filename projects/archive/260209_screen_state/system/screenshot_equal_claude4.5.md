# Design: Screen State Equality (Claude 4.5 Perspective)

## Problem Statement

The agent currently treats Accessibility Tree as the **primary** screen representation (converted to sanitized JSON), with screenshots as a secondary, optional attachment. This creates issues when apps have poor accessibility support—the agent becomes blind to UI elements it cannot access through the a11y tree.

**Goal**: Make **Accessibility** and **Screenshot** true equals in the domain model and perception pipeline, enabling flexible configuration between three modes:
1. Accessibility Only (current default)
2. Screenshot Only (for apps with bad a11y)
3. Hybrid (both)

---

## Design Philosophy

This design differs from the Gemini approach in several key ways:

| Aspect | Gemini Approach | Claude 4.5 Approach |
|--------|-----------------|---------------------|
| **Model Change** | New `AccessibilityState` / `ScreenshotState` wrappers | Minimal model change: make `elements` nullable |
| **Validation** | Runtime `require()` check | Sealed class configuration enforces at compile time |
| **Config Location** | `PerceptionMode` enum in `AgentExecutionConfig` | Dedicated `PerceptionConfig` with factory methods |
| **Migration Risk** | Higher (new model types) | Lower (additive changes only) |

### Core Principle: KISS with Progressive Enhancement

Rather than restructuring the domain model around new wrapper types, we:
1. Make current `ScreenSnapshot.elements` nullable (minimal change)
2. Add a typed `PerceptionConfig` to control capture behavior
3. Update consumers to handle nullability gracefully

---

## 1. Domain Model Changes

### Current Model
```kotlin
data class ScreenSnapshot(
    val timestamp: Long,
    val elements: List<PerceptionElement>,  // Implicitly required
    val image: ScreenImage? = null,
    val debug: ScreenSnapshotDebug? = null
)
```

### Proposed Model
```kotlin
data class ScreenSnapshot(
    val timestamp: Long,
    val elements: List<PerceptionElement>?,  // Now nullable
    val image: ScreenImage? = null,
    val debug: ScreenSnapshotDebug? = null
) {
    init {
        require(elements != null || image != null) {
            "ScreenSnapshot must have at least one modality"
        }
    }
    
    /** True if accessibility tree is available */
    val hasAccessibility: Boolean get() = !elements.isNullOrEmpty()
    
    /** True if visual capture is available */
    val hasScreenshot: Boolean get() = image != null
}
```

**Key insight**: We don't need new wrapper types—nullable fields with a validity invariant achieve the same goal with less refactoring.

---

## 2. Perception Configuration

### Configuration Design
```kotlin
/**
 * Controls what modalities the agent perceives.
 * 
 * Sealed class ensures exhaustive handling and prevents invalid states.
 */
sealed class PerceptionConfig {
    /** Capture accessibility tree only (current default) */
    data class AccessibilityOnly(
        val includeDebugTree: Boolean = false
    ) : PerceptionConfig()
    
    /** Capture screenshot only—for apps with poor a11y */
    data class ScreenshotOnly(
        val quality: ImageQuality = ImageQuality.AUTO
    ) : PerceptionConfig()
    
    /** Capture both modalities */
    data class Hybrid(
        val includeDebugTree: Boolean = false,
        val imageQuality: ImageQuality = ImageQuality.AUTO
    ) : PerceptionConfig()
    
    companion object {
        /** Default production config */
        val DEFAULT = AccessibilityOnly()
        
        /** Debug-friendly hybrid mode */
        val DEBUG_HYBRID = Hybrid(includeDebugTree = true)
    }
}

enum class ImageQuality { LOW, AUTO, HIGH }
```

### Integration with AgentExecutionConfig
```kotlin
data class AgentExecutionConfig(
    // ... existing fields ...
    
    /**
     * Controls what screen modalities are captured.
     * Default: accessibility only for backward compatibility.
     */
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT
)
```

---

## 3. Platform Layer Changes

### AccessibilityPlatform.captureScreen()

Current signature:
```kotlin
suspend fun captureScreen(): ScreenSnapshot
```

Proposed change: add config parameter or read from session config.

```kotlin
suspend fun captureScreen(config: PerceptionConfig = PerceptionConfig.DEFAULT): ScreenSnapshot {
    val shouldCaptureA11y = config !is PerceptionConfig.ScreenshotOnly
    val shouldCaptureImage = config !is PerceptionConfig.AccessibilityOnly
    
    val elements = if (shouldCaptureA11y) {
        captureAccessibilityTree()
    } else null
    
    val image = if (shouldCaptureImage) {
        captureScreenshot()
    } else null
    
    return ScreenSnapshot(
        timestamp = System.currentTimeMillis(),
        elements = elements,
        image = image,
        debug = buildDebugInfo(elements, image)
    )
}
```

---

## 4. PromptBuilder Adaptation

The key consumer of `ScreenSnapshot` is `PromptBuilder.buildObservationText()`. Changes:

```kotlin
internal fun buildObservationText(
    snapshot: ScreenSnapshot,
    image: ScreenImage?,
    warnings: List<String>
): String {
    return buildString {
        // Warnings first
        warnings.forEach { appendLine(it) }
        if (warnings.isNotEmpty()) appendLine()
        
        // Accessibility section
        if (snapshot.hasAccessibility) {
            val screenJson = Perceptor.toPromptJson(snapshot)
            appendLine("Screen state (${snapshot.elements!!.size} elements):")
            appendLine("```json")
            appendLine(screenJson)
            append("```")
        } else {
            appendLine("⚠️ Accessibility tree unavailable for this screen.")
            appendLine("Actions requiring element indices won't work.")
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

## 5. Tool Implications

### Element-Based Tools (click by index/text)
When `elements` is null:
- `mobile_action` with `index` parameter → **Error**: "Element-based actions unavailable in screenshot-only mode. Use coordinates."
- `mobile_action` with `text` parameter → **Error**: Same

### Coordinate-Based Tools
Always available regardless of perception mode.

### Recommendation
In screenshot-only mode, encourage coordinate-based actions or consider adding:
- A `describeScreen` tool that uses VLM to analyze the screenshot
- Fallback prompting: "If you cannot find elements, describe what you see and use coordinates."

---

## 6. Debug Experience

### Debug Output Structure
```
trace/run_xxx/
├── turn_01/
│   ├── pre_turn/
│   │   ├── raw_a11y.json         # Only if a11y enabled
│   │   ├── sanitized_a11y.json   # Only if a11y enabled
│   │   └── screenshot.png        # Only if screenshot enabled
│   └── ...
```

### Quick Mode Switching
For debug runs, expose simple toggles:

```kotlin
// In debug UI or config
val perceptionModes = listOf(
    "A11y Only" to PerceptionConfig.AccessibilityOnly(),
    "Screenshot Only" to PerceptionConfig.ScreenshotOnly(),
    "Hybrid (Debug)" to PerceptionConfig.DEBUG_HYBRID
)
```

---

## 7. Migration Strategy

### Phase 1: Make `elements` Nullable (Low Risk)
1. Change `ScreenSnapshot.elements` from `List<PerceptionElement>` to `List<PerceptionElement>?`
2. Add `require()` check for at least one modality
3. Add `hasAccessibility` / `hasScreenshot` convenience properties
4. Update all consumers to handle null (mostly `?.let` or `?: emptyList()`)

**Files affected**: `Models.kt`, `Perceptor.kt`, `PromptBuilder.kt`, tools that read elements

### Phase 2: Add PerceptionConfig (Medium)
1. Create `PerceptionConfig` sealed class
2. Add to `AgentExecutionConfig`
3. Update `AccessibilityPlatform.captureScreen()` to respect config

### Phase 3: UI Integration (Optional)
1. Add debug mode selector in settings
2. Expose perception mode in trace metadata

---

## 8. Open Questions

1. **VLM for Screenshot-Only Mode**: Should we add a visual analysis tool that generates pseudo-elements from screenshot OCR/detection?
先不考虑
2. **Graceful Degradation**: If a11y capture fails mid-session, should we auto-switch to screenshot mode?
先不考虑
3. **Token Cost**: Screenshots cost ~258-1000 tokens. Should we add quality selection (low/auto/high) based on budget?
先不考虑

---

## Summary

This design achieves screen state equality through:
- **Minimal model change**: nullable `elements` with invariant check
- **Typed configuration**: `PerceptionConfig` sealed class prevents invalid states
- **Progressive enhancement**: backward compatible by default
- **Clear debug experience**: mode switching for troubleshooting

**Key difference from Gemini design**: Avoids introducing new wrapper types (`AccessibilityState`, `ScreenshotState`), reducing refactoring scope while achieving the same flexibility.
