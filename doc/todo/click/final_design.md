# Mobile Action Final Design

## Executive Summary

This is the definitive design for refactoring mobile_action. It resolves the 8-class click path, scattered fallback logic, and UIAction interface inconsistencies.

**Core architectural decision**: Codex Option 1 — AccessibilityPlatform stays atomic (only wraps Android APIs). A middle "executor" layer handles all targeting resolution, fallback orchestration, and UI change verification.

**Key absorptions from V2**:
- Keep flat JSON params (no LLM interface migration)
- Per-action executor classes (clean code organization)
- `ActionResult` simplification (remove `ElementNotFound`, `exception`)
- Constructor injection pattern for testability
- Phased migration plan with file-level inventory
- Thin invocation glue (~40 lines)

**Key retentions from Codex Option 1**:
- Atomic platform principle (platform has zero strategy)
- Fallback tables as specification (clear, testable attempt ordering)
- Two-layer success model (dispatch success vs interaction success)
- `NodeLocator` concept (Phase 2: element_index → true node semantics)
- "No cross-target-type fallback" constraint

**Fixes for review-identified risks**:
- `Unverifiable` is a distinct outcome, not silently treated as success
- Cancellation checked between fallback attempts
- Attempt trail in all outcomes for debuggability
- Settle delay before UI change detection

---

## Current Problems (Brief)

```
LLM JSON
  → MobileActionTool → ClickActionHandler → ClickTargetInvocation
    → MultiSelectorTargeting → TargetingInvocationUtils
      → AccessibilityPlatform → performNodeClickAt / performTapAt
```

8 classes, 19 files. Specific issues:
1. Multi-selector allowed → 6 attempts per click, silently clicks wrong thing
2. `tool/handlers/` vs `tool/impl/mobileaction/` split has no clear rationale
3. UIAction mixes intent-level (`LongClick(elementIndex)`) with atomic-level (`ClickNodeAt(x,y)`)
4. Fallback logic in tool layer leaks Android API details upward (in current implementation)
5. 90% duplicated validation across Click/LongPress/Type handlers
6. `AccessibilityPlatform.kt` at 750+ lines, mixing screen capture, gesture dispatch, and intent-level type/long-press logic

---

## Design Principles

1. **Atomic Platform** — `AccessibilityPlatform` wraps exactly one Android API call per action. No fallback. No target resolution. No UI change detection. If you add a new fallback strategy, you never touch the platform.

2. **Single Smart Layer** — All "intelligence" (target resolution, attempt planning, fallback execution, outcome evaluation) lives in the executor layer. One place to understand, one place to test, one place to change.

3. **Single Targeting** — One action, one target. `element_index` OR `text` OR `x,y`. Multiple = validation error. No implicit priority between target types.

4. **No Cross-Target Fallback** — If the LLM says `element_index: 3`, we resolve element 3 to coordinates and try different APIs at those coordinates. We never silently switch to text or coordinate targeting.

5. **Dispatch ≠ Interaction** — Android API returning `true` (dispatch success) does not mean the UI changed (interaction success). Executors verify UI change after each attempt before declaring success.

6. **Flat JSON, Strict Validation** — Keep the current LLM interface (flat `element_index`, `text`, `x`, `y` params). Enforce one-of in validation code, not in JSON schema structure.

7. **400 Lines/File** — No god classes. Each executor ≤ 100 lines.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│  Layer 1: TOOL CONTRACT                                       │
│                                                               │
│  MobileActionTool.kt                                          │
│  - LLM schema definition (flat params)                        │
│  - One-of target validation                                   │
│  - Parses JSON → Target + action params                       │
│  - Creates MobileActionInvocation                             │
│                                                               │
│  MobileActionInvocation.kt (~40 lines)                        │
│  - Thin glue: routes to executor, maps outcome to             │
│    ToolExecutionResult, handles cancellation                  │
│                                                               │
│  Knows: JSON, ToolSpec framework, Target                      │
│  Does NOT know: Android APIs, nodes, gestures, fallback       │
├──────────────────────────────────────────────────────────────┤
│  Layer 2: ACTION EXECUTORS (the single smart layer)           │
│                                                               │
│  ClickExecutor.kt          (~80 lines)                        │
│  LongPressExecutor.kt      (~80 lines)                        │
│  TypeExecutor.kt           (~100 lines)                       │
│  SwipeExecutor.kt          (~80 lines)                        │
│  TargetResolver.kt         (~60 lines)                        │
│  UiChangeDetector.kt       (~50 lines)                        │
│                                                               │
│  - Resolves Target → coordinates                              │
│  - Builds attempt plan per action type                        │
│  - Executes attempts via atomic platform calls                │
│  - Verifies UI change between attempts                        │
│  - Returns ActionOutcome with attempt trail                   │
│                                                               │
│  Knows: Target, UIAction (atomic types), ActionResult         │
│  Does NOT know: JSON, ToolSpec, Android Service internals     │
├──────────────────────────────────────────────────────────────┤
│  Layer 3: ATOMIC PLATFORM                                     │
│                                                               │
│  AccessibilityPlatform.kt                                     │
│  - Each UIAction variant = exactly one Android API call       │
│  - ClickNodeAt: find clickable node → ACTION_CLICK            │
│  - TapAt: dispatchGesture (tap)                               │
│  - LongClickNodeAt: find node → ACTION_LONG_CLICK             │
│  - LongPressAt: dispatchGesture (hold)                        │
│  - SetTextOnNodeAt: find node → ACTION_SET_TEXT                │
│  - SetTextOnFocused: find focused → ACTION_SET_TEXT            │
│  - Swipe: dispatchGesture (swipe)                             │
│  - SystemButton / Wait: unchanged                             │
│                                                               │
│  Returns: ActionResult (simple success/failure)               │
│                                                               │
│  Knows: AccessibilityService, AccessibilityNodeInfo, gestures │
│  Does NOT know: Target, fallback, UI change, JSON, tools      │
└──────────────────────────────────────────────────────────────┘
```

### Why This Layering (Not V2's)

V2 puts fallback in the platform (executors inside `platform/action/`). This is pragmatic but has structural problems:

1. **Strategy ≠ mechanism.** "Try ACTION_CLICK first, then gesture tap" is a *strategy* decision. "How to call ACTION_CLICK" is a *mechanism*. Mixing them in the platform means platform changes when strategy changes.

2. **Testability.** Testing fallback strategies in the executor layer requires mocking only `AndroidPlatform` (one interface). Testing them inside the platform requires mocking `AccessibilityService` + `GestureDispatcher` (Android framework classes).

3. **Evolvability.** When we add NodeLocator (Phase 2), only the executor layer changes. The platform stays stable. If NodeLocator were in the platform, it would need access to perception data — platform responsibilities leak.

4. **Visibility.** In V2, the tool says "Click element 3" and the platform is a black box. In this design, the executor's attempt trail makes every decision visible. The agent and developer both see: `element(3) → (540,960) → ACTION_CLICK: no UI change → gesture_tap: success`.

---

## Type Definitions

### Target (tool/action/Target.kt)

```kotlin
/**
 * Abstract targeting method. Parsed from LLM JSON params.
 * Exactly one per action call. Resolved to coordinates by TargetResolver.
 */
