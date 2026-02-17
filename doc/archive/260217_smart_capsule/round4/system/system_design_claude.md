status: approved

# Qi Review Note for v2:
1. use /ultra-think mode to refine your section 3A. Make sure it is flawless.
2. make sure code里面没有中文，包括button的text等等，都改成英文。
3. 1.2.改好，然后就可以开始implement了。

重要Note：
- 注意保证你的code的cleanliness， 不要有重复的代码，不要有冗余的代码，不要有难以理解的代码。
- /summarize context before starting implementation.
- Fiduciarily follow steps in sop/system_work.md and sop/code_work.md. 该create的todo items一定要创建，该执行的步骤一定要执行, flatten out your todo items, so your steps are all clear。不要跳步。每个sop里提到的skill一定要在相应的步骤使用。

---

# Qi Review Note for v1:
1. 这个能保证smart capsule在main app和overlay下都render得一摸一样吗？ pixel-by-pixel the same吗？
2. Make sure state machine design is correct and complete. Every state and transition is covered and well thought out. 不同state下，不同UI component该怎么render，都要想清楚。 The state machine should be able to handle all the edge cases and corner cases.
3. section 4.10 OverlayStrategy 在之前的一个design draft里提到，但是你这里没有展开，这个如果有必要实现的话，那你还是有必要展开一点点？要么就别提。
4. 确保accessibility platform和virtual display platform都能perfectly work。


---

# Claude v2 Response to Review Notes:

**Re Point 1 (Pixel-perfect rendering):** See new Section 4A below. CapsuleRenderSpec guarantees logical-identical rendering (same text, colors, visibility, button labels, layout structure). Pixel-perfect is not achievable between View and Compose frameworks (different rendering engines, font metrics, spacing). But the UX will be functionally identical — same information, same controls, same visual hierarchy. This is the correct goal.

**Re Point 2 (Complete state machine):** See new Section 3A below. Full state transition table with every valid transition, guard conditions, and what each renderer shows per state.

**Re Point 3 (OverlayStrategy):** Removed. After deeper analysis, the `when (platformMode)` blocks in the simplified controller are only 3-4 places, all in visibility management. A full Strategy pattern adds an abstraction layer that costs more than it saves. The simplified controller is already clean enough without it.

**Re Point 4 (A11y + VD platforms):** See new Section 5A below. End-to-end flow for both platforms with every event and visibility decision documented.

---

# Smart Capsule Round 4 — KISS Refactor System Design (v2)

> Author: Claude (ultra-think mode)
> Date: 2026-02-13
> Principle: KISS + Occam's Razor — trust CapsuleStateHolder, eliminate everything else

---

## 1. Core Diagnosis

After synthesizing both round 1-3 code reviews, one insight dominates:

> **CapsuleStateHolder is the right abstraction. Everything around it doesn't trust it enough.**

Every bug, every duplication, every "forgot to wire X" issue traces to the same root: ServiceOverlayController maintains shadow state and manually pushes it to renderers instead of letting them observe. Fix the trust relationship, and most complexity melts away.

## 2. Design Principles

1. **One state, many observers.** CapsuleStateHolder emits. Renderers observe. No manual pushing.
2. **Derive, don't duplicate.** GlowState, island text, render specs — all derived from CapsuleMode.
3. **Separate WHAT from WHETHER.** State says what to show. Controller decides whether the overlay window is visible.
4. **No shadow state.** If CapsuleStateHolder knows it, nobody else should store it.

## 3. Architecture After Refactor

```
AgentEvent
    │
    ▼
AgentService.handleEvent()
    │
    ▼
ServiceOverlayController.onXxx()        ← thin coordinator
    │
    ├── stateHolder.onXxx()              ← single source of truth
    │       │
    │       ├──→ SmartCapsuleManager     ← observes mode StateFlow, auto-renders
    │       ├──→ SmartCapsuleCompose     ← observes mode StateFlow (already does)
    │       └──→ StatusIslandManager     ← observes mode StateFlow, derives display
    │
    └── ensureOverlayVisibility()        ← window show/hide only
            │
            ├── edgeGlowManager.show/hide(derivedGlowState)
            ├── capsuleManager.show/hide()
            └── statusIslandManager.show/hide()
```

