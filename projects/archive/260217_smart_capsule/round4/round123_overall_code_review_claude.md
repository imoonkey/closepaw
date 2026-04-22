# Smart Capsule — Round 1/2/3 Overall Code Review

> Reviewer: Claude (ultra-think mode)
> Scope: `git diff 339448dd..HEAD -- app/`
> Date: 2026-02-13
> Principle: KISS + Occam's Razor — simplify ruthlessly, question every abstraction

---

## Executive Summary

Three rounds of iteration produced a **functional** Smart Capsule with solid primitives (`CapsuleMode`, `CapsuleStateHolder`, `UserResponseChannel`, `AskUserTool`). The core protocol design (sealed CapsuleMode, one state drives all renderers) is elegant and correct.

However, the layering got muddied along the way. The "one state, many renderers" principle was _stated_ but not fully _implemented_. ServiceOverlayController became a god object that manually pushes state to renderers instead of letting them observe. GlowState drifted into a parallel state machine. Two full rendering implementations (View + Compose) duplicate logic. Numerous boolean flags in ServiceOverlayController shadow what CapsuleStateHolder already knows.

**The core insight**: CapsuleStateHolder is the right abstraction. The problem is that everything _around_ it doesn't trust it enough. Fix the trust relationship, and most complexity melts away.

---

## Table of Contents