sealed interface Target {
    data class ElementIndex(val index: Int) : Target
    data class Text(val text: String, val textIndex: Int = 0) : Target
    data class Coordinate(val x: Int, val y: Int) : Target
}
```

### UIAction (platform/UIAction.kt) — Redesigned

```kotlin
/**
 * Atomic platform operations. Each variant maps to exactly one
 * Android API call in AccessibilityPlatform.
 *
 * Naming convention:
 * - *NodeAt: accessibility node operation at coordinates (ACTION_*)
 * - *At: gesture operation at coordinates (dispatchGesture)
 * - *OnFocused: operation on currently focused node
 */
sealed interface UIAction {

    // --- Node-based (AccessibilityNodeInfo.performAction) ---

    /** Find clickable node at (x,y), perform ACTION_CLICK */
    data class ClickNodeAt(val x: Int, val y: Int) : UIAction

    /** Find node at (x,y), perform ACTION_LONG_CLICK */
    data class LongClickNodeAt(val x: Int, val y: Int) : UIAction

    /** Find node at (x,y), perform ACTION_SET_TEXT */
    data class SetTextOnNodeAt(
        val x: Int, val y: Int,
        val text: String, val clear: Boolean = false
    ) : UIAction

    /** Find focused editable node, perform ACTION_SET_TEXT */
    data class SetTextOnFocused(
        val text: String, val clear: Boolean = false
    ) : UIAction

    // --- Gesture-based (AccessibilityService.dispatchGesture) ---

    /** Gesture tap at coordinates */
    data class TapAt(val x: Int, val y: Int) : UIAction

    /** Gesture long press (hold) at coordinates for duration */
    data class LongPressAt(
        val x: Int, val y: Int,
        val durationMs: Long
    ) : UIAction

    /** Gesture swipe from start to end */
    data class Swipe(
        val startX: Int, val startY: Int,
        val endX: Int, val endY: Int,
        val durationMs: Long = 300
    ) : UIAction

    // --- System ---

    data class SystemButton(val button: SystemButtonType) : UIAction
    data class Wait(val durationMs: Long) : UIAction
}
```

**Changes from current UIAction:**
| Current | New | Reason |
|---------|-----|--------|
| `ClickNodeAt(x, y)` | `ClickNodeAt(x, y)` | Stays (already atomic) |
| `TapAt(x, y)` | `TapAt(x, y)` | Stays (already atomic) |
| `LongClick(elementIndex, ms)` | DELETED | Intent-level; executor resolves element → coords |
| `LongClickAt(x, y, ms)` | `LongPressAt(x, y, ms)` | Renamed for clarity (gesture-based) |
| — | `LongClickNodeAt(x, y)` | NEW: node-based ACTION_LONG_CLICK |
| `Type(text, elementIndex?, clear)` | DELETED | Intent-level; executor handles focus+type |
| — | `SetTextOnNodeAt(x, y, text, clear)` | NEW: find node at coords, set text |
| — | `SetTextOnFocused(text, clear)` | NEW: set text on focused editable |
| `Swipe(...)` | `Swipe(...)` | Stays |
| `SystemButton(...)` | `SystemButton(...)` | Stays |
| `Wait(...)` | `Wait(...)` | Stays |

### ActionResult (platform/ActionResult.kt) — Simplified

```kotlin
sealed interface ActionResult {
    data class Success(val message: String) : ActionResult
    data class Failure(val reason: String) : ActionResult
    data class Cancelled(val reason: String = "Action cancelled") : ActionResult

