# Mobile Action Architecture V2: Cross-Module Redesign

## Executive Summary

The current mobile action code path involves **8 distinct classes** to process a single click.
This document proposes a 3-layer architecture that reduces this to **4 classes** with clear
responsibilities, eliminates multi-selector fallback complexity, and puts API-level retry logic
where it belongs: in the platform layer.

**Core decisions:**
1. UIAction mirrors the LLM prompt's targeting model (element_index / text / coordinate)
2. Only ONE targeting method per action. Multiple = validation error.
3. API-level fallback (ACTION_CLICK -> gesture tap) lives in the platform layer
4. UI change detection is a platform concern, not a tool concern

---

## Current Architecture: What's Wrong

### The 8-class click path

```
LLM JSON
  -> MobileActionTool         (dispatches by "action" field)
    -> ClickActionHandler      (validates, creates invocation)
      -> ClickTargetInvocation (builds attempt plan)
        -> MultiSelectorTargeting  (parses ALL selectors from params)
        -> TargetingInvocationUtils (executes attempts, UI change detect)
          -> AndroidPlatform.performAction(UIAction)
            -> AccessibilityPlatform (dispatches by UIAction variant)
              -> performNodeClickAt / performTapAt  (atomic APIs)
```

### Specific problems

| Problem | Where | Impact |
|---------|-------|--------|
| Multi-selector allowed | MultiSelectorTargeting | 3 selectors x 2 APIs = 6 attempts. Silently clicks wrong thing. |
| Confusing directory split | `tool/handlers/` vs `tool/impl/mobileaction/` | ActionHandler validates, *Invocation executes. Split has no clear rationale. |
| UIAction mismatch | UIAction.kt | ClickNodeAt takes (x,y), LongClick takes elementIndex, Type takes both. Inconsistent. |
| Fallback in wrong layer | ClickTargetInvocation | Tool layer builds platform-level retry chains. Leaks Android API details up. |
| Near-identical handlers | Click/LongPress/TypeActionHandler | 90% duplicated validation code across 3 files. |
| Dead abstractions | MultiActionTool, BaseTool | Framework machinery that adds indirection without value. |
| 750+ line platform file | AccessibilityPlatform.kt | Mixes captureScreen, gesture dispatch, type logic, long press, swipe. |

### Class count by layer (current)

```
tool/impl/mobileaction/    4 files  (ActionHandlers)
tool/handlers/             8 files  (*Invocations + utilities)
tool/                      2 files  (MultiActionTool, BaseTool)
platform/                  5 files  (AccessibilityPlatform, UIAction, etc.)
                          --------
Total touch points:       19 files
```

---

## Design Principles

1. **KISS** - Every class earns its existence. If you can't explain why a class exists in one
   sentence, delete it.
2. **3 layers max** for action execution: Tool Definition -> Execution Glue -> Platform Execution.
3. **Single targeting** - One action, one target. The LLM knows what it wants to click.
4. **Platform owns fallback** - The tool layer says "click element 3". The platform decides
   how (ACTION_CLICK, gesture tap, etc.).
5. **400 lines/file** - No god classes. Split by action type, not by abstraction layer.
6. **Testable seams** - AndroidPlatform interface is the test boundary. Mock the platform, not
   6 layers of indirection.

---

## Target Architecture

### Layer Overview

