# Stage 4 — ask_user Polish + VD Fix + Takeover Timing

**Status**: DESIGN
**Depends on**: Round 1 (Stages 1-3) + local fixes
**UX Reference**: `round2/ux_design_round2.md` §1-5

---

## Goal

Make ask_user feel great, make it work in VD mode, and fix takeover timing honesty.

Six deliverables:
1. WaitingFor* expanded layout (header/body/input sections)
2. 4-minute timeout nudge
3. Context-aware supplement confirmation
4. VD mode ask_user via SmartCapsule overlay
5. Takeover timing: defer SessionTakeover until agent actually pauses
6. SmartCapsuleManager file size under 400 lines (extract expanded layout rendering)

---

## Phase 1: Expanded Layout

### 1.1 SmartCapsuleLayoutBuilder Changes

Add three new views to `CapsuleViews`:

```kotlin
data class CapsuleViews(
    // ... existing fields ...

    // Expanded section (WaitingFor* states)
    val expandedSection: ViewGroup?,      // LinearLayout container
    val expandedHeader: TextView?,        // "💬 等待答复" or "✋ 操作手机"
    val expandedBody: TextView?,          // Question/instruction text
    val expandedDivider: View?,           // Divider between header and body
)
```

Build these in `SmartCapsuleLayoutBuilder.build()`:

```
card (LinearLayout, vertical)
├── row1: [statusDot] [thoughtText]             ← existing
├── divider                                      ← existing
├── expandedSection (GONE by default)            ← NEW
│   ├── expandedHeader: "💬 等待答复"
│   ├── expandedDivider
│   └── expandedBody: question/instruction text
├── row2: [补充] [primary] [停止]                 ← existing
└── supplementInputArea: [EditText] [发送]        ← existing (reused for answer input)
```

**Key details:**
- `expandedSection` is inserted between `divider` and `row2`
- Default visibility: `GONE`
- Header: 12sp, muted gray `#6B7280`, single line
- Body: 14sp, dark `#171717`, max 3 lines (WaitingForInput) or 2 lines (WaitingForAction)
- Body/header divider: 1dp `#E5E5E5`

### 1.2 SmartCapsuleManager Rendering

In `renderWaitingForInput()`:
1. Hide row1's statusDot and thoughtText (or set them to the header text — simpler to just hide and use expandedSection)
2. Show expandedSection
3. Set expandedHeader to "💬 等待答复"
4. Set expandedBody to `mode.question`
5. Show supplementInputArea (reused for answer input), configure as answer mode
6. Show row2 with only stop button
7. Set overlay focusable, raise keyboard

In `renderWaitingForAction()`:
1. Hide row1 content
2. Show expandedSection
3. Set expandedHeader to "✋ 操作手机"
4. Set expandedBody to `mode.instruction`
5. Hide supplementInputArea
6. Show row2 with 完成 (primary) + 停止

In all OTHER render methods:
1. Hide expandedSection (`expandedSection?.visibility = GONE`)
2. Show row1 content as before

### 1.3 Layout Simplification

Actually, let me reconsider. Instead of hiding row1 and showing expandedSection, we can repurpose row1:

**Revised approach** — Row 1 becomes the header in expanded states:

```
card
├── row1: [statusDot | expandedHeaderIcon] [thoughtText | expandedHeaderText]
├── divider
├── expandedBody (GONE by default): question/instruction text
├── row2: buttons
└── supplementInputArea: input
```

This is simpler — we already use row1's thoughtText for the header. We just need to:
- Add `expandedBody` (a new TextView, GONE by default) between divider and row2
- In WaitingFor* states: set row1 as header, show expandedBody with the question

This avoids duplicating header layout. **One new view** instead of four.

### 1.4 Final Layout (Revised)

Add to `CapsuleViews`:

```kotlin
val expandedBody: TextView?  // question/instruction, between divider and row2
```

Build in `SmartCapsuleLayoutBuilder.build()`:

```
card
├── row1: [statusDot] [thoughtText]
├── divider
├── expandedBody (TextView, GONE, 14sp, max 3 lines, padding 12dp)  ← NEW
├── row2: buttons
└── supplementInputArea
```

In `renderWaitingForInput`:
- row1: hide dot, set thoughtText to "💬 等待答复" (12sp, gray)
- expandedBody: VISIBLE, text = mode.question (14sp, dark, max 3 lines)
- supplementInputArea: VISIBLE (answer input)
- row2: only stop button

In `renderWaitingForAction`:
- row1: hide dot, set thoughtText to "✋ 操作手机" (12sp, gray)
- expandedBody: VISIBLE, text = mode.instruction (14sp, dark, max 2 lines)
- row2: 完成 + 停止

In all other render methods:
- expandedBody: GONE

This approach requires:
- 1 new view in the layout builder
- Minimal changes to render methods (add `expandedBody.visibility = GONE` to compact renders, show in expanded renders)

---

## Phase 2: 4-Minute Nudge

### 2.1 Implementation

In `SmartCapsuleManager`:

```kotlin
private var nudgeRunnable: Runnable? = null
private val NUDGE_DELAY_MS = 4 * 60 * 1000L  // 4 minutes

private fun startNudgeTimer() {
    cancelNudgeTimer()
    nudgeRunnable = Runnable {
        val v = views ?: return@Runnable
        // Append nudge text to expandedBody
        val currentText = v.expandedBody?.text?.toString() ?: ""
        v.expandedBody?.text = "$currentText\n还在等待您的回复..."
    }.also { handler.postDelayed(it, NUDGE_DELAY_MS) }
}

private fun cancelNudgeTimer() {
    nudgeRunnable?.let { handler.removeCallbacks(it) }
    nudgeRunnable = null
}
```

- Call `startNudgeTimer()` when entering WaitingForInput or WaitingForAction
- Call `cancelNudgeTimer()` in `updateMode()` (alongside other runnable cancellations)

### 2.2 Cleanup

The nudge runnable is cancelled in `updateMode()`, `hide()`, and `dispose()`. Same pattern as `delayedHideRunnable` and `supplementConfirmedRunnable`.

---

## Phase 3: Context-Aware Supplement Confirmation

### 3.1 Implementation

Track whether the agent is mid-turn:

```kotlin
// SmartCapsuleManager
private var isAgentMidTurn = false

fun onTurnPhaseChanged(phase: TurnPhase) {
    isAgentMidTurn = (phase == TurnPhase.EXECUTION || phase == TurnPhase.PLANNING)
}

fun onTurnCompleted() {
    isAgentMidTurn = false
}
```

In `onSupplementConfirmed()`:

```kotlin
val confirmText = if (isAgentMidTurn) "✓ 已收到，下一步生效" else "✓ 已收到"
val confirmDuration = if (isAgentMidTurn) 2000L else 1500L
v.thoughtText.text = confirmText
// ... postDelayed with confirmDuration ...
```

### 3.2 Event Flow

`ServiceOverlayController` already routes `TurnPhaseChanged` events. Need to forward to capsuleManager:

```kotlin
// In ServiceOverlayController.onTurnPhaseChanged():
PlatformMode.ACCESSIBILITY -> {
    edgeGlowManager.updateState(currentGlowState)
    capsuleManager.onTurnPhaseChanged(phase)  // NEW
}
```

---

## Phase 4: VD Mode ask_user

### 4.1 ServiceOverlayController Changes

Currently in `onAskUser`, VD mode just updates the status island text. Change to:

```kotlin
PlatformMode.VIRTUAL_DISPLAY -> {
    // Show SmartCapsule overlay for ask_user (same as A11y mode)
    capsuleManager.cancelSupplementIfActive()
    val capsuleMode = when (type) {
        AskUserType.QUESTION -> CapsuleMode.WaitingForInput(question = message, callId = callId)
        AskUserType.ACTION -> CapsuleMode.WaitingForAction(instruction = message, callId = callId)
    }
    capsuleManager.updateMode(capsuleMode)

    // Update status island to show ask_user state
    statusIslandManager?.updateStatus("❓ ${message.take(20)}", glowStateColor(GlowState.Paused))
}
```

### 4.2 Capsule Lifecycle in VD Mode

The capsule needs to hide after the ask_user response is delivered. Currently, when the user sends a response, the capsule transitions to `Running`. But in VD mode, the capsule should hide (we use status island for Running state).

Add a callback or check in `SmartCapsuleManager` for post-response behavior:

Option A: In ServiceOverlayController, detect the transition back to Running after ask_user and hide capsule in VD mode.

Option B: Add a flag to SmartCapsuleManager that auto-hides on transition away from WaitingFor* states.

**Chosen: Option A** — it's explicit and doesn't add hidden behavior to the capsule.

In `ServiceOverlayController`, when `onSessionResumed` or `onThoughtUpdate` fires in VD mode:
- If capsuleManager is showing, hide it (the ask_user interaction is done)

```kotlin
// In onThoughtUpdate / onSessionResumed (VD mode):
if (capsuleManager.isShowing()) {
    capsuleManager.hide()
}
```

### 4.3 Capsule onUserResponse in VD Mode

The capsule's `onUserResponse` callback already goes through `ServiceOverlayController.onUserResponse`. This delivers the response to the agent session via `Op.UserResponse`. No change needed here.

The capsule's `onStop` callback already goes through `ServiceOverlayController.onStop`. Works in both modes.

### 4.4 Status Island During ask_user

While the capsule is showing the ask_user UI, the status island continues showing its compact pill at the top. The status island shows "❓ question..." with purple dot. This is fine — two overlays coexisting.

---

## Phase 5: Takeover Timing Fix

### 5.1 Agent.kt Changes

Add a "pause confirmed" signal:

```kotlin
// Agent.kt
private var pauseConfirmed: CompletableDeferred<Unit>? = null

suspend fun pause(): Deferred<Unit> {
    val confirmed = CompletableDeferred<Unit>()
    lifecycleMutex.withLock {
        pauseState.value = true
        pauseConfirmed = confirmed
    }
    return confirmed
}

// In the main loop, when pause check triggers:
if (pauseState.value) {
    pauseConfirmed?.complete(Unit)
    pauseConfirmed = null
    pauseState.first { !it }
}
```