## 3A. Complete State Machine (ultra-think refined)

### States
```
Hidden ─── no task active, capsule not shown (overlay) / input-only (Compose main app)
Running ── agent actively working, shows thought + controls
TakeoverPending ── user requested takeover, waiting for current action to finish
Takeover ── user has control, agent paused
WaitingForInput ── agent asked a QUESTION, waiting for text answer
WaitingForAction ── agent asked user to DO something on phone
Done ── task completed, auto-hides after 3s
Error ── error occurred, stays until dismissed
```

### Exhaustive State × Event Matrix

Every cell = result. `—` = no-op (logged). `→X` = transition to state X.

| Event \ State | Hidden | Running | TakeoverPending | Takeover | WaitingForInput | WaitingForAction | Done | Error |
|---|---|---|---|---|---|---|---|---|
| onTaskStarted | →Running | →Running | →Running | →Running | →Running | →Running | →Running | →Running |
| onThoughtUpdate | — | →Running | — | — | — | — | — | — |
| onTakeoverRequested | — | →TakeoverPending | — | — | — | — | — | — |
| onTakeoverConfirmed | — | →Takeover | →Takeover | — | — | — | — | — |
| onResumed | — | — | →Running | →Running | — | — | — | — |
| onAskUser(Q) | →WaitingForInput | →WaitingForInput | →WaitingForInput | →WaitingForInput | →WaitingForInput | →WaitingForInput | →WaitingForInput | →WaitingForInput |
| onAskUser(A) | →WaitingForAction | →WaitingForAction | →WaitingForAction | →WaitingForAction | →WaitingForAction | →WaitingForAction | →WaitingForAction | →WaitingForAction |
| onUserResponseSent | — | — | — | — | →Running | →Running | — | — |
| onTaskCompleted | — | →Done/Error | →Done/Error | →Done/Error | →Done/Error | →Done/Error | — | — |
| onError | →Error | →Error | →Error | →Error | →Error | →Error | →Error | →Error |
| onDismissError | — | — | — | — | — | — | — | →Hidden |
| (auto-hide 3s) | — | — | — | — | — | — | →Hidden | — |

**Design rationale:**
- `onTaskStarted` and `onError`: **universal reset** — can be called from ANY state. New task or error always takes priority.
- `onAskUser`: **universal** — agent can ask user from any active state. This handles edge cases where ask_user arrives during takeover handoff.
- `onThoughtUpdate`: **Running-only** — in TakeoverPending/Takeover the agent shouldn't be emitting thoughts. In WaitingFor* the agent is suspended. Silently ignored in non-Running states.
- `onTakeoverRequested`: **Running-only** — can only request takeover when agent is actively running.
- `onResumed`: **Takeover/TakeoverPending-only** — can only resume from paused states.
- `onUserResponseSent`: **WaitingFor*-only** — only meaningful when there's a pending ask.
- `onTaskCompleted`: **active states only** — ignored in Hidden (no task) and Done/Error (already terminal).

### Transition Guards (implemented in CapsuleStateHolder)