```
┌─────────────────────────────────────────────────────────┐
│  Layer 1: TOOL DEFINITION                               │
│                                                         │
│  MobileActionTool.kt                                    │
│  - LLM schema, parameter validation                     │
│  - Enforces single targeting method                     │
│  - Constructs UIAction from JSON params                 │
│  - Creates MobileActionInvocation                       │
│                                                         │
│  Knows about: JSON, LLM params, UIAction types          │
│  Does NOT know about: Android APIs, nodes, gestures     │
├─────────────────────────────────────────────────────────┤
│  Layer 2: EXECUTION GLUE                                │
│                                                         │
│  MobileActionInvocation.kt                              │
│  - Calls platform.performAction(uiAction, snapshot)     │
│  - Converts ActionResult -> ToolExecutionResult         │
│  - Converts postSnapshot -> ToolObservation             │
│  - Handles cancellation                                 │
│                                                         │
│  Knows about: ToolSpec framework, ActionResult          │
│  Does NOT know about: targeting, fallback, Android APIs │
├─────────────────────────────────────────────────────────┤
│  Layer 3: PLATFORM EXECUTION                            │
│                                                         │
│  AccessibilityPlatform.kt (coordinator)                 │
│    ├── ClickExecutor.kt                                 │
│    ├── LongPressExecutor.kt                             │
│    ├── TypeExecutor.kt                                  │
│    └── SwipeExecutor.kt                                 │
│                                                         │
│  - Resolves Target -> coordinates/node                  │
│  - API-level fallback (ACTION_CLICK -> gesture tap)     │
│  - UI change detection (pre/post snapshot comparison)   │
│  - Returns ActionResult with postSnapshot               │
│                                                         │
│  Knows about: AccessibilityService, nodes, gestures     │
│  Does NOT know about: JSON, LLM, tool framework        │
└─────────────────────────────────────────────────────────┘
```

### Why exactly 3 layers?

- **Layer 1 (Tool)**: Converts LLM intent (JSON) to typed action (UIAction). Cannot be merged
  with Layer 3 because tool layer should not know about Android APIs.
- **Layer 2 (Glue)**: Bridges the tool framework (ToolInvocation/ToolExecutionResult) with the
  platform (ActionResult). ~40 lines. Could arguably be inlined, but the ToolInvocation interface
  requires a class for the approval UI and cancellation flow.
- **Layer 3 (Platform)**: Executes actions using Android accessibility APIs. Cannot be merged
  with Layer 1 because platform should not know about JSON params or LLM concerns.

---

## Interface Definitions

### UIAction (redesigned)

```kotlin
package com.moonkey.androidagent.platform

/**
 * UIAction - Intent-level actions matching the LLM's mobile_action prompt.
 *
 * Each targeted action accepts exactly ONE Target. The platform layer
 * handles API-level fallback internally.
 */
sealed interface UIAction {

    /**
     * Targeting method for an action. Exactly one per action call.
     * Preference order (documented in prompt): ElementIndex > Text > Coordinate
     */
    sealed interface Target {
        data class ElementIndex(val index: Int) : Target
        data class Text(val text: String, val textIndex: Int = 0) : Target
        data class Coordinate(val x: Int, val y: Int) : Target
    }

    // === Targeted actions ===

    data class Click(val target: Target) : UIAction

    data class LongPress(
        val target: Target,
        val durationMs: Long = 1000
    ) : UIAction

    data class Type(
        val inputText: String,
        val target: Target? = null,  // null = type into currently focused field
        val clear: Boolean = false
    ) : UIAction

    // === Coordinate-only actions ===

    data class Swipe(
        val startX: Int, val startY: Int,
        val endX: Int, val endY: Int,
        val durationMs: Long = 300
    ) : UIAction

    data class SwipeDirection(
        val direction: Direction,
        val distance: Distance = Distance.MEDIUM,
        val durationMs: Long = 400
    ) : UIAction {
        enum class Direction { UP, DOWN, LEFT, RIGHT }
        enum class Distance { SHORT, MEDIUM, LONG }
    }

    // === System actions (unchanged) ===

    data class SystemButton(val button: SystemButtonType) : UIAction

    data class Wait(val durationMs: Long) : UIAction
}
```

**Key changes from current UIAction:**
- `ClickNodeAt(x, y)` and `TapAt(x, y)` **deleted** - these are now internal to ClickExecutor
- `LongClick(elementIndex)` and `LongClickAt(x, y)` **merged** into `LongPress(target)`
- `Type(text, elementIndex?, clear)` now uses `Target?` instead of raw `elementIndex`
- New `SwipeDirection` variant replaces complex direction parsing in SwipeTargetInvocation
- Atomic API choices (ACTION_CLICK vs gesture tap) are **not** exposed in UIAction

### ActionResult (simplified)

```kotlin
package com.moonkey.androidagent.platform

/**
 * ActionResult - Result of platform action execution.
 *
 * Success includes postSnapshot for observation capture and UI change verification.
 * ElementNotFound is absorbed into Failure (platform resolves targets internally).
 */
sealed interface ActionResult {
    data class Success(
        val message: String,
        val postSnapshot: ScreenSnapshot? = null
    ) : ActionResult

    data class Failure(
        val reason: String
    ) : ActionResult

    data class Cancelled(
        val reason: String = "Action cancelled"
    ) : ActionResult

    fun isSuccess(): Boolean = this is Success
}
```