    fun isSuccess(): Boolean = this is Success
}
```

**Removed:**
- `ElementNotFound` — platform doesn't know about elements; returns `Failure("No clickable node at (x,y)")`
- `exception: Throwable?` from `Failure` — log exceptions at source, don't carry through layers

### ActionOutcome (tool/action/ActionOutcome.kt)

```kotlin
/**
 * Result of executor-level action execution.
 * Richer than ActionResult: includes UI change verification,
 * observation for the LLM, and full attempt trail.
 */
sealed interface ActionOutcome {
    data class Success(
        val message: String,
        val observation: ToolObservation?,
        val attemptTrail: List<String>,
        val verified: Boolean = true  // true = UI change confirmed
    ) : ActionOutcome

    data class Failed(
        val reason: String,
        val attemptTrail: List<String>
    ) : ActionOutcome

    data class Cancelled(
        val reason: String
    ) : ActionOutcome
}
```

### UiChangeDetector (tool/action/UiChangeDetector.kt)

```kotlin
object UiChangeDetector {

    enum class ChangeResult { Changed, Unchanged, Unverifiable }

    fun compare(pre: ScreenSnapshot?, post: ScreenSnapshot?): ChangeResult {
        if (pre == null || post == null) return ChangeResult.Unverifiable
        val preHash = fingerprint(pre)
        val postHash = fingerprint(post)
        return if (preHash != postHash) ChangeResult.Changed else ChangeResult.Unchanged
    }

    /** Detects scroll boundary (pre/post content identical). */
    fun detectScrollBoundary(pre: ScreenSnapshot?, post: ScreenSnapshot?): String? {
        // ... existing logic from TargetingInvocationUtils
    }

    private fun fingerprint(snapshot: ScreenSnapshot): Long {
        // FNV-1a hash over sorted elements' stable fields
        // (resourceId, className, text, description, bounds, isFocused, isEnabled)
    }
}
```

**Fix for review-identified risk:** `Unverifiable` is a distinct result, not silently treated as `Changed`. Executors decide how to handle it (return success with `verified = false`).

### AndroidPlatform Interface (simplified)

```kotlin
interface AndroidPlatform {
    suspend fun captureScreen(): ScreenSnapshot
    suspend fun performAction(action: UIAction): ActionResult  // snapshot param removed
    fun hasRequiredPermissions(): Boolean
    fun getCurrentPackageName(): String?
    fun getDisplayInfo(): DisplayInfo
    suspend fun getInstalledApps(): List<AppInfo>
    suspend fun launchApp(packageName: String): ActionResult
}
```

**Change:** `snapshot` parameter removed from `performAction`. All atomic actions work with coordinates or focused state — they don't need a snapshot. Element resolution happens in the executor layer.

---

## Fallback Tables

### Constraint: No Cross-Target Fallback

If LLM says `element_index: 3`, we resolve element 3 to coordinates `(540, 960)` and try different APIs **at those coordinates**. We never silently fall back to text search or different coordinates. If resolution fails, we fail immediately with a clear error.

### Click

| Target | Attempt 1 | Attempt 2 | On All Fail |
|--------|-----------|-----------|-------------|
| `ElementIndex(i)` | `ClickNodeAt(center)` | `TapAt(center)` | `Failed` with trail |
| `Text(t, idx)` | `ClickNodeAt(center)` | `TapAt(center)` | `Failed` with trail |
| `Coordinate(x, y)` | `ClickNodeAt(x, y)` | `TapAt(x, y)` | `Failed` with trail |

Each attempt: execute → settle delay → check UI change.
- `Changed` → `Success(verified=true)`
- `Unchanged` → log attempt, continue to next
- `Unverifiable` → `Success(verified=false)` with warning

### Long Press

| Target | Attempt 1 | Attempt 2 | On All Fail |
|--------|-----------|-----------|-------------|
| `ElementIndex(i)` | `LongClickNodeAt(center)` | `LongPressAt(center, ms)` | `Failed` |
| `Text(t, idx)` | `LongClickNodeAt(center)` | `LongPressAt(center, ms)` | `Failed` |
| `Coordinate(x, y)` | `LongClickNodeAt(x, y)` | `LongPressAt(x, y, ms)` | `Failed` |

Same UI change verification as click.

### Type

| Target | Step 1 | Step 2 | Fallback |
|--------|--------|--------|----------|
| `ElementIndex(i)` | `SetTextOnNodeAt(center, text, clear)` | — | `TapAt(center)` → delay → `SetTextOnFocused(text, clear)` |
| `Text(t, idx)` | `SetTextOnNodeAt(center, text, clear)` | — | `TapAt(center)` → delay → `SetTextOnFocused(text, clear)` |
| `Coordinate(x, y)` | `SetTextOnNodeAt(x, y, text, clear)` | — | `TapAt(x, y)` → delay → `SetTextOnFocused(text, clear)` |
| `null` (no target) | `SetTextOnFocused(text, clear)` | — | — |

Type success: `ACTION_SET_TEXT` returns true. UI change verification is supplementary (typing always changes the a11y tree if text was set).

### Swipe

| Mode | Execution | Verification |
|------|-----------|-------------|
| `direction + distance` | Compute start/end from screen center + distance, `Swipe(start, end, ms)` | Scroll boundary detection |
| `start + end` | `Swipe(startX, startY, endX, endY, ms)` | Scroll boundary detection |

Swipe has no node-based fallback. Gesture only.

---

## Success Contract

### Two-Layer Model (Inside Executors)

Every attempt produces two signals:

1. **Dispatch result**: Did the Android API accept the command? (`ActionResult.Success` vs `Failure`)
2. **Interaction result**: Did the UI actually change? (`UiChangeDetector.compare()`)

Decision logic per attempt:

```
dispatch = platform.performAction(atomicAction)
  if dispatch is Failure → log, try next attempt
  if dispatch is Success:
    wait(SETTLE_DELAY_MS)
    post = platform.captureScreen()
    change = UiChangeDetector.compare(pre, post)
    if Changed     → return Success(verified=true)
    if Unchanged   → log "dispatched, no UI change", try next attempt
    if Unverifiable → return Success(verified=false, warning="...")