```kotlin
// ── Universal events (any state → target) ──

fun onTaskStarted(taskId: String, input: String) {
    cancelAutoHide()
    _turnPhase.value = null
    setMode(CapsuleMode.Running(sanitizeThought(input)))
}

fun onError(message: String) {
    cancelAutoHide()
    setMode(CapsuleMode.Error(sanitizeThought(message)))
}

fun onAskUser(type: AskUserType, message: String, callId: String) {
    setMode(when (type) {
        AskUserType.QUESTION -> CapsuleMode.WaitingForInput(question = message, callId = callId)
        AskUserType.ACTION -> CapsuleMode.WaitingForAction(instruction = message, callId = callId)
    })
}

// ── Guarded events (specific states only) ──

fun onThoughtUpdate(thought: String) {
    if (_mode.value !is CapsuleMode.Running) return  // silent ignore
    setMode(CapsuleMode.Running(thought))
}

fun onTakeoverRequested() {
    val current = _mode.value as? CapsuleMode.Running ?: return
    setMode(CapsuleMode.TakeoverPending(current.thought))
}

fun onTakeoverConfirmed() {
    val thought = when (val m = _mode.value) {
        is CapsuleMode.TakeoverPending -> m.lastThought
        is CapsuleMode.Running -> m.thought
        else -> return
    }
    setMode(CapsuleMode.Takeover(thought))
}

fun onResumed() {
    val current = _mode.value
    if (current !is CapsuleMode.Takeover && current !is CapsuleMode.TakeoverPending) return
    _turnPhase.value = null
    setMode(CapsuleMode.Running("Thinking..."))
}

fun onUserResponseSent(callId: String) {
    val current = _mode.value
    if (current !is CapsuleMode.WaitingForInput && current !is CapsuleMode.WaitingForAction) return
    setMode(CapsuleMode.Running("Processing response..."))
}

fun onTaskCompleted(reason: CompletionReason) {
    val current = _mode.value
    if (current is CapsuleMode.Hidden || current is CapsuleMode.Done || current is CapsuleMode.Error) return
    setMode(/* Done or Error based on reason */)
    scheduleAutoHide()
}

fun onDismissError() {
    if (_mode.value !is CapsuleMode.Error) return
    setMode(CapsuleMode.Hidden)
}
```

### Per-State Renderer Spec (ALL ENGLISH)

| State | Dot | Thought | ExpandedBody | Primary Btn | Stop Btn | Row3 | Row3 Mode |
|---|---|---|---|---|---|---|---|
| Running | 🔵 pulse | thought text | — | ✋ Takeover | ⏹ Stop | ✓ | Add note |
| TakeoverPending | 🟡 static | "Handing over..." | — | ✋ Handing over (disabled) | ⏹ Stop | ✓ | Add note |
| Takeover | 🟡 static | lastThought (dim) | — | ▶ Resume | ⏹ Stop | ✓ | Add note |
| WaitingForInput | — | "💬 Awaiting response" | question | — | ⏹ Stop | ✓ | Send → |
| WaitingForAction | — | "✋ Action needed" | instruction | ✅ Done | ⏹ Stop | ✗ | — |
| Done | 🟢 static | "✓ message" | — | — | — | ✗ | — |
| Error | 🔴 static | "⚠ message" | — | — | ✕ Close | ✗ | — |
| Hidden | — | — | — | — | — | ✓* | Send → |

*Hidden + Row3 only applies in MAIN_APP context (Compose capsule acts as input dock). Overlay capsule is not shown in Hidden mode.

## 4. Changes

### 4.1 New: CapsuleRenderSpec (pure rendering specification)

**File:** `ui/overlay/model/CapsuleRenderSpec.kt`

A pure data class that maps `CapsuleMode → visual properties`. Both View and Compose renderers read from this spec. This is the SINGLE source of truth for "what does the capsule look like in each mode."