**Key changes:**
- `ElementNotFound` **deleted** - platform resolves elements internally, returns descriptive Failure
- `Success` now carries `postSnapshot` - captured during UI change detection, reused for observation
- `Failure.exception` **removed** - exceptions should be logged at source, not carried through layers

### AndroidPlatform (simplified signature)

```kotlin
interface AndroidPlatform {
    suspend fun captureScreen(): ScreenSnapshot
    suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot? = null): ActionResult
    fun hasRequiredPermissions(): Boolean
    fun getCurrentPackageName(): String?
    fun getDisplayInfo(): DisplayInfo
    suspend fun getInstalledApps(): List<AppInfo>
    suspend fun launchApp(packageName: String): ActionResult
}
```

No signature change to `performAction`. The semantic change is that the platform now handles
all targeting resolution and API fallback internally.

---

## Execution Flows

### Click: `{"action": "click", "element_index": 3}`

```
MobileActionTool.validate()
  -> Exactly one target? Yes (element_index only). Valid.

MobileActionTool.createInvocation()
  -> UIAction.Click(Target.ElementIndex(3))
  -> MobileActionInvocation(uiAction)

MobileActionInvocation.execute()
  -> platform.performAction(UIAction.Click(Target.ElementIndex(3)), snapshot)

AccessibilityPlatform.performAction()
  -> delegates to ClickExecutor.execute(target, snapshot)

ClickExecutor.execute():
  1. resolveTarget(ElementIndex(3), snapshot)
     -> find element 3 in snapshot -> center = (540, 960)
  2. preSnapshot = snapshot
  3. Try ACTION_CLICK:
     -> findClickableNodeAt(540, 960) -> node found
     -> node.performAction(ACTION_CLICK) -> success
     -> captureScreen() -> postSnapshot
     -> uiChanged(preSnapshot, postSnapshot)? YES
     -> return Success("Clicked element 3 via ACTION_CLICK", postSnapshot)

MobileActionInvocation:
  -> postSnapshot -> ToolObservation.ScreenState(...)
  -> ToolExecutionResult.Success(output, observation)
```

**If ACTION_CLICK succeeds but UI doesn't change:**

```
ClickExecutor.execute():
  3. ACTION_CLICK -> success, but NO UI change -> continue
  4. Try gesture tap:
     -> dispatchTap(540f, 960f) -> success
     -> captureScreen() -> postSnapshot
     -> uiChanged? YES
     -> return Success("Clicked element 3 via gesture tap", postSnapshot)
```

**If all attempts fail:**

```
ClickExecutor.execute():
  3. ACTION_CLICK -> no UI change
  4. gesture tap -> no UI change
  -> return Failure("Click at (540,960) failed: no UI change after all API attempts")
```

### Long Press: `{"action": "long_press", "text": "Delete", "duration_ms": 1500}`

```
MobileActionTool.validate()
  -> Single target? Yes (text only). Valid.

ClickExecutor is replaced by LongPressExecutor:
  1. resolveTarget(Text("Delete", 0), snapshot)
     -> find element with text "Delete" -> center = (200, 400)
  2. Try ACTION_LONG_CLICK on node at (200, 400)
  3. If fail/no change: try gesture long press (hold 1500ms)
  4. Return with postSnapshot
```

### Type: `{"action": "type", "input_text": "hello", "element_index": 5, "clear": true}`

```
TypeExecutor.execute():
  1. target = ElementIndex(5)
  2. resolveTarget -> element 5 center = (300, 600)
  3. Tap (300, 600) to focus the field
  4. delay(FOCUS_DELAY_MS)
  5. Re-query tree, find editable node at (300, 600)
  6. If clear: ACTION_SET_TEXT("")
  7. ACTION_SET_TEXT("hello")
  8. captureScreen() -> postSnapshot
  9. Return Success with postSnapshot
```

### Type (no target): `{"action": "type", "input_text": "hello"}`