```

### Executor Output → Tool Output Mapping

| ActionOutcome | ToolExecutionResult | Agent Sees |
|---------------|---------------------|------------|
| `Success(verified=true)` | `Success` | "Clicked element 3 via ACTION_CLICK" |
| `Success(verified=false)` | `Success` | "Clicked element 3 [unverified - snapshot unavailable]" |
| `Failed` | `Failure` | "Click failed. Attempts: ACTION_CLICK: no node; gesture_tap: no UI change" |
| `Cancelled` | `Cancelled` | "Cancelled" |

### Attempt Trail Format

Human-readable, appended to output:

```
Attempts: element(3)→(540,960) ACTION_CLICK: no UI change → gesture_tap: success (UI changed)
```

This gives the agent (and developers) full visibility into what was tried and why.

---

## Execution Flows

### Click: `{"action": "click", "element_index": 3}`

```
MobileActionTool.validate()
  → has element_index only → Valid

MobileActionTool.createInvocation()
  → target = Target.ElementIndex(3)
  → MobileActionInvocation(params, description, clickExecutor, target)

MobileActionInvocation.execute()
  → clickExecutor.execute(target, snapshot, platform, isCancelled)

ClickExecutor.execute():
  1. targetResolver.resolve(ElementIndex(3), snapshot)
     → element 3 center = (540, 960) → Point(540, 960)
  2. pre = snapshot
  3. Attempt: ClickNodeAt(540, 960)
     → platform.performAction(ClickNodeAt(540, 960))
     → AccessibilityPlatform: findClickableNode → ACTION_CLICK → Success
     → delay(300ms)
     → platform.captureScreen() → post
     → UiChangeDetector.compare(pre, post) → Changed
     → return Success("Clicked (540,960) via ACTION_CLICK", verified=true)

MobileActionInvocation:
  → observation from post-snapshot
  → ToolExecutionResult.Success(output, observation)
```

**If ACTION_CLICK succeeds but no UI change:**

```
ClickExecutor.execute():
  3. ClickNodeAt(540, 960) → Success, but Unchanged
     → trail: "ACTION_CLICK: dispatched, no UI change"
  4. isCancelled()? No → continue
  5. Attempt: TapAt(540, 960)
     → platform.performAction(TapAt(540, 960))
     → AccessibilityPlatform: dispatchGesture → Success
     → delay(300ms) → captureScreen() → compare → Changed
     → return Success("Clicked (540,960) via gesture_tap", verified=true)
```

### Validation error: `{"action": "click", "element_index": 3, "x": 100, "y": 200}`

```
MobileActionTool.validate()
  → element_index present AND x/y present
  → count = 2 targeting methods
  → Invalid("click accepts only ONE targeting method. Got: element_index, x/y")
```

### Type with target: `{"action": "type", "input_text": "hello", "element_index": 5, "clear": true}`

```
TypeExecutor.execute():
  1. resolve ElementIndex(5) → (300, 600)
  2. Attempt 1: SetTextOnNodeAt(300, 600, "hello", clear=true)
     → platform finds node at (300,600), clears, sets text → Success
     → return Success("Typed into element at (300,600)")
  
  If Attempt 1 fails (no editable node at coords):
  3. Attempt 2: TapAt(300, 600) → focus field
     → delay(150ms)
     → SetTextOnFocused("hello", clear=true) → find focused editable → set text
     → return Success("Typed via tap-to-focus")
```

### Type without target: `{"action": "type", "input_text": "hello"}`

```
TypeExecutor.execute():
  1. target = null
  2. SetTextOnFocused("hello", clear=false) → Success
