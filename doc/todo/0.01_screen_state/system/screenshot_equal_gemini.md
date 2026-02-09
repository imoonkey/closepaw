# Design: Screen State Equality (Screenshot = Accessibility Tree)

## Context
Currently, the agent treats Accessibility Tree information as the "first-class" representation of the screen state, converted to JSON. Screenshots are treated as "second-class" citizens, optionally attached.
This design proposes refactoring the definition of `ScreenState` (currently `ScreenSnapshot`) to treat both Accessibility Information and Screenshot Images as parallel, equal components.
We also want to support flexible configuration to allow switching between:
1.  **Accessibility Only** (Current default)
2.  **Screenshot Only** (Visual-only agent)
3.  **Hybrid** (Both)

## Goals
1.  **Decouple**: Remove the assumption that `elements` (A11y nodes) are always present.
2.  **Equality**: Redefine `ScreenState` to hold nullable `accessibility` and nullable `screenshot`.
3.  **Validation**: Enforce that a `ScreenState` must contain *at least one* valid representation.
4.  **Configurability**: Allow easy toggling of perception modes at runtime/debug-time.

## 1. Domain Model Changes

### Current Model
```kotlin
data class ScreenSnapshot(
    val timestamp: Long,
    val elements: List<PerceptionElement>, // Implies A11y is mandatory (or empty)
    val image: ScreenImage? = null,        // Optional
    val debug: ScreenSnapshotDebug? = null
)
```

### Proposed Model

We will likely rename `ScreenSnapshot` to `ScreenState` to better reflect its nature, or keep `ScreenSnapshot` but change its structure. For this design, let's use `ScreenState` as the conceptual name (implementation might stick to `ScreenSnapshot` to reduce churn, or migrate).

```kotlin
/**
 * Represents the perceived state of the screen at a point in time.
 * Must contain at least one of [accessibility] or [screenshot].
 */
data class ScreenState(
    val timestamp: Long,
    val accessibility: AccessibilityState?,
    val screenshot: ScreenshotState?,
    val debug: ScreenStateDebug? = null
) {
    init {
        require(accessibility != null || screenshot != null) {
            "ScreenState must contain at least accessibility info or a screenshot."
        }
    }
}

data class AccessibilityState(
    val rootPackageName: String?,
    val elements: List<PerceptionElement>
)

data class ScreenshotState(
    val image: ScreenImage,
    // Future: Potential for OCR results or visual features here
)
```

This change requires updating all consumers of `ScreenSnapshot.elements` to handle nullability (i.e., when running in Visual-only mode).

## 2. Perception System Refactoring

### Perception Configuration
We introduce a `PerceptionMode` to controls what the agent "sees".

```kotlin
enum class PerceptionMode {
    ACCESSIBILITY_ONLY,
    SCREENSHOT_ONLY,
    HYBRID // Both
}
```

This config should be part of `AgentExecutionConfig`.

### Perceptor Update
The `Perceptor` object currently mixes "traversing a11y tree" with "creating a snapshot". We should split these concerns.

```kotlin
object Perceptor {
    fun perceive(
        mode: PerceptionMode,
        accessibilityNode: AccessibilityNodeInfo?,
        screenshotProvider: () -> ScreenImage?
    ): ScreenState {
        val a11yState = if (mode != SCREENSHOT_ONLY) {
             // ... extract elements from accessibilityNode ...
             AccessibilityState(...)
        } else null

        val visualState = if (mode != ACCESSIBILITY_ONLY) {
             val image = screenshotProvider()
             if (image != null) ScreenshotState(image) else null
        } else null

        return ScreenState(..., a11yState, visualState)
    }
}
```

## 3. Agent & Pipeline Refactoring

### AgentTurnRunner
`AgentTurnRunner` needs to respect the `PerceptionMode`.
Currently `capturePreTurnSnapshot` calls `services.platform.captureScreen()`. We need to change how platform captures data.
Ideally, `Platform` should provide raw data, and `Perceptor` constructs the domain object.

### PromptBuilder (Critical)
`PromptBuilder` constructs the LLM input. It must now handle cases where A11y is missing.

**Current Logic:**
- Always `Perceptor.toPromptJson(snapshot)`
- Optionally attach image.

**New Logic:**
```kotlin
fun buildObservationSection(...): ResponseInputItem {
    val contentParts = mutableListOf<...>()

    // 1. Accessibility Logic
    if (screenState.accessibility != null) {
         val json = Perceptor.toPromptJson(screenState.accessibility)
         contentParts.add(Text("Screen State (JSON): ... $json"))
    } else {
         contentParts.add(Text("Note: Accessibility information is unavailable for this turn."))
    }

    // 2. Visual Logic
    if (screenState.screenshot != null) {
         if (backend supports images) {
             contentParts.add(Image(screenState.screenshot.image))
         }
         contentParts.add(Text("Screenshot attached."))
    }
    
    // ...
}
```

## 4. Migration Strategy
1.  **Phase 1 (Refactor `ScreenSnapshot`)**:
    *   Modify `ScreenSnapshot` to have nullable `elements` (or `accessibilityState`).
    *   Mark `elements` as deprecated if moving to `accessibilityState`.
    *   Update codebase to handle null `elements` (mostly `PromptBuilder` and Tools).
    *   **Tools Consideration**: Tools like `click` (by index/text) rely on A11y. If A11y is missing, these tools might fail or need fallback (e.g., generic click by coordinates). This is a larger implication: **Visual-only mode requires visual-coordinate based tools.**

2.  **Phase 2 (Config & Logic)**:
    *   Add `PerceptionMode` to config.
    *   Update `AgentTurnRunner` to orchestrate capture based on mode.

3.  **Phase 3 (Prompt Updates)**:
    *   Update `PromptBuilder` to dynamically generate prompt based on available state components.

## Open Questions / Risks
- **Tool Compatibility**: If we switch to "Screenshot Only", identifying elements by numeric index (`index`) will be impossible unless we run OCR/Detection to generate pseudo-elements. The user's note implies "I want to switch... debug". This might mean they accept that some tools won't work, OR we need a "Visual Perception" layer (VLM) to generate element indices from the image.
- **For this design**: We assume that if A11y is missing, standard index-based actions might simply not be available, or the generic "click(x, y)" must be used.