```
TypeExecutor.execute():
  1. target = null
  2. Find currently focused editable node
  3. ACTION_SET_TEXT("hello")
  4. Return with postSnapshot
```

### Swipe: `{"action": "swipe", "direction": "up", "distance": "medium"}`

```
SwipeExecutor.execute():
  1. Get display info -> compute safe area
  2. Compute start/end from direction + distance + screen center
  3. dispatchSwipe(startX, startY, endX, endY, durationMs)
  4. captureScreen() -> detect scroll boundary
  5. Return Success (with boundary warning if applicable)
```

### Validation error: `{"action": "click", "element_index": 3, "x": 100, "y": 200}`

```
MobileActionTool.validate():
  -> element_index present AND x/y present
  -> count = 2 targeting methods
  -> return Invalid("click accepts only one targeting method. Got: element_index, x/y")
```

---

## File Inventory

### Files to DELETE (13 files)

```
tool/handlers/
  ActionHandler.kt              # Interface with no value in new design
  ClickTargetInvocation.kt      # Replaced by ClickExecutor in platform
  LongPressTargetInvocation.kt  # Replaced by LongPressExecutor
  TypeTargetInvocation.kt       # Replaced by TypeExecutor
  SwipeTargetInvocation.kt      # Replaced by SwipeExecutor
  MultiSelectorTargeting.kt     # Multi-selector concept eliminated
  TargetingInvocationUtils.kt   # Split: UI change -> UiChangeDetector,
                                #        observation -> MobileActionInvocation

tool/impl/mobileaction/         # Entire directory
  ClickActionHandler.kt         # Validation moved to MobileActionTool
  LongPressActionHandler.kt
  TypeActionHandler.kt
  SwipeActionHandler.kt

tool/
  MultiActionTool.kt            # Abstract base class with no remaining subclass
  BaseTool.kt                   # Dead code (no subclasses)
```

### Files to MODIFY (5 files)

| File | Change |
|------|--------|
| `platform/UIAction.kt` | Redesign with Target sealed class. Remove ClickNodeAt, TapAt, LongClickAt. |
| `platform/ActionResult.kt` | Remove ElementNotFound. Add postSnapshot to Success. Remove exception from Failure. |
| `platform/AccessibilityPlatform.kt` | Slim down. Delegate targeted actions to executors. Keep captureScreen, gesture dispatch, system button, wait. |
| `platform/AndroidPlatform.kt` | No API change. Just remove any references to deleted types. |
| `tool/impl/MobileActionTool.kt` | Rewrite: implement ToolSpec directly. Inline validation. Construct UIAction. |

### Files to CREATE (6 files)

| File | ~Lines | Responsibility |
|------|--------|----------------|
| `platform/action/ClickExecutor.kt` | ~90 | Target resolution + ACTION_CLICK / gesture tap fallback + UI change verification |
| `platform/action/LongPressExecutor.kt` | ~90 | Target resolution + ACTION_LONG_CLICK / gesture hold fallback + UI change verification |
| `platform/action/TypeExecutor.kt` | ~100 | Target resolution + tap-to-focus + ACTION_SET_TEXT |
| `platform/action/SwipeExecutor.kt` | ~200 | Direction/distance computation + gesture dispatch + scroll boundary detection |
| `platform/action/UiChangeDetector.kt` | ~60 | Snapshot fingerprinting + change comparison |
| `tool/impl/MobileActionInvocation.kt` | ~50 | Generic glue: ActionResult -> ToolExecutionResult + observation capture |

### Files UNCHANGED (kept as-is)

```
platform/AccessibilityNodeFinder.kt  # Used by executors for node lookup
platform/DisplayInfo, AppInfo         # Data classes
tool/ToolSpec.kt                      # Core framework interface
tool/handlers/UIActionInvocation.kt   # Used by SystemButtonTool, WaitTool
tool/handlers/DataQueryInvocation.kt  # Used by non-mobile-action tools
tool/impl/SystemButtonTool.kt         # Uses UIActionInvocation (no change)
tool/impl/WaitTool.kt                 # Uses UIActionInvocation (no change)
```

### Post-redesign file count