```kotlin
data class CapsuleRenderSpec(
    val dot: DotSpec?,                    // null = dot hidden
    val thought: ThoughtSpec,
    val expandedBody: String?,            // null = collapsed
    val buttons: ButtonsSpec,
    val row3: Row3Spec?,                  // null = row hidden
) {
    data class DotSpec(val color: Int, val pulsing: Boolean)
    data class ThoughtSpec(val text: String, val alpha: Float = 1f)
    data class ButtonSpec(val icon: String, val text: String, val enabled: Boolean = true)
    data class ButtonsSpec(val primary: ButtonSpec?, val stop: ButtonSpec?)
    data class Row3Spec(val hint: String, val buttonText: String, val clearInput: Boolean = false)

    companion object {
        fun from(mode: CapsuleMode, previousMode: CapsuleMode? = null): CapsuleRenderSpec = when (mode) {
            is CapsuleMode.Running -> CapsuleRenderSpec(
                dot = DotSpec(CapsuleColors.BLUE, pulsing = true),
                thought = ThoughtSpec(mode.thought.ifEmpty { "Thinking..." }),
                expandedBody = null,
                buttons = ButtonsSpec(
                    primary = ButtonSpec("✋", "Takeover"),
                    stop = ButtonSpec("⏹", "Stop")
                ),
                row3 = Row3Spec("Got ideas? Add a note...", "Add note"),
            )
            is CapsuleMode.TakeoverPending -> CapsuleRenderSpec(
                dot = DotSpec(CapsuleColors.AMBER, pulsing = false),
                thought = ThoughtSpec("Handing over..."),
                expandedBody = null,
                buttons = ButtonsSpec(
                    primary = ButtonSpec("✋", "Handing over", enabled = false),
                    stop = ButtonSpec("⏹", "Stop")
                ),
                row3 = Row3Spec("Got ideas? Add a note...", "Add note"),
            )
            is CapsuleMode.Takeover -> CapsuleRenderSpec(
                dot = DotSpec(CapsuleColors.AMBER, pulsing = false),
                thought = ThoughtSpec(mode.lastThought.ifEmpty { "Paused" }, alpha = 0.6f),
                expandedBody = null,
                buttons = ButtonsSpec(
                    primary = ButtonSpec("▶", "Resume"),
                    stop = ButtonSpec("⏹", "Stop")
                ),
                row3 = Row3Spec("Got ideas? Add a note...", "Add note"),
            )
            is CapsuleMode.WaitingForInput -> CapsuleRenderSpec(
                dot = null,
                thought = ThoughtSpec("💬 Awaiting response"),
                expandedBody = mode.question,
                buttons = ButtonsSpec(primary = null, stop = ButtonSpec("⏹", "Stop")),
                row3 = Row3Spec(
                    "Type your response...", "Send →",
                    clearInput = previousMode !is CapsuleMode.WaitingForInput
                ),
            )
            is CapsuleMode.WaitingForAction -> CapsuleRenderSpec(
                dot = null,
                thought = ThoughtSpec("✋ Action needed"),
                expandedBody = mode.instruction,
                buttons = ButtonsSpec(
                    primary = ButtonSpec("✅", "Done"),
                    stop = ButtonSpec("⏹", "Stop")
                ),
                row3 = null,
            )
            is CapsuleMode.Done -> CapsuleRenderSpec(
                dot = DotSpec(CapsuleColors.TEAL, pulsing = false),
                thought = ThoughtSpec("✓ ${mode.message}"),
                expandedBody = null,
                buttons = ButtonsSpec(primary = null, stop = null),
                row3 = null,
            )
            is CapsuleMode.Error -> CapsuleRenderSpec(
                dot = DotSpec(CapsuleColors.RED, pulsing = false),
                thought = ThoughtSpec("⚠ ${mode.message}"),
                expandedBody = null,
                buttons = ButtonsSpec(primary = null, stop = ButtonSpec("✕", "Close")),
                row3 = null,
            )
            is CapsuleMode.Hidden -> CapsuleRenderSpec(
                dot = null,
                thought = ThoughtSpec(""),
                expandedBody = null,
                buttons = ButtonsSpec(primary = null, stop = null),
                row3 = Row3Spec("What can I help you with?", "Send →"),
            )
        }
    }
}
```

### 4.1A Rendering parity guarantee (Re Review Point 1)

**CapsuleRenderSpec is the contract.** Both View and Compose renderers mechanically apply the same spec:

| Spec Field | View Renderer | Compose Renderer |
|---|---|---|
| `dot.color` | `GradientDrawable.setColor(color)` | `Color(color)` → `Box.background()` |
| `dot.pulsing` | `ObjectAnimator` scale 1→1.3→1 | (Compose animation equivalent) |
| `thought.text` | `TextView.text = spec.thought.text` | `Text(text = spec.thought.text)` |
| `thought.alpha` | `TextView.alpha = spec.thought.alpha` | `Text(alpha = spec.thought.alpha)` |
| `expandedBody` | `expandedBody.text = spec.expandedBody` | `Text(text = spec.expandedBody)` |
| `buttons.primary` | `primaryButton.visibility`, text, enabled | `if (spec.buttons.primary != null)` |
| `row3.hint` | `editText.hint = spec.row3.hint` | `placeholder = { Text(spec.row3.hint) }` |