### 5.2 SessionAgentRunner.kt Changes

Propagate the Deferred:

```kotlin
suspend fun pause(): Deferred<Unit> {
    return agent?.pause() ?: CompletableDeferred<Unit>().also { it.complete(Unit) }
}
```

### 5.3 AgentSession.kt Changes

Wait for pause confirmation before emitting SessionTakeover:

```kotlin
private suspend fun handleTakeover() {
    if (_state.value != SessionState.Running) return

    val confirmed = agentRunner.pause()
    _state.value = SessionState.Paused

    // Wait for agent to actually pause (current turn finishes)
    confirmed.await()

    emit(AgentEvent.SessionTakeover(sessionId = sessionId, timestamp = now()))
}
```

### 5.4 UI Flow

No capsule changes needed! The existing flow works:
1. User taps 接管 → capsule shows TakeoverPending (immediately, via `requestTakeover()`)
2. `Op.Takeover` sent to session
3. Session calls `agentRunner.pause()`, gets deferred
4. Session awaits deferred (current turn finishes)
5. Session emits `SessionTakeover` (agent ACTUALLY paused now)
6. Capsule transitions to Takeover

TakeoverPending is visible for the duration of the current turn (0-few seconds). Exactly right.

---

## Phase 6: Extract SmartCapsuleRenderer

SmartCapsuleManager is 439 lines. With the expanded layout changes, it will grow further. Extract the rendering logic.

### 6.1 SmartCapsuleRenderer.kt (new file)

Extract all `render*()` methods into a standalone renderer class:

```kotlin
class SmartCapsuleRenderer(private val service: AccessibilityService) {

    // Dot pulse
    private var pulseAnimator: AnimatorSet? = null

    fun render(views: CapsuleViews, mode: CapsuleMode) { ... }

    // Private render methods
    private fun renderRunning(...) { ... }
    private fun renderWaitingForInput(...) { ... }
    // ... etc ...

    // Dot helpers
    fun startPulse(dot: View) { ... }
    fun stopPulse() { ... }
    fun setDotColor(views: CapsuleViews, color: Int, pulsing: Boolean) { ... }

    fun dispose() { stopPulse() }
}
```

### 6.2 SmartCapsuleManager Simplified

SmartCapsuleManager keeps:
- Overlay lifecycle (show/hide/dispose)
- State management (mode, callbacks)
- Button logic (handlePrimaryClick, handleStopClick)
- Event handlers (onTaskStarted, onMessageDelta, etc.)
- Input area management (supplement, answer)
- Keyboard management

Delegates to SmartCapsuleRenderer:
- All `render*()` methods
- Dot animation

**Target: ~250 lines for Manager, ~200 lines for Renderer.**

---

## Execution Order

1. **Phase 6** first — Extract renderer before adding new code. Clean foundation.
2. **Phase 1** — Expanded layout. Core visual improvement.
3. **Phase 2** — Nudge timer. Small addition.
4. **Phase 3** — Context-aware supplement. Small addition.
5. **Phase 4** — VD mode fix. Critical functional fix.
6. **Phase 5** — Takeover timing. Backend correctness.

Phases 2-3 are tiny and can be done together. Phase 4 and 5 are independent of each other but both depend on Phase 1 (expanded layout is used in VD mode's ask_user).

---

## Files Modified

| File | Change |
|------|--------|
| `SmartCapsuleLayoutBuilder.kt` | Add `expandedBody` view |
| `SmartCapsuleManager.kt` | Extract renderer, add nudge timer, context-aware supplement |
| `SmartCapsuleRenderer.kt` | **NEW** — extracted render methods + expanded layout rendering |
| `ServiceOverlayController.kt` | VD ask_user capsule show/hide, forward TurnPhaseChanged |
| `Agent.kt` | pause() returns Deferred, pauseConfirmed signal |
| `SessionAgentRunner.kt` | Propagate Deferred from Agent.pause() |
| `AgentSession.kt` | handleTakeover() awaits pause confirmation |
| `AgentDefTest.kt` | Update if Agent.pause() signature changes |

---

## Testing

| Test | What |
|------|------|
| Unit: CapsuleModeTest | Existing — no changes needed |
| Unit: UserResponseChannelTest | Existing — no changes needed |
| Unit: AgentDefTest | Verify allowedTools still includes ask_user |
| Manual: A11y ask_user | Verify expanded layout, nudge at 4min, collapse on response |
| Manual: VD ask_user | Verify capsule appears, user can respond, capsule hides |
| Manual: Takeover timing | Verify capsule stays in TakeoverPending until agent actually pauses |
| Manual: Supplement timing | Verify "下一步生效" when agent is mid-turn |
| Build: ./gradlew assembleDebug | Must pass |
| Lint: ./gradlew lint | No new warnings |