```
tool/impl/                  2 files  (MobileActionTool, MobileActionInvocation)
tool/handlers/              2 files  (UIActionInvocation, DataQueryInvocation)
platform/                   5 files  (AccessibilityPlatform, UIAction, etc.)
platform/action/            5 files  (executors + UiChangeDetector)
                           --------
Total touch points:        14 files
```

Down from 19 files. More importantly, the **conceptual complexity** drops dramatically:
no multi-selector, no attempt plans, no dedup sets, no nested when-expressions across selectors.

---

## Key Interface: Executor Pattern

Each executor follows the same contract:

```kotlin
/**
 * Resolves a UIAction.Target to screen coordinates, executes the action
 * using API-level fallback, and verifies UI change.
 *
 * Dependencies are injected via constructor for testability.
 */
class ClickExecutor(
    private val service: AccessibilityService,
    private val gestureDispatcher: GestureDispatcher,
    private val uiChangeDetector: UiChangeDetector,
    private val visualizer: ActionVisualizerManager?,
    private val captureScreen: suspend () -> ScreenSnapshot
) {
    suspend fun execute(target: UIAction.Target, snapshot: ScreenSnapshot?): ActionResult {
        // 1. Resolve target to coordinates
        val coords = resolveTarget(target, snapshot)
            ?: return ActionResult.Failure(describeResolutionFailure(target, snapshot))

        val preSnapshot = snapshot

        // 2. API-level fallback chain
        val attempts = listOf(
            AtomicAttempt("ACTION_CLICK") { tryActionClick(coords.x, coords.y) },
            AtomicAttempt("gesture tap")  { gestureDispatcher.tap(coords) }
        )

        val attemptLog = mutableListOf<String>()
        for (attempt in attempts) {
            val result = attempt.execute()
            if (result is ActionResult.Failure) {
                attemptLog.add("${attempt.label}: ${result.reason}")
                continue
            }

            // 3. Verify UI changed
            val postSnapshot = captureScreen()
            if (uiChangeDetector.hasChanged(preSnapshot, postSnapshot)) {
                return ActionResult.Success(
                    message = "Clicked (${coords.x}, ${coords.y}) via ${attempt.label}",
                    postSnapshot = postSnapshot
                )
            }
            attemptLog.add("${attempt.label}: no UI change")
        }

        return ActionResult.Failure(
            "Click at (${coords.x}, ${coords.y}) failed. ${attemptLog.joinToString("; ")}"
        )
    }

    private fun resolveTarget(
        target: UIAction.Target,
        snapshot: ScreenSnapshot?
    ): Point? = when (target) {
        is UIAction.Target.ElementIndex -> {
            snapshot?.elements?.firstOrNull { it.index == target.index }
                ?.let { Point(it.center.x, it.center.y) }
        }
        is UIAction.Target.Text -> {
            snapshot?.elements?.filter {
                it.text.equals(target.text, ignoreCase = true) ||
                it.description.equals(target.text, ignoreCase = true)
            }?.getOrNull(target.textIndex)
                ?.let { Point(it.center.x, it.center.y) }
        }
        is UIAction.Target.Coordinate -> Point(target.x, target.y)
    }
}
```

**Compare with current ClickTargetInvocation**: 228 lines with nested when-expressions,
dedup sets, attempt plans, and selector-to-coordinate mapping scattered across 3 helper methods.
The executor above is ~60 lines of linear, readable code.

---

## MobileActionTool (Rewritten)

