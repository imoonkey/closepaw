# Design: Tool System DRY-up

**Priority**: P2 — DRY
**Files affected**: 10+ tool implementation files in `tool/impl/`

---

## Problem

Tool implementations repeat 3 patterns across 10+ files:

### 1. `buildDescription()` boilerplate (10+ files)

Every tool that isn't `complete_task` builds a human-readable description string:

```kotlin
// Repeated in MobileActionTool, SystemButtonTool, WaitTool, OpenAppTool, etc.
private fun buildDescription(params: JSONObject): String {
    val action = params.optString("action", "unknown")
    return "Performing $action"
}
```

Each file writes its own version with slightly different formatting. The result is used by `ActionDescriptionFormatter` — which also has its own formatting logic, creating a second path.

### 2. Observation construction (5+ files)

Tools that capture post-action screen state repeat:

```kotlin
val snapshot = context.platform.captureScreen()
val a11yTree = Perceptor.toPromptJson(snapshot)
ToolObservation.ScreenState(
    accessibilityTree = a11yTree,
    elementCount = snapshot.elements.size,
    summary = snapshot.toSummary(context.platform.getCurrentPackageName()),
    snapshot = snapshot
)
```

This 5-line block appears in `MobileActionTool`, `SystemButtonTool`, `OpenAppTool`, and `WaitTool`.

### 3. Scroll boundary detection (2 files)

`UIActionInvocation.kt` and `UiChangeDetector.kt` both independently implement "did the scroll actually change anything?" by comparing element sets before/after.

## Solution

### 1. Extract `ToolDescriptionBuilder`

```kotlin
// tool/ToolDescriptionBuilder.kt
object ToolDescriptionBuilder {
    fun mobileAction(action: String, params: JSONObject): String { ... }
    fun systemButton(button: String): String { ... }
    fun wait(durationMs: Long): String { ... }
    fun openApp(appName: String): String { ... }
    fun generic(toolName: String, params: JSONObject): String { ... }
}
```

Align `ActionDescriptionFormatter` to delegate to the same builder, eliminating the dual-path.

### 2. Extract `ObservationFactory`

```kotlin
// tool/ObservationFactory.kt
object ObservationFactory {
    suspend fun captureScreenObservation(
        platform: AndroidPlatform
    ): Pair<ToolObservation.ScreenState, ScreenSnapshot> {
        val snapshot = platform.captureScreen()
        val a11yTree = Perceptor.toPromptJson(snapshot)
        val observation = ToolObservation.ScreenState(
            accessibilityTree = a11yTree,
            elementCount = snapshot.elements.size,
            summary = snapshot.toSummary(platform.getCurrentPackageName()),
            snapshot = snapshot
        )
        return observation to snapshot
    }

    fun textObservation(text: String): ToolObservation.TextOutput =
        ToolObservation.TextOutput(text)
}
```

### 3. Unify scroll boundary detection

Extract to a single utility:

```kotlin
// tool/ScrollChangeDetector.kt
object ScrollChangeDetector {
    fun hasContentChanged(
        before: List<PerceptionElement>,
        after: List<PerceptionElement>
    ): Boolean { ... }
}
```

Both `UIActionInvocation` and `UiChangeDetector` reference this.

## Steps

1. Create `tool/ToolDescriptionBuilder.kt` — extract description formatting from each tool
2. Refactor `ActionDescriptionFormatter` to delegate to `ToolDescriptionBuilder`
3. Create `tool/ObservationFactory.kt` — extract observation construction
4. Replace inline observation construction in each tool with `ObservationFactory.captureScreenObservation()`
5. Create `tool/ScrollChangeDetector.kt` — extract from `UIActionInvocation`
6. Update `UiChangeDetector` to use `ScrollChangeDetector`
7. Remove duplicate code from each tool file

## Risks

- **Low**: Each extraction is a pure refactoring — same logic, different location
- **Low**: `ObservationFactory` is stateless, easy to test