```

---

## Key Implementation: ClickExecutor

```kotlin
class ClickExecutor(
    private val targetResolver: TargetResolver = TargetResolver,
    private val uiChangeDetector: UiChangeDetector = UiChangeDetector
) {
    companion object {
        private const val UI_SETTLE_DELAY_MS = 300L
    }

    suspend fun execute(
        target: Target,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean
    ): ActionOutcome {
        // 1. Resolve target → coordinates
        val point = targetResolver.resolve(target, snapshot)
            ?: return ActionOutcome.Failed(
                reason = targetResolver.describeFailure(target, snapshot),
                attemptTrail = emptyList()
            )

        val attemptTrail = mutableListOf<String>()
        val attempts = listOf(
            "ACTION_CLICK" to UIAction.ClickNodeAt(point.x, point.y),
            "gesture_tap" to UIAction.TapAt(point.x, point.y)
        )

        // 2. Execute with fallback
        for ((label, action) in attempts) {
            if (isCancelled()) return ActionOutcome.Cancelled("Cancelled between attempts")

            val result = platform.performAction(action)

            if (result is ActionResult.Failure) {
                attemptTrail.add("$label: ${result.reason}")
                continue
            }

            // 3. Verify UI change
            delay(UI_SETTLE_DELAY_MS)
            val post = runCatching { platform.captureScreen() }.getOrNull()
            val observation = post?.let { buildObservation(it, platform) }
            val change = uiChangeDetector.compare(snapshot, post)

            when (change) {
                UiChangeDetector.ChangeResult.Changed -> {
                    attemptTrail.add("$label: success (UI changed)")
                    return ActionOutcome.Success(
                        message = "Clicked (${point.x},${point.y}) via $label",
                        observation = observation,
                        attemptTrail = attemptTrail,
                        verified = true
                    )
                }
                UiChangeDetector.ChangeResult.Unverifiable -> {
                    attemptTrail.add("$label: dispatched (unverifiable)")
                    return ActionOutcome.Success(
                        message = "Clicked (${point.x},${point.y}) via $label [unverified]",
                        observation = observation,
                        attemptTrail = attemptTrail,
                        verified = false
                    )
                }
                UiChangeDetector.ChangeResult.Unchanged -> {
                    attemptTrail.add("$label: dispatched, no UI change")
                    // continue to next attempt
                }
            }
        }

        // 4. All attempts exhausted
        return ActionOutcome.Failed(
            reason = "Click at (${point.x},${point.y}) failed after all attempts",
            attemptTrail = attemptTrail
        )
    }
}
```

~60 lines of linear, readable code. Compare with current `ClickTargetInvocation` (228 lines with nested when-expressions, dedup sets, MultiSelectorTargeting calls).

---

## Key Implementation: MobileActionTool (Rewritten)

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
        if (action.isEmpty()) return Invalid("Missing required parameter: action")

        return when (action) {
            "click", "long_press" -> validateSingleTarget(params, action, required = true)
            "type" -> validateTypeAction(params)
            "swipe" -> validateSwipeAction(params)
            else -> Invalid("Unknown action: '$action'")
        }
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val action = params.getString("action")
        val target = parseOptionalTarget(params)
        val description = buildDescription(action, target, params)

        return MobileActionInvocation(params, description) { platform, snapshot, isCancelled ->
            when (action) {
                "click" -> ClickExecutor().execute(target!!, snapshot, platform, isCancelled)
                "long_press" -> LongPressExecutor().execute(
                    target!!, params.optLong("duration_ms", 1000), snapshot, platform, isCancelled
                )
                "type" -> TypeExecutor().execute(
                    target, params.getString("input_text"),
                    params.optBoolean("clear", false), snapshot, platform, isCancelled
                )
                "swipe" -> SwipeExecutor().execute(params, snapshot, platform, isCancelled)
                else -> error("Unreachable: validated above")
            }
        }
    }

    // --- Validation: one-of target enforcement ---

    private fun validateSingleTarget(
        params: JSONObject, action: String, required: Boolean
    ): ValidationResult {
        val hasElement = params.has("element_index")
        val hasText = params.optString("text", "").trim().isNotEmpty()
        val hasCoords = params.has("x") || params.has("y")
        val count = listOf(hasElement, hasText, hasCoords).count { it }

        if (count == 0 && required)
            return Invalid("$action requires one of: element_index, text, or x/y")
        if (count > 1)
            return Invalid(
                "$action accepts only ONE targeting method. " +
                "Got: ${targetNames(hasElement, hasText, hasCoords)}"
            )
        // Validate specific method...
        return Valid
    }

    // --- Target parsing ---

    private fun parseOptionalTarget(params: JSONObject): Target? = when {
        params.has("element_index") ->
            Target.ElementIndex(params.getInt("element_index"))
        params.optString("text", "").trim().isNotEmpty() ->
            Target.Text(params.getString("text"), params.optInt("text_index", 0))
        params.has("x") && params.has("y") ->
            Target.Coordinate(params.getInt("x"), params.getInt("y"))
        else -> null
    }
}
```

**What disappeared:**
- `MultiActionTool` base class → implement `ToolSpec` directly
- `ActionHandler` interface → validation is inline
- `ClickActionHandler` / `LongPressActionHandler` / `TypeActionHandler` / `SwipeActionHandler` → all inline
- `MultiSelectorTargeting` → single target, parsed in 10 lines
- `tool/impl/mobileaction/` directory → gone

---

## Key Implementation: MobileActionInvocation (Thin Glue)

```kotlin
class MobileActionInvocation(
    override val params: JSONObject,
    private val description: String,
    private val executeAction: suspend (AndroidPlatform, ScreenSnapshot?, () -> Boolean) -> ActionOutcome
) : ToolInvocation {
    override val toolName = "mobile_action"

    override fun getDescription(): String {
        val thought = params.optString("agent_thought", "").trim()
        return if (thought.isNotEmpty()) "$description ($thought)" else description
    }

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) return ToolExecutionResult.Cancelled()
        val outcome = executeAction(context.platform, context.currentSnapshot, context::isCancelled)
        return mapOutcome(outcome)
    }

    private fun mapOutcome(outcome: ActionOutcome): ToolExecutionResult = when (outcome) {
        is ActionOutcome.Success -> {
            val output = buildString {
                append(outcome.message)
                if (!outcome.verified) append(" [unverified]")
                if (outcome.attemptTrail.size > 1) {
                    append("\nAttempts: ${outcome.attemptTrail.joinToString(" → ")}")
                }
            }
            ToolExecutionResult.Success(output = output, observation = outcome.observation)
        }
        is ActionOutcome.Failed -> {
            val output = buildString {
                append(outcome.reason)
                if (outcome.attemptTrail.isNotEmpty()) {
                    append("\nAttempts: ${outcome.attemptTrail.joinToString("; ")}")
                }
            }
            ToolExecutionResult.Failure(output)
        }
        is ActionOutcome.Cancelled -> ToolExecutionResult.Cancelled(outcome.reason)
    }
}
```