```kotlin
class MobileActionTool : ToolSpec {
    override val name = "mobile_action"

    override val description = """
Perform touch interactions on the mobile device screen.

Targeting (for click, long_press, type):
Specify EXACTLY ONE targeting method per action:
- element_index: index from current screen state (preferred)
- text + text_index: visible text on screen
- x, y: absolute pixel coordinates (last resort)

Actions:
- click: Tap target. Example: {"action":"click","element_index":3}
- long_press: Long press target. Example: {"action":"long_press","text":"Delete"}
- type: Type text. Example: {"action":"type","input_text":"hello","element_index":5}
- swipe: Swipe gesture. Example: {"action":"swipe","direction":"up"}
""".trimIndent()

    override fun validate(params: JSONObject): ValidationResult {
        val action = params.optString("action", "")
        if (action.isEmpty()) return ValidationResult.Invalid("Missing required parameter: action")

        return when (action) {
            "click", "long_press" -> validateSingleTarget(params, action, required = true)
            "type" -> validateTypeAction(params)
            "swipe" -> validateSwipeAction(params)
            else -> ValidationResult.Invalid("Unknown action: '$action'")
        }
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val action = params.getString("action")
        val uiAction = when (action) {
            "click" -> UIAction.Click(parseTarget(params))
            "long_press" -> UIAction.LongPress(
                target = parseTarget(params),
                durationMs = params.optLong("duration_ms", 1000)
            )
            "type" -> UIAction.Type(
                inputText = params.getString("input_text"),
                target = parseOptionalTarget(params),
                clear = params.optBoolean("clear", false)
            )
            "swipe" -> parseSwipeAction(params)
            else -> error("Unreachable: validated above")
        }
        return MobileActionInvocation(params, buildDescription(params), uiAction)
    }

    // === Validation Helpers ===

    private fun validateSingleTarget(
        params: JSONObject, action: String, required: Boolean
    ): ValidationResult {
        val hasElement = params.has("element_index")
        val hasText = params.optString("text", "").trim().isNotEmpty()
        val hasCoords = params.has("x") || params.has("y")

        val count = listOf(hasElement, hasText, hasCoords).count { it }
        if (count == 0 && required) {
            return ValidationResult.Invalid(
                "$action requires one of: element_index, text, or x/y"
            )
        }
        if (count > 1) {
            return ValidationResult.Invalid(
                "$action accepts only ONE targeting method. " +
                "Got: ${targetNames(hasElement, hasText, hasCoords)}"
            )
        }
        // Validate the specific method...
        return ValidationResult.Valid
    }

    // === Target Parsing ===

    private fun parseTarget(params: JSONObject): UIAction.Target = when {
        params.has("element_index") ->
            UIAction.Target.ElementIndex(params.getInt("element_index"))
        params.optString("text", "").trim().isNotEmpty() ->
            UIAction.Target.Text(params.getString("text"), params.optInt("text_index", 0))
        params.has("x") && params.has("y") ->
            UIAction.Target.Coordinate(params.getInt("x"), params.getInt("y"))
        else -> error("No target: should be caught by validation")
    }
}
```

**What disappeared:**
- `MultiActionTool` base class (implement `ToolSpec` directly)
- `ActionHandler` interface (validation is inline)
- `ClickActionHandler` / `LongPressActionHandler` / `TypeActionHandler` (all inline)
- `MultiSelectorTargeting` (single target, parsed in 10 lines)
- Separate `tool/impl/mobileaction/` directory (gone)

---

## MobileActionInvocation (New Unified Glue)

```kotlin
class MobileActionInvocation(
    override val params: JSONObject,
    private val description: String,
    private val uiAction: UIAction
) : ToolInvocation {
    override val toolName = "mobile_action"

    override fun getDescription(): String {
        val thought = params.optString("agent_thought", "").trim()
        return if (thought.isNotEmpty()) "$description ($thought)" else description
    }

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) return ToolExecutionResult.Cancelled()

        val result = context.platform.performAction(uiAction, context.currentSnapshot)
        return when (result) {
            is ActionResult.Success -> {
                val observation = result.postSnapshot?.let { snapshot ->
                    val tree = Perceptor.toPromptJson(snapshot)
                    ToolObservation.ScreenState(
                        accessibilityTree = tree,
                        elementCount = snapshot.elements.size,
                        summary = snapshot.toSummary(context.platform.getCurrentPackageName()),
                        snapshot = snapshot
                    )
                }
                ToolExecutionResult.Success(output = result.message, observation = observation)
            }
            is ActionResult.Failure -> ToolExecutionResult.Failure(result.reason)
            is ActionResult.Cancelled -> ToolExecutionResult.Cancelled(result.reason)
        }
    }
}
```

**~40 lines**. Replaces UIActionInvocation (105 lines), ClickTargetInvocation (228 lines),
LongPressTargetInvocation (172 lines), TypeTargetInvocation (222 lines) for mobile_action use.

Note: `UIActionInvocation` is NOT deleted because `SystemButtonTool` and `WaitTool` still use it.
It stays as a general-purpose invocation for non-targeted UIActions.