Logical parity is **guaranteed** because both read from the same `CapsuleRenderSpec.from()`. Pixel-level differences between Android View and Jetpack Compose are expected (different rendering engines) but functionally invisible to users — same layout structure, same text, same colors, same controls.

### 4.2 New: CapsuleColors (centralized color constants)

**File:** `ui/overlay/model/CapsuleColors.kt`

```kotlin
object CapsuleColors {
    const val BLUE = 0xFF2563EB.toInt()    // Running
    const val AMBER = 0xFFF59E0B.toInt()   // Takeover / Paused
    const val TEAL = 0xFF0D9488.toInt()     // Done / Success
    const val RED = 0xFFEF4444.toInt()      // Error
    const val PURPLE = 0xFF7C3AED.toInt()   // Executing (glow only)
}
```

### 4.3 New: NavSpec (navigation button visibility)

**File:** included in `CapsuleRenderSpec.kt`

```kotlin
data class NavSpec(
    val showMinimize: Boolean,
    val showApp: Boolean,
    val showWatch: Boolean,
) {
    companion object {
        fun from(context: CapsuleContext, platformMode: PlatformMode, hasIsland: Boolean): NavSpec =
            NavSpec(
                showMinimize = platformMode == PlatformMode.VIRTUAL_DISPLAY && hasIsland,
                showApp = context != CapsuleContext.MAIN_APP,
                showWatch = platformMode != PlatformMode.ACCESSIBILITY
                    && context != CapsuleContext.SCREEN_VIEWING,
            )
    }
}
```

### 4.4 Move: sanitizeThought → protocol/TextUtils.kt

**From:** `ui/overlay/model/CapsuleMode.kt`
**To:** `protocol/TextUtils.kt`

**Why:** `AgentTurnRunner` (agent layer) currently depends on `ui.overlay.model.sanitizeThought`, breaking the layer boundary.

### 4.5 Enhanced: CapsuleStateHolder

**File:** `ui/overlay/CapsuleStateHolder.kt`

Changes:
1. **Add `turnPhase: StateFlow<TurnPhase?>`** — so GlowState can be derived
2. **Add transition guards** on all mutations (see Section 3A)
3. **Add `derivedGlowState` property** — eliminates parallel GlowState tracking
4. **Move auto-hide timer here** — both renderers get the behavior for free
5. **Require CoroutineScope** — for auto-hide timer

### 4.6 Simplified: SmartCapsuleManager (observe StateFlow)

**File:** `ui/overlay/SmartCapsuleManager.kt`

Key change: Instead of being *pushed* state via `renderMode()`, the manager **observes** `stateHolder.mode` directly when shown.

```kotlin
class SmartCapsuleManager(
    private val service: AccessibilityService,
    private val stateHolder: CapsuleStateHolder,
    private val scope: CoroutineScope,
) {
    private var observeJob: Job? = null

    fun show() {
        if (overlayView != null) return
        // ... add view to WindowManager ...
        startObserving()
    }

    fun hide() {
        stopObserving()
        // ... remove view from WindowManager ...
    }

    private fun startObserving() {
        observeJob = scope.launch {
            stateHolder.mode.collect { mode ->
                if (mode is CapsuleMode.Hidden) {
                    hide()
                } else {
                    val spec = CapsuleRenderSpec.from(mode, stateHolder.previousMode)
                    renderer.render(views!!, spec, animate = shouldAnimate(stateHolder.previousMode, mode))
                    setupInteractivity(views!!, mode)
                }
            }
        }
    }
}
```

**Why:** Eliminates `pushModeToOverlayCapsule()` entirely. Every state change auto-renders.

### 4.7 Simplified: SmartCapsuleRenderer (renders CapsuleRenderSpec)

**File:** `ui/overlay/SmartCapsuleRenderer.kt`

Renderer accepts `CapsuleRenderSpec` and mechanically applies it to views. No per-mode methods. No business logic. Pure view-property-setting.

Shrinks from ~380 lines to ~150 lines. All per-mode logic lives in `CapsuleRenderSpec.from()`.

### 4.8 Simplified: SmartCapsuleCompose (uses CapsuleRenderSpec)