1. [Critical: Architecture Issues](#1-critical-architecture-issues)
2. [High: State Management Problems](#2-high-state-management-problems)
3. [High: Code Duplication](#3-high-code-duplication)
4. [Medium: Design Inconsistencies](#4-medium-design-inconsistencies)
5. [Low: Minor Issues & Cleanup](#5-low-minor-issues--cleanup)
6. [Bugs Found (Non-Edge-Case)](#6-bugs-found-non-edge-case)
7. [Refactoring Recommendations](#7-refactoring-recommendations)
8. [What's Working Well](#8-whats-working-well)

---

## 1. Critical: Architecture Issues

### 1.1 ServiceOverlayController Is a God Object

**File**: `app/ServiceOverlayController.kt` (511 lines)

ServiceOverlayController does at least **six** jobs:
1. Owns CapsuleStateHolder (state management)
2. Manages SmartCapsuleManager (overlay rendering)
3. Manages EdgeGlowManager (glow overlay)
4. Manages StatusIslandManager (island overlay)
5. Tracks foreground/background app transitions
6. Routes every event between state and renderers with `when (platformMode)` branching

Almost every method follows this pattern:

```kotlin
fun onSomething() {
    // Update some local boolean
    hasActiveTask = true
    currentGlowState = GlowState.Active

    // Update state holder
    stateHolder.onSomething()

    // Branch on platform mode
    when (platformMode) {
        PlatformMode.VIRTUAL_DISPLAY -> {
            statusIslandManager?.doSomething()
        }
        PlatformMode.ACCESSIBILITY -> {
            edgeGlowManager.doSomething()
            pushModeToOverlayCapsule()
        }
    }
}
```

This is the **Strategy pattern** screaming to be extracted. Every `when (platformMode)` block is identical structure with different implementations.

**Why it matters**: Every new event handler requires updating 3-4 things in the right order, in both platform branches. This is the primary source of "forgot to wire X" bugs found in every code review across all three rounds.

**Recommendation**: See [Section 7.1](#71-extract-overlay-strategy).

---

### 1.2 Push Model vs Pull Model Mismatch

CapsuleStateHolder exposes `StateFlow<CapsuleMode>` — a reactive, pull-based API. SmartCapsuleCompose correctly collects from it:

```kotlin
// ChatScreen.kt — correct: observe the state
val capsuleMode by (stateHolder?.mode ?: fallbackMode).collectAsStateWithLifecycle()
```

But SmartCapsuleManager uses the opposite pattern — it gets **pushed** state by ServiceOverlayController calling `pushModeToOverlayCapsule()` after every state mutation:

```kotlin
// ServiceOverlayController.kt — fragile: manual push after every change
private fun pushModeToOverlayCapsule() {
    capsuleManager.renderMode(stateHolder.mode.value, stateHolder.previousMode)
}
```

**Consequences**:
- If any code path modifies `stateHolder` but forgets to call `pushModeToOverlayCapsule()`, the overlay is stale.
- Every round's code review found missing push calls. This is a **systemic** problem, not individual oversights.
- The push model requires ServiceOverlayController to be in the loop for every state change, preventing direct state holder → renderer communication.

**Recommendation**: Have SmartCapsuleManager observe CapsuleStateHolder's StateFlow directly using a `Handler` on main thread or `CoroutineScope` from the service. See [Section 7.2](#72-make-smartcapsulemanager-observe-stateflow-directly).

---

### 1.3 GlowState Is a Parallel State Machine

EdgeGlowManager has its own state enum (`GlowState`: Active, Executing, Success, Error, Paused) that must stay in sync with CapsuleMode. ServiceOverlayController maintains `currentGlowState` and manually coordinates:

```kotlin
// Five separate places that set currentGlowState:
fun onTaskStarted()    { currentGlowState = GlowState.Active }
fun onTurnPhaseChanged() { currentGlowState = when(phase) { ... } }
fun onActionExecuted() { currentGlowState = if (success) GlowState.Active else GlowState.Error }
fun onTaskCompleted()  { currentGlowState = GlowState.Success }
fun onSessionTakeover() { currentGlowState = GlowState.Paused }
```

**GlowState is fully derivable from CapsuleMode + TurnPhase**:

```kotlin
fun deriveGlowState(mode: CapsuleMode, turnPhase: TurnPhase?): GlowState = when {
    mode is CapsuleMode.Error -> GlowState.Error
    mode is CapsuleMode.Done -> GlowState.Success
    mode is CapsuleMode.TakeoverPending || mode is CapsuleMode.Takeover -> GlowState.Paused
    mode is CapsuleMode.Running && turnPhase == TurnPhase.EXECUTION -> GlowState.Executing
    mode is CapsuleMode.Running -> GlowState.Active
    else -> GlowState.Active
}
```

**Recommendation**: Delete `currentGlowState` from ServiceOverlayController. Make EdgeGlowManager derive its state from CapsuleStateHolder, or add a `turnPhase` field to CapsuleStateHolder and derive GlowState at render time.

---

### 1.4 StatusIslandManager Doesn't Follow "One State, Many Renderers"

Round 3's design doc explicitly says:
> "One state, many renderers: Single CapsuleMode for overlay, Compose, island."

But StatusIslandManager has its own API (`updateStatus(string, color)`, `showSuccess(message)`, `showError(message)`, `updatePauseState(paused)`) that is called with raw strings and colors, NOT driven by CapsuleMode:

```kotlin
// ServiceOverlayController calls island with raw strings:
statusIslandManager?.updateStatus(input.take(24), glowStateColor(GlowState.Active))
statusIslandManager?.updateStatus("❓ ${message.take(20)}", glowStateColor(GlowState.Paused))
statusIslandManager?.showSuccess("✓ Done!")
```

This means StatusIslandManager's display can drift from the actual CapsuleMode.

**Recommendation**: StatusIslandManager should accept `CapsuleMode` directly and derive its display text and dot color internally. This completes the "one state, many renderers" vision.

---

## 2. High: State Management Problems

### 2.1 State Duplication in ServiceOverlayController

CapsuleStateHolder is supposed to be the single source of truth. But ServiceOverlayController maintains **five** shadow states:

| Shadow State | Redundant With |
|---|---|
| `hasActiveTask: Boolean` | `stateHolder.mode.value !is CapsuleMode.Hidden` |
| `currentTaskInput: String?` | `(stateHolder.mode.value as? CapsuleMode.Running)?.thought` |
| `currentGlowState: GlowState` | Derivable from `CapsuleMode + TurnPhase` |
| `isAppInForeground: Boolean` | Could be derived from `CapsuleContext` |
| `lastKnownForegroundPackage: String?` | Only used to derive `isAppInForeground` |

`hasActiveTask` is particularly concerning because it's set to `false` in `onTaskCompleted` AND `onSessionCompleted`, but `stateHolder.mode` is only set to Done/Hidden in `onTaskCompleted`. If these get out of sync, the window state tracking (`handleWindowStateChangedA11y`) makes wrong decisions.

**Recommendation**: Eliminate shadow states. Let `CapsuleStateHolder` be the single source:
- Replace `hasActiveTask` with `stateHolder.mode.value !is CapsuleMode.Hidden`
- Remove `currentTaskInput` (only used in one fallback)
- Derive `currentGlowState` (see 1.3)
- Keep `isAppInForeground` / `lastKnownForegroundPackage` as they're Android system concerns, but don't use them to shadow capsule state

---

### 2.2 CapsuleStateHolder Has No Transition Guards

Any event handler can be called in any order without validation:

```kotlin
fun onTakeoverRequested() {
    val lastThought = (_mode.value as? CapsuleMode.Running)?.thought ?: ""
    // What if mode is Done? Error? Hidden? WaitingForInput?
    // Falls through with empty thought — silently wrong
    setMode(CapsuleMode.TakeoverPending(lastThought))
}
```

More examples:
- `onResumed()` when already in `Running` → redundant state change, re-renders capsule
- `onThoughtUpdate()` when in `Takeover` → overwrites the Takeover mode with Running
- `onUserResponseSent()` when no pending ask → transitions to Running("处理答复中...")

**Recommendation**: Add transition guards:

```kotlin
fun onTakeoverRequested() {
    val current = _mode.value
    if (current !is CapsuleMode.Running) {
        Log.w(TAG, "Ignoring takeover request in ${current::class.simpleName}")
        return
    }
    setMode(CapsuleMode.TakeoverPending(current.thought))
}
```

This makes the state machine strict and predictable. Invalid transitions log a warning instead of silently corrupting state.

---

### 2.3 previousMode Is Not Thread-Safe

`CapsuleStateHolder.previousMode` is a plain `var` updated inside `setMode()`:

```kotlin
private fun setMode(new: CapsuleMode) {
    previousMode = _mode.value  // Non-atomic read + write
    _mode.value = new
}
```

If two events fire rapidly from different coroutine dispatchers, `previousMode` could be stale. For View-based rendering on the main thread this is mostly fine (coroutine dispatch to Main serializes), but for Compose collection on arbitrary dispatchers, this is a potential race.

**Recommendation**: Either make `previousMode` a `StateFlow` too, or document that `setMode` must only be called from Main dispatcher.

---

## 3. High: Code Duplication

### 3.1 Two Complete Rendering Implementations

`SmartCapsuleRenderer.kt` (384 lines, View-based) and `SmartCapsuleCompose.kt` (406 lines, Compose-based) implement **the same rendering logic** in different UI frameworks:

| Logic | Renderer (View) | Compose |
|---|---|---|
| Dot color per mode | `renderRunning` → `COLOR_BLUE` | `CapsuleRow1` → `Color(0xFF2563EB)` |
| Dot visibility | `v.statusDot.visibility = View.GONE` for WaitingFor* | `val showDot = mode !is CapsuleMode.WaitingForInput...` |
| Row1 text per mode | `v.thoughtText.text = mode.thought.ifEmpty { "思考中..." }` | `text = when (mode) { is CapsuleMode.Running -> mode.thought.ifEmpty { "思考中..." }` |
| Row2 button labels | `v.primaryIcon.text = "✋"; v.primaryText.text = "接管"` | `CapsuleTextButton(text = "✋ 接管", ...)` |
| Nav button visibility | `configureNavButtons(...)` | inline `if (platformMode == ...)` |
| Row3 placeholder text | `configureRow3Supplement/configureRow3Answer` | `val (placeholder, buttonText) = when (mode) { ... }` |
| shouldShowRow3 | implicit in render methods | `shouldShowRow3(mode)` |

Every time you change a button label, add a mode, or adjust visibility logic, you must update **both** files.

**Recommendation**: Extract a shared `CapsuleRenderSpec`:

```kotlin
data class CapsuleRenderSpec(
    val dotColor: Int?, // null = hidden
    val dotPulsing: Boolean,
    val thoughtText: String,
    val thoughtAlpha: Float,
    val expandedBody: String?,
    val primaryButton: ButtonSpec?, // null = hidden
    val stopButton: ButtonSpec?,
    val showRow3: Boolean,
    val row3Hint: String,
    val row3ButtonText: String,
) {
    companion object {
        fun from(mode: CapsuleMode): CapsuleRenderSpec = when (mode) {
            is CapsuleMode.Running -> CapsuleRenderSpec(
                dotColor = 0xFF2563EB.toInt(),
                dotPulsing = true,
                thoughtText = mode.thought.ifEmpty { "思考中..." },
                // ...
            )
            // ...
        }
    }
}
```

Both SmartCapsuleRenderer and SmartCapsuleCompose read from the spec. Single source of rendering truth.

---

### 3.2 Color Constants Duplicated in 4 Files

The same hex color values appear in:
- `SmartCapsuleRenderer.kt` → `COLOR_BLUE = 0xFF2563EB`
- `SmartCapsuleLayoutBuilder.kt` → `colorBlue = 0xFF2563EB`
- `SmartCapsuleCompose.kt` → `Color(0xFF2563EB)`
- `StatusIslandManager.kt` → `colorPrimary = 0xFF2563EB`
- `ServiceOverlayController.kt` → `glowStateColor()` → `0xFF2563EB`

If the design ever changes the blue to a different shade, you have to update 5 files.

**Recommendation**: Create a `CapsuleColors` object:

```kotlin
object CapsuleColors {
    const val BLUE = 0xFF2563EB.toInt()    // Running
    const val AMBER = 0xFFF59E0B.toInt()   // Takeover
    const val TEAL = 0xFF0D9488.toInt()    // Done
    const val RED = 0xFFEF4444.toInt()     // Error
    const val PURPLE = 0xFF7C3AED.toInt()  // Executing (glow)
}
```

---

### 3.3 Nav Button Visibility Logic Duplicated

Navigation button visibility (`[1] ⊖`, `[2] 📱`, `[3] 👁`) is computed in:
1. `SmartCapsuleRenderer.configureNavButtons()` — View-based
2. `SmartCapsuleCompose.CapsuleRow2()` — Compose-based

Same logic, two implementations. Would be resolved by the `CapsuleRenderSpec` approach (3.1).

---

## 4. Medium: Design Inconsistencies

### 4.1 SmartCapsuleManager Is Not Actually a Pure Renderer

The class docstring says:
> "SmartCapsuleManager — pure View renderer for the Smart Capsule overlay. Does NOT compute state."

But it contains significant business logic:

1. **`handleRow3Submit()`** — decides whether input is a UserResponse, Send, or Supplement based on current mode. This is business logic, not rendering.
2. **`handlePrimaryClick()`** — maps mode to action (Running→takeover, Takeover→resume, WaitingForAction→response). Business logic.
3. **`handleStopClick()`** — Error→dismissError, else→stop. Business logic.
4. **`flashSupplementConfirmation()`** — manages confirmation text + timer. Business logic.
5. **`startNudgeTimer()`** — 4-minute timer that appends text to body. Business logic.
6. **Keyboard management** — `setOverlayFocusable()`, `focusInputAndShowKeyboard()`. Platform concern, not rendering.

**The actual pure renderer is SmartCapsuleRenderer.** SmartCapsuleManager is better described as a "View controller" or "overlay coordinator."

**Recommendation**: Rename to `SmartCapsuleOverlayController` to clarify its role. Or, move business logic (handleRow3Submit, handlePrimaryClick, handleStopClick) into the callback lambdas set by ServiceOverlayController, keeping the manager truly passive.

---

### 4.2 CapsuleMode.Hidden Serves Double Duty

`CapsuleMode.Hidden` means both:
1. **No active task** (idle state, show only Row 3 in main app)
2. **Capsule should not be shown** (overlay should be removed)

In the main app (`SmartCapsuleCompose`), Hidden means "show Row 3 only (acts as InputDock)." In the overlay (`SmartCapsuleManager`), Hidden means "remove from screen." This semantic ambiguity caused the Round 3 code review to flag that `onSend` isn't wired for the overlay because it "hides on Hidden mode."

**Recommendation**: Consider splitting:
- `CapsuleMode.Idle` — no task, show input-only row in main app
- `CapsuleMode.Hidden` — completely hidden, no overlay

Or keep the current approach but document the context-dependent semantics explicitly in the CapsuleMode KDoc.

---

### 4.3 onSend Callback Is Not Wired for Overlay

SmartCapsuleManager has `var onSend: ((String) -> Unit)? = null` with a comment:

```kotlin
// Not wired in Stage 7 — overlay hides on Hidden mode. Wired in Stage 8+ for
// Compose main-app capsule or future Row-3-only idle overlay
```

If the overlay is shown via island tap when no task is active, Row 3 appears but typing and pressing "发送" does nothing. This is a dead UI element.

**Recommendation**: Either wire `onSend` to start a new task from the overlay (requires session access), or hide Row 3 in the overlay when no task is active (add a guard in renderMode for Hidden→don't show Row 3).

---

### 4.4 Supplement Confirmation Not Implemented in Compose

`SmartCapsuleManager` has `flashSupplementConfirmation()` which temporarily shows "✓ 已收到" on the thought line. `SmartCapsuleCompose` has no equivalent. When the user sends a supplement from the main app, there's no visual feedback.

**Recommendation**: Add a transient supplement confirmation in the Compose capsule, possibly via a `supplementConfirmation` state in CapsuleStateHolder (a nullable `String` that auto-clears after a delay).

---

### 4.5 Nudge Timer Not Implemented in Compose

The 4-minute nudge ("还在等待您的回复...") works in the overlay but not in the Compose capsule.

**Recommendation**: Move nudge logic to CapsuleStateHolder (it already tracks mode). Have the state holder emit a `WaitingForInput` with appended nudge text after 4 minutes, so both renderers show it automatically.

---

## 5. Low: Minor Issues & Cleanup

### 5.1 Deprecated InputDock and InputState Still Exist

`InputDock.kt` and `InputState` enum are marked `@Deprecated` but still in the codebase. They're 100+ lines of dead code.

**Recommendation**: Delete `InputDock.kt`. Remove `InputState` enum from `ChatMessage.kt`.

---

### 5.2 VirtualDisplayViewerActivity Couples Directly to AgentService.instance

```kotlin
// VirtualDisplayViewerActivity.kt
AgentService.instance?.notifyViewerVisible(sv)
AgentService.instance?.onViewerOpened()
```

Direct static singleton access from an Activity to a Service. This is fragile and untestable.

**Recommendation**: Use a `LocalBroadcastManager` or a shared `StateFlow` in a singleton holder that both Activity and Service observe. Or accept the coupling as pragmatic for an accessibility service app (this is common in Android a11y code).

---

### 5.3 SmartCapsuleCompose Input State Not Preserved Across Mode Transitions

```kotlin
var inputText by remember { mutableStateOf("") }
```

If the user is typing a supplement (mode = Running) and an `ask_user` QUESTION arrives (mode → WaitingForInput), the input mode changes from "补充" to "发送 →", and the hint changes, but the text the user was typing is preserved — which could be confusing since it's now being treated as an answer to the question, not a supplement.

The overlay version handles this correctly with `configureRow3Answer`:
```kotlin
if (previousMode !is CapsuleMode.WaitingForInput) {
    v.inputEditText.text?.clear()
}
```

The Compose version doesn't clear on transition.

**Recommendation**: Clear `inputText` when transitioning to WaitingForInput from a non-WaitingForInput mode. Add a `LaunchedEffect(mode)` that clears the input when appropriate.

---

### 5.4 Magic Durations Should Be Centralized

Scattered timing constants:

| Duration | Location | Purpose |
|---|---|---|
| 300ms | SmartCapsuleManager | Debounce |
| 4 min | SmartCapsuleManager | Nudge delay |
| 5 min | AskUserTool | Timeout |
| 3000ms | SmartCapsuleManager | Auto-hide delay |
| 200ms | SmartCapsuleManager | Keyboard show delay |
| 1500/2000ms | SmartCapsuleManager | Supplement confirmation |
| 250/200ms | SmartCapsuleAnimator | Height animation |
| 300ms | SmartCapsuleAnimator | Exit animation |
| 200ms | SmartCapsuleRenderer | Dot crossfade |

**Recommendation**: Create a `CapsuleTiming` object with named constants.

---

## 6. Bugs Found (Non-Edge-Case)

### 6.1 ServiceOverlayController.onActionExecuted: Spurious TaskStarted on Fallback

```kotlin
if (!capsuleManager.isShowing()) {
    currentTaskInput?.let { input ->
        stateHolder.onTaskStarted("action-fallback", input)
        pushModeToOverlayCapsule()
    }
}
```

If the capsule isn't showing but there's an active task, this calls `onTaskStarted` again with a fake task ID. This corrupts the state holder because it creates a new Running mode with the original input text (not the current thought), and the "action-fallback" task ID doesn't match the real session task ID.

**Recommendation**: Just show the capsule and push the current mode. Don't generate fake TaskStarted events.

---

### 6.2 Takeover Callback Updates State Before Op Is Processed

In ServiceOverlayController's capsuleManager setup:

```kotlin
this.onTakeover = {
    stateHolder.onTakeoverRequested()        // (1) State → TakeoverPending
    pushModeToOverlayCapsule()               // (2) UI shows TakeoverPending
    this@ServiceOverlayController.onTakeover() // (3) Submits Op.Takeover to session
}
```

Step (1) happens before the session even knows about the takeover request. If the session rejects the takeover (e.g., state is not Running), the capsule is already showing TakeoverPending with no way to revert.

**Recommendation**: Submit the Op first, then update state only on confirmation. Or: accept the optimistic update pattern but add rollback logic if the Op fails.

---

## 7. Refactoring Recommendations

### 7.1 Extract Overlay Strategy

Replace `when (platformMode)` in ServiceOverlayController with Strategy pattern:

```kotlin
interface OverlayStrategy {
    fun onTaskStarted(taskId: String, input: String)
    fun onThoughtUpdate(thought: String)
    fun onTaskCompleted(reason: CompletionReason)
    fun onSessionTakeover()
    fun onSessionResumed()
    fun onAskUser(type: AskUserType, message: String, callId: String)
    fun onSupplementReceived(text: String)
    // ... etc
    fun showInitial()
    fun hideAll()
    fun dispose()
}

class A11yOverlayStrategy(
    private val stateHolder: CapsuleStateHolder,
    private val capsuleManager: SmartCapsuleManager,
    private val edgeGlowManager: EdgeGlowManager
) : OverlayStrategy { ... }

class VirtualDisplayOverlayStrategy(
    private val stateHolder: CapsuleStateHolder,
    private val capsuleManager: SmartCapsuleManager,
    private val statusIslandManager: StatusIslandManager
) : OverlayStrategy { ... }
```

ServiceOverlayController becomes a thin coordinator:

```kotlin
class ServiceOverlayController(...) {
    val stateHolder = CapsuleStateHolder()
    private var strategy: OverlayStrategy = A11yOverlayStrategy(...)

    fun setPlatformMode(mode: PlatformMode) {
        strategy = when (mode) {
            PlatformMode.ACCESSIBILITY -> A11yOverlayStrategy(...)
            PlatformMode.VIRTUAL_DISPLAY -> VirtualDisplayOverlayStrategy(...)
        }
    }

    fun onTaskStarted(taskId: String, input: String) {
        stateHolder.onTaskStarted(taskId, input)
        strategy.onTaskStarted(taskId, input)
    }
}
```

**Estimated reduction**: ~150 lines from ServiceOverlayController, cleaner separation, easier to add new platform modes.

---

### 7.2 Make SmartCapsuleManager Observe StateFlow Directly

Instead of ServiceOverlayController pushing state:

```kotlin
class SmartCapsuleManager(
    private val service: AccessibilityService,
    private val stateHolder: CapsuleStateHolder
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun startObserving() {
        scope.launch {
            stateHolder.mode.collect { newMode ->
                renderMode(newMode, stateHolder.previousMode)
            }
        }
    }

    fun stopObserving() {
        scope.cancel()
    }
}
```

This eliminates `pushModeToOverlayCapsule()` entirely. Every state change automatically renders.

**Trade-off**: Currently `renderMode` is always called from MainThread (via ServiceOverlayController). With flow collection, we need to ensure MainThread dispatch (hence `Dispatchers.Main` above). This is standard practice.

---

### 7.3 Move Business Logic Out of Renderers into CapsuleStateHolder

| Current location | Logic | Move to |
|---|---|---|
| SmartCapsuleManager.handleRow3Submit | Mode → action routing | CapsuleStateHolder or ServiceOverlayController callback |
| SmartCapsuleManager.flashSupplementConfirmation | Confirmation timer | CapsuleStateHolder (emit transient state) |
| SmartCapsuleManager.startNudgeTimer | 4-minute nudge | CapsuleStateHolder (emit updated WaitingForInput with nudge text) |
| SmartCapsuleManager.scheduleAutoHide | Done → Hidden timer | CapsuleStateHolder (schedule self-transition) |

If timers live in CapsuleStateHolder, both Compose and View renderers get the behavior for free.

---

### 7.4 Create Shared CapsuleRenderSpec

See [Section 3.1](#31-two-complete-rendering-implementations) for the full proposal.

The spec would be a pure function: `CapsuleMode → CapsuleRenderSpec`. Both `SmartCapsuleRenderer` and `SmartCapsuleCompose` would consume the spec rather than independently implementing mode-to-visual mapping.

**Estimated dedup**: ~200 lines.

---

### 7.5 Simplify CapsuleViews Data Class

`CapsuleViews` is a data class with **24 fields**. Many of these could be nested:

```kotlin
// Current: 24 flat fields
data class CapsuleViews(
    val container: ViewGroup,
    val row1: ViewGroup,
    val statusDot: View,
    val thoughtText: TextView,
    val divider1: View,
    val expandedBody: TextView?,
    val row2: ViewGroup,
    val primaryButton: ViewGroup,
    val primaryIcon: TextView,
    val primaryText: TextView,
    val stopButton: ViewGroup,
    val stopIcon: TextView,
    val stopText: TextView,
    val navMinimize: View?,
    val navApp: View?,
    val navWatch: View?,
    val divider2: View,
    val row3: ViewGroup,
    val inputEditText: EditText,
    val inputButton: ViewGroup,
    val inputButtonText: TextView,
)

// Simplified: nested groups
data class CapsuleViews(
    val container: ViewGroup,
    val row1: Row1Views,
    val divider1: View,
    val expandedBody: TextView?,
    val row2: Row2Views,
    val divider2: View,
    val row3: Row3Views,
)
```

This is a minor cleanup but makes the code more navigable.

---

### 7.6 Delete Dead Code

Files/elements to remove:
1. `InputDock.kt` — deprecated, replaced by SmartCapsuleCompose
2. `InputState` enum in `ChatMessage.kt` — deprecated
3. `StatusIslandManager.updatePauseState()` — could be replaced by CapsuleMode-driven rendering
4. `StatusIslandManager.showSuccess()` / `showError()` — same reason

---

## 8. What's Working Well

Credit where due — several design decisions are excellent:

### 8.1 CapsuleMode Sealed Interface

The sealed interface with data classes is the right pattern. It's exhaustive (when-expressions enforce handling all cases), type-safe, and carries exactly the data each mode needs. The extension functions (`displayThought()`, `isExpanded()`, `sanitizeThought()`) are clean and well-tested.

### 8.2 UserResponseChannel

Simple, correct, well-tested. `CompletableDeferred` as a suspension bridge between the tool execution coroutine and the UI layer is elegant. The `awaitResponse` / `deliver` / `cancel` API is minimal and complete. The test suite covers the important cases.

### 8.3 AskUserTool Design

Clean separation: AskUserTool (tool spec + validation) → AskUserInvocation (suspension + timeout) → UserResponseChannel (delivery bridge). The timeout-vs-cancellation distinction is well-documented and correctly implemented.

### 8.4 CapsuleStateHolder Unit Tests

The `CapsuleStateHolderTest` has good coverage of the happy-path state transitions. Adding transition guard tests (per Section 2.2) would make it excellent.

### 8.5 CapsuleMode Unit Tests

`CapsuleModeTest` thoroughly tests `sanitizeThought`, `displayThought`, and `isExpanded`. Good boundary testing (empty string, exact 40 chars, whitespace-only).

### 8.6 Agent Thought Pipeline

The flow from `agent_thought` parameter → `emitAgentThought()` → `ThoughtUpdate` event → `CapsuleStateHolder.onThoughtUpdate()` → capsule display is well-designed. The fallback chain (agent_thought → tool description → default) is pragmatic.

### 8.7 Three-Row Layout Concept

The 3-row capsule layout (thought + controls + input) is a clean UX abstraction. Row visibility per mode is well-thought-out. The decision to merge supplement input into Row 3 (eliminating the separate SupplementInput mode) was a good simplification.

---

## Summary: Priority-Ordered Action Items

| Priority | Item | Effort | Impact |
|---|---|---|---|
| **P1** | Fix spurious onTaskStarted fallback (6.1) | 15 min | State corruption |
| **P1** | Extract OverlayStrategy from ServiceOverlayController (7.1) | 2-3 hr | Eliminates god object, all `when(platformMode)` |
| **P1** | Eliminate state duplication in ServiceOverlayController (2.1) | 1 hr | Eliminates sync bugs |
| **P1** | Make SmartCapsuleManager observe StateFlow (7.2) | 1-2 hr | Eliminates "forgot to push" bugs |
| **P2** | Create shared CapsuleRenderSpec (7.4) | 2 hr | Eliminates View/Compose duplication |
| **P2** | Derive GlowState from CapsuleMode (1.3) | 1 hr | Eliminates parallel state machine |
| **P2** | StatusIslandManager driven by CapsuleMode (1.4) | 1 hr | Completes "one state, many renderers" |
| **P2** | Add transition guards to CapsuleStateHolder (2.2) | 1 hr | Prevents invalid states |
| **P3** | Move timers to CapsuleStateHolder (7.3) | 1-2 hr | Unifies Compose/View behavior |
| **P3** | Centralize colors and timing constants (3.2, 5.4) | 30 min | DRY |
| **P3** | Delete InputDock.kt and InputState (7.6) | 15 min | Dead code removal |
| **P3** | Fix Compose input preservation (5.3) | 15 min | UX bug |
| **P4** | Split CapsuleMode.Hidden semantics (4.2) | 30 min | Clarity |
| **P4** | Simplify CapsuleViews data class (7.5) | 30 min | Readability |

**Total estimated effort for P1-P2**: ~10 hours. This would eliminate the major architectural issues and leave a clean, maintainable capsule system.

---

> "Simplicity is the ultimate sophistication." — Leonardo da Vinci
>
> The CapsuleMode sealed interface is the gem of this design. Trust it more. Let everything flow from it. When you find yourself creating a shadow boolean or a manual push call, that's a signal — you're working around the system instead of with it.