---

## UiChangeDetector

Extracted from the current `TargetingInvocationUtils` into a focused utility:

```kotlin
package com.moonkey.androidagent.platform.action

/**
 * Compares pre-action and post-action snapshots to determine if
 * a meaningful UI change occurred. Used by executors to verify
 * that an action had a visible effect.
 */
object UiChangeDetector {

    fun hasChanged(pre: ScreenSnapshot?, post: ScreenSnapshot?): Boolean {
        if (pre == null || post == null) return true  // unverifiable = treat as changed
        return fingerprint(pre) != fingerprint(post)
    }

    fun detectScrollBoundary(pre: ScreenSnapshot?, post: ScreenSnapshot?): String? {
        // ... existing logic from TargetingInvocationUtils.detectScrollBoundary
    }

    private fun fingerprint(snapshot: ScreenSnapshot): Long {
        // ... existing FNV-1a hash from TargetingInvocationUtils.snapshotFingerprint
    }
}
```

---

## AccessibilityPlatform Changes

The main `performAction` dispatcher simplifies to:

```kotlin
override suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot?): ActionResult {
    return when (action) {
        is UIAction.Click -> clickExecutor.execute(action.target, snapshot)
        is UIAction.LongPress -> longPressExecutor.execute(action.target, action.durationMs, snapshot)
        is UIAction.Type -> typeExecutor.execute(action.inputText, action.target, action.clear, snapshot)
        is UIAction.Swipe -> swipeExecutor.execute(action, snapshot)
        is UIAction.SwipeDirection -> swipeExecutor.executeDirectional(action, snapshot)
        is UIAction.SystemButton -> performSystemButton(action)
        is UIAction.Wait -> performWait(action)
    }
}
```

**What stays in AccessibilityPlatform.kt (~350 lines):**
- `captureScreen()` + screenshot logic
- `GestureDispatcher` (dispatchGesture, performTap, performSwipeGesture helpers)
- `performSystemButton()`, `performEnterKey()`
- `performWait()`
- `getInstalledApps()`, `launchApp()`

**What moves to `platform/action/`:**
- Click fallback chain -> `ClickExecutor.kt`
- Long press fallback chain -> `LongPressExecutor.kt`
- Type focus-then-set-text logic -> `TypeExecutor.kt`
- Directional swipe computation -> `SwipeExecutor.kt`
- UI change detection -> `UiChangeDetector.kt`

---

## Dependency Graph (Before vs After)

### Before

```
MobileActionTool
  -> MultiActionTool (base)
  -> ClickActionHandler -> ClickTargetInvocation
                             -> MultiSelectorTargeting
                             -> TargetingInvocationUtils
                               -> AndroidPlatform -> AccessibilityPlatform
  -> LongPressActionHandler -> LongPressTargetInvocation -> (same)
  -> TypeActionHandler -> TypeTargetInvocation -> (same)
  -> SwipeActionHandler -> SwipeTargetInvocation -> (same)
                        -> UIActionInvocation -> (same)
```

### After

```
MobileActionTool
  -> MobileActionInvocation
    -> AndroidPlatform
      -> AccessibilityPlatform
        -> ClickExecutor, LongPressExecutor, TypeExecutor, SwipeExecutor
          -> AccessibilityNodeFinder, UiChangeDetector
```

**One path. Linear. No branching abstractions.**

---

## Testing Strategy

### Unit Test Targets

| Component | Mock | Test |
|-----------|------|------|
| MobileActionTool validation | None | Single target enforcement, parameter combinations |
| MobileActionTool.createInvocation | None | Correct UIAction construction from JSON |
| MobileActionInvocation | AndroidPlatform | ActionResult -> ToolExecutionResult mapping |
| ClickExecutor | AccessibilityService (or extracted interface) | Fallback chain, UI change gating |
| LongPressExecutor | Same | Same pattern |
| TypeExecutor | Same | Focus-then-type flow, no-target path |
| SwipeExecutor | DisplayInfo | Direction/distance computation |
| UiChangeDetector | None (pure function) | Fingerprint stability, change detection |

### Key Test Cases