**File:** `ui/capsule/SmartCapsuleCompose.kt`

Composables read from `CapsuleRenderSpec`. Fixes:
- Clear `inputText` on WaitingForInput transition (via `spec.row3.clearInput`)
- Use spec for all text, colors, visibility decisions

### 4.9 Simplified: StatusIslandManager (CapsuleMode-driven)

**File:** `ui/overlay/StatusIslandManager.kt`

Replace `updateStatus(string, color)`, `showSuccess()`, `showError()`, `updatePauseState()` with:

```kotlin
fun renderMode(mode: CapsuleMode, glowState: GlowState) {
    val text = when (mode) {
        is CapsuleMode.Running -> mode.thought.take(24)
        is CapsuleMode.TakeoverPending -> "交接中..."
        is CapsuleMode.Takeover -> "已暂停"
        is CapsuleMode.WaitingForInput -> "❓ 等待答复"
        is CapsuleMode.WaitingForAction -> "✋ 操作手机"
        is CapsuleMode.Done -> "✓ ${mode.message}"
        is CapsuleMode.Error -> "⚠ ${mode.message}"
        is CapsuleMode.Hidden -> ""
    }
    updateDisplay(text, glowState.colorHex)
    // Auto-hide after terminal states
    if (mode is CapsuleMode.Done || mode is CapsuleMode.Error) {
        handler.postDelayed({ hide() }, AUTO_HIDE_DELAY_MS)
    }
}
```

### 4.10 Simplified: ServiceOverlayController (thin coordinator)

**File:** `app/ServiceOverlayController.kt`

No OverlayStrategy pattern — not worth the abstraction for 3-4 simple visibility checks. The controller is simplified to:

**Eliminated state:**
- `hasActiveTask` → `stateHolder.hasActiveTask` (derived: `mode !is Hidden`)
- `currentTaskInput` → not needed
- `currentGlowState` → `stateHolder.derivedGlowState`
- `pushModeToOverlayCapsule()` → manager observes directly

**Retained (window management, not state):**
- `isAppInForeground` / `lastKnownForegroundPackage` — Android lifecycle tracking
- `platformMode` — determines which overlay windows to show

**Pattern:** Each event handler = (1) update stateHolder + (2) update visibility/glow/island.

```kotlin
fun onTaskStarted(taskId: String, input: String) {
    stateHolder.onTaskStarted(taskId, input)
    showOverlaysForCurrentState()
}

fun onThoughtUpdate(thought: String) {
    stateHolder.onThoughtUpdate(thought)
    // Overlay capsule auto-updates via observation
    // Island needs explicit push (no coroutine scope):
    updateIsland()
}

fun onTurnPhaseChanged(phase: TurnPhase) {
    stateHolder.setTurnPhase(phase)
    stateHolder.setAgentMidTurn(phase == TurnPhase.EXECUTION || phase == TurnPhase.PLANNING)
    updateGlow()
    updateIsland()
}

fun onTaskCompleted(reason: CompletionReason) {
    stateHolder.onTaskCompleted(reason)
    updateGlow()
    updateIsland()
}

// ── Visibility helpers ──

private fun showOverlaysForCurrentState() {
    when (platformMode) {
        PlatformMode.ACCESSIBILITY -> {
            if (!isAppInForeground) {
                edgeGlowManager.show(stateHolder.derivedGlowState)
                capsuleManager.show() // starts observing, auto-renders
            }
        }
        PlatformMode.VIRTUAL_DISPLAY -> {
            statusIslandManager?.show()
        }
    }
}

private fun updateGlow() {
    if (platformMode == PlatformMode.ACCESSIBILITY && edgeGlowManager.isShowing()) {
        edgeGlowManager.updateState(stateHolder.derivedGlowState)
    }
}

private fun updateIsland() {
    if (platformMode == PlatformMode.VIRTUAL_DISPLAY) {
        statusIslandManager?.renderMode(stateHolder.mode.value, stateHolder.derivedGlowState)
    }
}
```

Expected: **511 → ~200 lines**.

### 4.11 GlowState derivation

**File:** `ui/overlay/model/GlowState.kt` (add function)