~40 lines. Replaces `UIActionInvocation` + `ClickTargetInvocation` + `LongPressTargetInvocation` + `TypeTargetInvocation` for mobile_action.

Note: `UIActionInvocation.kt` is NOT deleted — `SystemButtonTool` and `WaitTool` still use it.

---

## AccessibilityPlatform Changes

The `performAction` dispatcher becomes a flat mapping to atomic implementations:

```kotlin
override suspend fun performAction(action: UIAction): ActionResult = when (action) {
    is UIAction.ClickNodeAt     -> performNodeClickAt(action.x, action.y)
    is UIAction.TapAt           -> performTap(action.x.toFloat(), action.y.toFloat())
    is UIAction.LongClickNodeAt -> performNodeLongClickAt(action.x, action.y)
    is UIAction.LongPressAt     -> performLongPressGesture(action.x.toFloat(), action.y.toFloat(), action.durationMs)
    is UIAction.SetTextOnNodeAt -> performSetTextOnNodeAt(action.x, action.y, action.text, action.clear)
    is UIAction.SetTextOnFocused -> performSetTextOnFocused(action.text, action.clear)
    is UIAction.Swipe           -> performSwipe(action)
    is UIAction.SystemButton    -> performSystemButton(action)
    is UIAction.Wait            -> performWait(action)
}
```

**New atomic implementations** (added to AccessibilityPlatform):

```kotlin
// ACTION_LONG_CLICK on node at coordinates (new)
private suspend fun performNodeLongClickAt(x: Int, y: Int): ActionResult

// ACTION_SET_TEXT on node found at coordinates (extracted from current performType)
private suspend fun performSetTextOnNodeAt(x: Int, y: Int, text: String, clear: Boolean): ActionResult

// ACTION_SET_TEXT on focused editable node (extracted from current performType)
private suspend fun performSetTextOnFocused(text: String, clear: Boolean): ActionResult
```

**Removed from AccessibilityPlatform:**
- `performLongClick(UIAction.LongClick, snapshot)` — was intent-level, executor handles now
- `performType(UIAction.Type, snapshot)` — was intent-level, executor handles now

**What stays** (~400 lines total):
- `captureScreen()` + screenshot logic
- `performNodeClickAt(x, y)` — unchanged
- `performTap(x, y)` — unchanged
- `performNodeLongClickAt(x, y)` — new, simple
- `performLongPressGesture(x, y, ms)` — extracted from current LongClickAt
- `performSetTextOnNodeAt(x, y, text, clear)` — new, extracted
- `performSetTextOnFocused(text, clear)` — new, extracted
- `performSwipe(...)` — unchanged
- `performSystemButton(...)` — unchanged
- `performEnterKey()` — unchanged
- `performWait(...)` — unchanged
- Gesture helpers (`dispatchGesture`, etc.) — unchanged
- App management (`getInstalledApps`, `launchApp`) — unchanged

---

## Phase 2: NodeLocator (Future Enhancement)

### Problem

Currently, `element_index` immediately degrades to coordinates:
```
element 3 → snapshot lookup → center (540, 960) → ClickNodeAt(540, 960)
```

The `ClickNodeAt` implementation then searches the a11y tree for a clickable node at those coordinates. This is a re-discovery step that can find the wrong node if the layout shifted slightly between perception and action.

### Solution

Attach a `NodeLocator` to each `PerceptionElement` during screen capture:

```kotlin
data class NodeLocator(
    val windowId: Int?,
    val pathFromRoot: List<Int>,   // child indices from root to target
    val fingerprint: NodeFingerprint
)

data class NodeFingerprint(
    val className: String?,
    val resourceId: String?,
    val text: String?,
    val description: String?,
    val boundsHash: Int
)
```

At execution time, the executor's `TargetResolver` can:
1. Re-walk the fresh a11y tree via `pathFromRoot`
2. Verify identity via `fingerprint` match
3. If match: use the node directly (true node-level action, no coordinate intermediary)
4. If mismatch: fall back to coordinate-based resolution

This adds a new attempt to the click fallback chain:

| Target | Attempt 1 (Phase 2) | Attempt 2 | Attempt 3 |
|--------|---------------------|-----------|-----------|
| `ElementIndex(i)` | `ClickNodeByLocator(locator)` | `ClickNodeAt(center)` | `TapAt(center)` |

### Why Phase 2

- Phase 1 works correctly with coordinate-based resolution (same as current behavior)
- NodeLocator requires changes to perception layer (`Perceptor.snapshot()` must build locators)
- New atomic action type `ClickNodeByLocator` needs careful implementation
- Can be added without changing the executor pattern (just insert an attempt at position 0)

---

## File Inventory

### Files to DELETE (13 files)