1. **Single target enforcement**: click with element_index + x/y -> validation error
2. **Target resolution**: ElementIndex -> correct coordinates from snapshot
3. **Fallback chain**: ACTION_CLICK fails (no UI change) -> gesture tap succeeds
4. **All attempts fail**: Returns Failure with attempt trail
5. **No snapshot + element target**: Returns Failure("Snapshot required...")
6. **Type without target**: Types into focused field
7. **Swipe boundary detection**: Pre/post snapshots identical -> warning

### What changes for existing tests

- `MultiSelectorTargetingTest.kt` -> **DELETE** (multi-selector concept eliminated)
- `TargetInvocationsTest.kt` -> **REWRITE** as executor tests
  - Test executors directly with mocked service/gesture dispatcher
  - Much simpler setup: no ToolExecutionContext, no JSONObject param construction

---

## Migration Plan

### Phase 1: Platform layer (no external API changes)

1. Create `platform/action/` directory
2. Implement `UiChangeDetector.kt` (extract from TargetingInvocationUtils)
3. Implement `ClickExecutor.kt` with new Target-based interface
4. Implement `LongPressExecutor.kt`
5. Implement `TypeExecutor.kt`
6. Implement `SwipeExecutor.kt`
7. Update `UIAction.kt` with Target sealed class
8. Update `ActionResult.kt` (add postSnapshot, remove ElementNotFound)
9. Update `AccessibilityPlatform.kt` to delegate to executors

### Phase 2: Tool layer

10. Create `MobileActionInvocation.kt`
11. Rewrite `MobileActionTool.kt` (implement ToolSpec directly, inline validation)
12. Update `MobileActionTool` prompt description (single target requirement)

### Phase 3: Cleanup

13. Delete `tool/handlers/ClickTargetInvocation.kt`
14. Delete `tool/handlers/LongPressTargetInvocation.kt`
15. Delete `tool/handlers/TypeTargetInvocation.kt`
16. Delete `tool/handlers/SwipeTargetInvocation.kt`
17. Delete `tool/handlers/MultiSelectorTargeting.kt`
18. Delete `tool/handlers/TargetingInvocationUtils.kt`
19. Delete `tool/handlers/ActionHandler.kt`
20. Delete `tool/impl/mobileaction/` directory (all 4 files)
21. Delete `tool/MultiActionTool.kt`
22. Delete `tool/BaseTool.kt`

### Phase 4: Tests

23. Delete `MultiSelectorTargetingTest.kt`
24. Rewrite `TargetInvocationsTest.kt` as executor tests
25. Add MobileActionTool validation tests

### Verification

After each phase: `./gradlew clean assembleDebug lint test`

---

## Open Questions

1. **UI change detection for type action**: Should typing verify UI change? Text fields update
   their content, which changes the a11y tree. Current design does this implicitly via
   postSnapshot capture. Probably yes, but worth considering edge cases (autocomplete, etc.).

2. **Configurable settle delay**: Different apps have different animation speeds. The current
   hardcoded 300ms may not be enough for slow apps. Consider making this configurable per-action
   or per-session in a future iteration.

3. **GestureDispatcher extraction**: For testability, the gesture dispatch (tap, swipe, long press)
   could be extracted into a `GestureDispatcher` interface that executors depend on. This avoids
   passing `AccessibilityService` directly to executors. Adds one interface but dramatically
   improves testability. Recommended for Phase 1.

4. **Post-snapshot capture cost**: Currently, UI change detection captures a full screen snapshot
   after EACH atomic attempt. For a 2-attempt click fallback, that's 2 extra captureScreen() calls
   in the worst case. This is acceptable since the second attempt only happens on failure. But
   worth monitoring for performance.

---

## Summary: Before vs After

| Metric | Before | After |
|--------|--------|-------|
| Files touched per click | 8 | 4 |
| Total files in mobile_action path | 19 | 14 |
| Abstraction layers | 5+ | 3 |
| Lines: click execution | ~500 (spread across 4 files) | ~90 (ClickExecutor) |
| Multi-selector complexity | O(selectors x APIs) | O(APIs) per single target |
| Validation code duplication | 3x (Click/LongPress/Type handlers) | 1x (inline in MobileActionTool) |
| Time to understand "how does click work?" | Read 4+ files | Read 1 file (ClickExecutor) |