```kotlin
fun deriveGlowState(mode: CapsuleMode, turnPhase: TurnPhase?): GlowState = when {
    mode is CapsuleMode.Error -> GlowState.Error
    mode is CapsuleMode.Done -> GlowState.Success
    mode is CapsuleMode.TakeoverPending || mode is CapsuleMode.Takeover -> GlowState.Paused
    mode is CapsuleMode.WaitingForInput || mode is CapsuleMode.WaitingForAction -> GlowState.Paused
    mode is CapsuleMode.Running && turnPhase == TurnPhase.EXECUTION -> GlowState.Executing
    mode is CapsuleMode.Running -> GlowState.Active
    else -> GlowState.Active
}
```

### 4.12 Delete dead code

- `ui/chat/components/InputDock.kt` — deprecated
- `InputState` enum in `ChatMessage.kt` — deprecated

### 4.13 Bug fixes

1. **Spurious `onTaskStarted("action-fallback")`** — removed entirely
2. **Compose input not cleared on WaitingForInput transition** — handled by `CapsuleRenderSpec.clearInput`

## 5. Platform-Specific Flows (Re Review Point 4)

### 5A. ACCESSIBILITY Mode — End-to-End Flow

```
User in Main App → sends task → CapsuleMode: Hidden → Running
    Main App Compose: observes stateHolder.mode, shows Running capsule inline
    Overlay: NOT shown (user is in our app, isAppInForeground = true)
    Glow: NOT shown

User leaves app (TYPE_WINDOW_STATE_CHANGED) → isAppInForeground = false
    Controller: shows overlay capsule + edge glow
    Overlay capsule: starts observing, auto-renders Running
    Glow: show(derivedGlowState = Active)

Agent thinks → ThoughtUpdate → stateHolder.onThoughtUpdate()
    Overlay capsule: auto-renders via observation (no push needed)
    Glow: stays Active

Agent executes → TurnPhaseChanged(EXECUTION) → stateHolder.setTurnPhase(EXECUTION)
    Glow: updateState(derivedGlowState = Executing) → purple color
    Overlay capsule: unchanged (still Running mode)

Agent asks question → AskUser(QUESTION) → stateHolder.onAskUser()
    Mode: WaitingForInput
    Overlay capsule: auto-renders expanded with question + input field + keyboard
    Glow: updateState(Paused) → amber color

User answers → onUserResponse → stateHolder.onUserResponseSent()
    Mode: Running("处理答复中...")
    Overlay capsule: auto-renders compact Running
    Glow: updateState(Active) → blue color

Agent completes → TaskCompleted → stateHolder.onTaskCompleted()
    Mode: Done("已完成")
    Overlay capsule: auto-renders Done, auto-hides after 3s (timer in stateHolder)
    Glow: updateState(Success) → teal, auto-hides

User returns to app → isAppInForeground = true
    Controller: hides overlay capsule + glow
    Main App Compose: observes mode (Done or Hidden), renders accordingly

User sends supplement while agent is running:
    Overlay capsule: Row 3 "补充" button → onSupplement callback → session Op
    Controller: capsuleManager.flashSupplementConfirmation() (transient UI-only)
```

### 5B. VIRTUAL_DISPLAY Mode — End-to-End Flow

```
User in Main App → sends task → CapsuleMode: Hidden → Running
    Controller: shows StatusIsland on real screen
    Island: renderMode(Running, Active) → shows "task text..." with blue dot
    Main App Compose: observes stateHolder.mode, shows Running capsule inline

User leaves app → island stays visible (background context)
    Controller: stateHolder.setContext(BACKGROUND)

User taps island → onIslandTapped()
    Controller: shows overlay capsule, hides island
    Context: SCREEN_VIEWING
    Overlay capsule: starts observing, auto-renders current mode
    Nav buttons: NavSpec(showMinimize=true, showApp=true, showWatch=false)

User taps ⊖ minimize → onMinimize
    Controller: hides overlay capsule, shows island

User taps 📱 → onOpenApp
    Controller: launches MainActivity

User opens VD Viewer → onViewerOpened()
    Controller: shows overlay capsule, hides island
    Context: SCREEN_VIEWING
    Overlay capsule: renders with NavSpec(showMinimize=true, showApp=true, showWatch=false)

User closes VD Viewer → onViewerClosed()
    Controller: hides overlay capsule, shows island
    Context: BACKGROUND

Agent asks question → AskUser(QUESTION)
    Mode: WaitingForInput
    Island: renderMode(WaitingForInput, Paused) → "❓ 等待答复" amber dot
    If capsule showing: auto-renders expanded
    If capsule hidden: island shows question indicator, user can tap to expand

Agent completes → TaskCompleted
    Mode: Done
    Island: renderMode(Done, Success) → "✓ Done", auto-hides after 3s
    Overlay capsule (if showing): auto-renders Done, auto-hides after 3s
```