```
tool/handlers/
  ActionHandler.kt                  # Interface replaced by inline validation
  ClickTargetInvocation.kt          # Replaced by ClickExecutor
  LongPressTargetInvocation.kt      # Replaced by LongPressExecutor
  TypeTargetInvocation.kt           # Replaced by TypeExecutor
  SwipeTargetInvocation.kt          # Replaced by SwipeExecutor
  MultiSelectorTargeting.kt         # Multi-selector concept eliminated
  TargetingInvocationUtils.kt       # Split into UiChangeDetector + executor logic

tool/impl/mobileaction/             # Entire directory
  ClickActionHandler.kt             # Validation moved to MobileActionTool
  LongPressActionHandler.kt
  TypeActionHandler.kt
  SwipeActionHandler.kt

tool/
  MultiActionTool.kt                # No remaining subclasses
```

### Files to CREATE (8 files)

| File | ~Lines | Responsibility |
|------|--------|----------------|
| `tool/impl/MobileActionInvocation.kt` | ~40 | Thin glue: ActionOutcome → ToolExecutionResult |
| `tool/action/Target.kt` | ~15 | Target sealed interface |
| `tool/action/ActionOutcome.kt` | ~20 | Executor return type |
| `tool/action/ClickExecutor.kt` | ~80 | Click fallback chain |
| `tool/action/LongPressExecutor.kt` | ~80 | Long press fallback chain |
| `tool/action/TypeExecutor.kt` | ~100 | Focus-then-type flow |
| `tool/action/SwipeExecutor.kt` | ~80 | Direction/distance computation |
| `tool/action/TargetResolver.kt` | ~60 | Target → Point resolution |
| `tool/action/UiChangeDetector.kt` | ~50 | Snapshot fingerprinting |

### Files to MODIFY (5 files)

| File | Change |
|------|--------|
| `platform/UIAction.kt` | Redesign: remove intent-level variants, add atomic variants |
| `platform/ActionResult.kt` | Remove `ElementNotFound`, remove `exception` from Failure |
| `platform/AccessibilityPlatform.kt` | Simplify: remove intent-level handlers, add atomic implementations |
| `platform/AndroidPlatform.kt` | Remove `snapshot` param from `performAction` |
| `tool/impl/MobileActionTool.kt` | Rewrite: implement ToolSpec directly, inline validation |

### Files to UPDATE (minor)

| File | Change |
|------|--------|
| `tool/handlers/UIActionInvocation.kt` | Remove snapshot from `performAction` call |

### Files UNCHANGED

```
platform/AccessibilityNodeFinder.kt   # Used by atomic platform implementations
platform/DisplayInfo.kt, AppInfo.kt   # Data classes
tool/ToolSpec.kt                       # Core framework interface
tool/handlers/UIActionInvocation.kt    # Used by SystemButtonTool, WaitTool (minor update)
tool/handlers/DataQueryInvocation.kt   # Non-mobile-action tools
tool/impl/SystemButtonTool.kt         # Uses UIActionInvocation (no change)
tool/impl/WaitTool.kt                 # Uses UIActionInvocation (no change)
```

### Post-Redesign File Count

```
tool/impl/              2 files   (MobileActionTool, MobileActionInvocation)
tool/action/            8 files   (executors + shared utilities)
tool/handlers/          2 files   (UIActionInvocation, DataQueryInvocation)
platform/               5 files   (same count, simpler internals)
                       --------
Total touch points:    17 files   (down from 19, dramatically simpler)
```

More importantly: to understand "how does click work?", read 1 file (`ClickExecutor.kt`, ~80 lines). Currently: read 4+ files across 2 directories.

---

## Migration Plan

### Phase 1: Atomic Platform (no tool-layer changes yet)

1. Create `tool/action/` directory
2. Create `Target.kt`, `ActionOutcome.kt`
3. Create `UiChangeDetector.kt` (extract from `TargetingInvocationUtils`)
4. Create `TargetResolver.kt`
5. Update `UIAction.kt` — add new atomic variants (`LongClickNodeAt`, `SetTextOnNodeAt`, `SetTextOnFocused`), keep old variants temporarily
6. Update `ActionResult.kt` — remove `ElementNotFound`
7. Add `performNodeLongClickAt`, `performSetTextOnNodeAt`, `performSetTextOnFocused` to `AccessibilityPlatform`
8. **Verify**: `./gradlew clean assembleDebug lint test`

### Phase 2: Executors

9. Create `ClickExecutor.kt`
10. Create `LongPressExecutor.kt`
11. Create `TypeExecutor.kt`
12. Create `SwipeExecutor.kt`
13. Create `MobileActionInvocation.kt`
14. **Verify**: `./gradlew clean assembleDebug lint test` (executors compile, not wired yet)

### Phase 3: Wire Up

15. Rewrite `MobileActionTool.kt` — implement ToolSpec directly, use executors
16. Remove `snapshot` param from `AndroidPlatform.performAction`
17. Update `UIActionInvocation.kt` to match
18. Remove old UIAction variants (`LongClick`, `LongClickAt`, `Type`)
19. Update `AccessibilityPlatform.performAction` — remove old variant handlers
20. **Verify**: `./gradlew clean assembleDebug lint test`

### Phase 4: Cleanup

21. Delete `tool/handlers/ClickTargetInvocation.kt`
22. Delete `tool/handlers/LongPressTargetInvocation.kt`
23. Delete `tool/handlers/TypeTargetInvocation.kt`
24. Delete `tool/handlers/SwipeTargetInvocation.kt`
25. Delete `tool/handlers/MultiSelectorTargeting.kt`
26. Delete `tool/handlers/TargetingInvocationUtils.kt`
27. Delete `tool/handlers/ActionHandler.kt`
28. Delete `tool/impl/mobileaction/` directory (all 4 files)
29. Delete `tool/MultiActionTool.kt`
30. Remove `exception` field from `ActionResult.Failure`
31. **Verify**: `./gradlew clean assembleDebug lint test`

### Phase 5: Tests

32. Delete `MultiSelectorTargetingTest.kt`
33. Rewrite `TargetInvocationsTest.kt` as executor tests
34. Add `MobileActionTool` validation tests (one-of enforcement)
35. Add `UiChangeDetector` unit tests
36. Add `TargetResolver` unit tests
37. **Verify**: `./gradlew clean assembleDebug lint test`

---

## Testing Strategy

### Per-Component Test Targets

| Component | Mock | Key Test Cases |
|-----------|------|----------------|
| `MobileActionTool` validation | None (pure) | One-of enforcement, missing target, invalid values |
| `MobileActionTool.createInvocation` | None | Correct Target construction from JSON |
| `MobileActionInvocation` | AndroidPlatform | ActionOutcome → ToolExecutionResult mapping |
| `ClickExecutor` | AndroidPlatform | Fallback chain, UI change gating, cancellation |
| `LongPressExecutor` | AndroidPlatform | Same pattern, duration forwarding |
| `TypeExecutor` | AndroidPlatform | Focus-then-type, no-target path, clear behavior |
| `SwipeExecutor` | DisplayInfo | Direction/distance computation, boundary detection |
| `TargetResolver` | None (pure) | ElementIndex resolution, Text matching, failure messages |
| `UiChangeDetector` | None (pure) | Fingerprint stability, Changed/Unchanged/Unverifiable |

### Critical Test Cases

1. **One-of enforcement**: `click` with `element_index` + `x/y` → validation error
2. **Target resolution**: `ElementIndex(3)` → correct center from snapshot
3. **Fallback chain**: ACTION_CLICK fails → gesture tap succeeds → returns Success
4. **UI change gating**: ACTION_CLICK succeeds but no UI change → continues to gesture tap
5. **All attempts fail**: Returns Failed with complete attempt trail
6. **No snapshot + element target**: TargetResolver returns null → executor returns Failed
7. **Type without target**: Types into focused field without tap
8. **Cancellation between attempts**: Returns Cancelled
9. **Unverifiable success**: Pre/post snapshot null → returns Success(verified=false)
10. **Swipe boundary**: Pre/post identical → warning in output

---

## Risks & Mitigations

### Double-Trigger Risk (High → Medium)

**Risk**: ACTION_CLICK succeeds on a toggle (e.g., like button), no visible UI change (a11y tree unchanged), gesture tap fires → toggle flips back.

**Mitigation**:
- The settle delay (300ms) gives time for subtle a11y tree changes to propagate
- Fingerprint includes `isFocused`, `isEnabled`, `text` — most toggle state changes are captured
- Attempt trail makes double-triggers diagnosable
- Phase 2: add heuristic for "node state changed" (e.g., `isChecked` toggled) as a success signal even without full tree change

**Acceptance**: This risk exists in the current design too. The new design does not make it worse, and the attempt trail makes it visible.

### Snapshot Capture Cost (Medium)

**Risk**: UI change detection captures a full `captureScreen()` after each attempt. For a 2-attempt click, that's 1-2 extra captures in the worst case.

**Mitigation**:
- Second attempt only happens when first fails or doesn't change UI (uncommon path)
- `captureScreen()` is already called for observation after every action
- The post-action snapshot is reused for both UI change detection AND observation (no duplicate capture)

### Migration Breakage (Medium → Low)

**Risk**: Large-scale delete/rewrite could break compilation or behavior.

**Mitigation**:
- 5-phase plan with `./gradlew clean assembleDebug lint test` after each phase
- Old and new variants coexist temporarily (Phase 1-2) before cutover (Phase 3)
- Phase 3 is the only "big bang" step; if it fails, revert and debug

---

## Non-Goals (Explicit Scope Boundaries)

1. **No nested JSON target**: Keep flat params. LLM knows the current format.
2. **No task-level semantic verification**: "Form submitted" is agent-level logic, not tool-level.
3. **No multi-action per call**: One action per `mobile_action` invocation.
4. **No old schema compatibility**: Clean break. Update agent prompt simultaneously.
5. **No NodeLocator in Phase 1**: Coordinates work. NodeLocator is Phase 2.
6. **No configurable settle delay per action**: Use constant 300ms. Tune later if needed.

---

## Summary: Before vs After

| Metric | Before | After |
|--------|--------|-------|
| Classes per click path | 8 | 4 (Tool → Invocation → Executor → Platform) |
| Total files in mobile_action path | 19 | 17 |
| Lines to understand "how click works" | ~500 across 4 files | ~80 in ClickExecutor |
| Multi-selector complexity | O(selectors × APIs) | O(APIs) per single target |
| Validation duplication | 3× (Click/LongPress/Type handlers) | 1× (inline in MobileActionTool) |
| Platform responsibility | Mixed (atomic + intent-level + fallback) | Pure atomic |
| Fallback visibility | Hidden in nested when-expressions | Explicit attempt trail in output |
| UI change false positives | Unverifiable treated as success | Unverifiable is distinct outcome |
| Testability | Mock 6 layers | Mock 1 interface (AndroidPlatform) |