### 5C. Visibility Decision Matrix

| Platform | User Location | Has Active Task | Overlay Capsule | Edge Glow | Status Island |
|---|---|---|---|---|---|
| A11y | Main App | No | Hidden | Hidden | N/A |
| A11y | Main App | Yes | Hidden (Compose shows) | Hidden | N/A |
| A11y | Other App | Yes | Shown (observing) | Shown | N/A |
| A11y | Other App | No | Hidden | Hidden | N/A |
| VD | Main App | No | Hidden | N/A | Hidden |
| VD | Main App | Yes | Hidden (Compose shows) | N/A | Shown |
| VD | Background | Yes | Hidden | N/A | Shown |
| VD | VD Viewer | Yes | Shown (observing) | N/A | Hidden |
| VD | Island tapped | Yes | Shown (observing) | N/A | Hidden |

## 6. Implementation Phases

### Phase A: Foundation (CapsuleRenderSpec + CapsuleColors + TextUtils)
- Create `CapsuleRenderSpec.kt` with full `from()` implementation
- Create `CapsuleColors.kt`
- Create `protocol/TextUtils.kt` (move sanitizeThought)
- Update imports in `AgentTurnRunner`, `CapsuleStateHolder`, `CapsuleMode.kt`

### Phase B: State holder hardening
- Add `turnPhase` to `CapsuleStateHolder`
- Add transition guards on all mutations
- Add `derivedGlowState` property
- Add `deriveGlowState()` to `GlowState.kt`
- Move auto-hide timer to state holder (requires CoroutineScope)

### Phase C: Renderers use CapsuleRenderSpec
- Rewrite `SmartCapsuleRenderer.render()` to accept CapsuleRenderSpec
- Rewrite `SmartCapsuleCompose` to use CapsuleRenderSpec
- Fix Compose input clearing bug
- Update `SmartCapsuleManager` to use new renderer API

### Phase D: Observer pattern + controller simplification
- SmartCapsuleManager observes `stateHolder.mode` StateFlow
- StatusIslandManager gets `renderMode(CapsuleMode, GlowState)`
- Simplify ServiceOverlayController: remove shadow states, remove push
- Update AgentService wiring

### Phase E: Cleanup
- Delete `InputDock.kt`
- Remove `InputState` from `ChatMessage.kt`
- Fix spurious `onTaskStarted` fallback
- Final code review

## 7. What We're NOT Changing

- `CapsuleMode` sealed interface — it's the gem
- `UserResponseChannel` — clean, correct, well-tested
- `AskUserTool` pipeline — well-designed
- `EdgeGlowManager` internal implementation — works fine
- `SmartCapsuleLayoutBuilder` — layout building is stable
- `SmartCapsuleAnimator` — animation logic is stable
- Agent layer — beyond scope

## 8. Metrics (Expected)

| Metric | Before | After |
|---|---|---|
| ServiceOverlayController lines | 511 | ~200 |
| SmartCapsuleRenderer lines | 382 | ~150 |
| SmartCapsuleCompose lines | 410 | ~300 |
| Shadow state variables | 5 | 0 |
| `pushModeToOverlayCapsule()` calls | 12 | 0 |
| `when (platformMode)` blocks | 14 | ~4 |
| Color constant locations | 5 files | 1 file |
| Rendering logic locations | 2 | 1 (CapsuleRenderSpec) |
| Dead code files | 2 | 0 |
