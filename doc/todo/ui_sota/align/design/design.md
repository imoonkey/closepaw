# UI SOTA Aligned Design — Draft v1

Date: 2026-02-20
Status: Initial draft by Claude, pending Codex review.

---

## 1. State Machine — Consensus

### 1.1 State Dimensions (4-tuple)

| # | Dimension | Values | Owner |
|---|-----------|--------|-------|
| 1 | PlatformMode | ACCESSIBILITY, VIRTUAL_DISPLAY | ServiceOverlayController |
| 2 | OverlayUserLocation | MAIN_APP, VD_VIEWER, OTHER_APP | ServiceOverlayController |
| 3 | CapsuleMode | Hidden, Running, TakeoverPending, Takeover, WaitingForInput, WaitingForAction, Done, Error | CapsuleStateHolder |
| 4 | ShowPreference | CAPSULE, ISLAND | ServiceOverlayController |

### 1.2 CapsuleMode Transitions

**Universal** (from any mode):
- `onTaskStarted(taskId, input)` → Running(sanitize(input))
- `onError(message)` → Error(sanitize(message))
- `onAskUser(QUESTION, msg, callId)` → WaitingForInput(msg, callId)
- `onAskUser(ACTION, msg, callId)` → WaitingForAction(msg, callId)

**Guarded** (specific source modes only):
- `onThoughtUpdate(t)`: Running only → Running(t)
- `onTakeoverRequested()`: Running only → TakeoverPending
- `onTakeoverConfirmed()`: Running|TakeoverPending → Takeover
- `onResumed()`: Takeover|TakeoverPending → Running("Thinking...")
- `onUserResponseSent(callId)`: WaitingForInput|WaitingForAction + callId must match → Running("Processing response...")
- `onDismissError()`: Error only → Hidden

**Task completion** (`onTaskCompleted`):
- Guard: ignores if Hidden/Done/Error
- GOAL_ACHIEVED → Done(message ∥ "Task completed")
- MAX_TURNS → Done("Max steps reached")
- TASK_IMPOSSIBLE → Done("Task impossible")
- USER_STOPPED → Done("Stopped")
- INTERRUPTED → Done("Interrupted")
- ERROR → Error("Error occurred")
- Done auto-hides → Hidden after 3s

**Session completion** (`onSessionEnded`):
- Distinct from task completion
- USER_STOPPED/INTERRUPTED → Hidden (immediate, no Done)
- GOAL_ACHIEVED/MAX_TURNS/TASK_IMPOSSIBLE → Done with auto-hide
- ERROR → Error (if not already Error)

### 1.3 Extended State

- `isStopPending`: transient flag, cleared on terminal events
- `turnPhase`, `isAgentMidTurn`: maintained by TurnPhaseChanged
- `previousMode`: tracks prior mode for render transitions
- `hasActiveTask`: Running|TakeoverPending|Takeover|WaitingForInput|WaitingForAction

### 1.4 ShowPreference Transitions

- Init: ISLAND
- onTaskStarted → CAPSULE
- onAskUser → CAPSULE
- onSessionError → CAPSULE
- onMinimize (⊖) → ISLAND
- onViewerOpened → CAPSULE
- onViewerClosed → ISLAND
- Force normalization: WaitingForInput/WaitingForAction/Error → CAPSULE

### 1.5 Visibility Decision (`deriveOverlayVisibility`)

`isActive = hasActiveTask ∨ Done ∨ Error`

**A11y:**
- MAIN_APP: all overlays hidden
- OTHER_APP + isActive: capsule XOR island (per showPreference) + glow

**VD:**
- MAIN_APP: all overlays hidden
- non-MAIN_APP + !isActive: all overlays hidden
- non-MAIN_APP + isActive: capsule XOR island (per showPreference); glow only in VD_VIEWER + hasActiveTask

Invariants: capsule ⊕ island (never both); MAIN_APP → no system overlays.

---

## 2. User Flow — Consensus

### 2.1 Render Hosts

| Host | Context | Source |
|------|---------|--------|
| SmartCapsuleCompose | MAIN_APP (in ChatScreen bottomBar) | In-app Compose |
| CapsuleOverlayHost | OTHER_APP, VD_VIEWER | System overlay window |
| IslandOverlayHost | OTHER_APP, VD_VIEWER (when pref=ISLAND) | System overlay window |
| GlowOverlayHost | OTHER_APP (A11y), VD_VIEWER (VD) | Decorative overlay |

All capsule rendering through shared `SmartCapsuleSurface`.

### 2.2 Per-Mode Rendering

| Mode | Dot | Thought | Body | Primary Btn | Stop/Close | Row3 |
|------|-----|---------|------|-------------|------------|------|
| Hidden | — | — | — | — | — | "What can I help you with?" / Send |
| Running | Blue | thought | — | Takeover | Stop | "Got ideas? Add a note..." / Add note |
| TakeoverPending | Amber | "Handing over..." | — | Handing over (disabled) | Stop | Add note |
| Takeover | Amber | thought (0.6α) | — | Resume | Stop | Add note |
| WaitingForInput | — | "💬 Awaiting response" | question | — | Stop | "Type your response..." / Send (auto-focus) |
| WaitingForAction | — | "✋ Action needed" | instruction | Done | Stop | — |
| Done | Teal | "✓ msg" | — | — | — | — |
| Error | Red | "⚠ msg" | — | — | Close | — |

### 2.3 Input Enablement

- MAIN_APP: always enabled
- A11y overlay + Running/TakeoverPending: **disabled** ("Take over to type note")
- All other overlay modes: enabled
- WaitingForInput: enabled + auto-focus + keyboard

### 2.4 NavSpec

| Platform | Context | ⊖ | 📱 | 👁 |
|----------|---------|---|---|---|
| A11y | MAIN_APP | no | no | no |
| A11y | OTHER_APP | **[OPEN QUESTION]** | no | no |
| VD | MAIN_APP | no | no | yes |
| VD | SCREEN_VIEWING | yes | yes | no |
| VD | BACKGROUND | yes | yes | yes |

(⊖ hidden in WI/WA/Error/Done regardless)

### 2.5 Chat History Side Effects

- TaskCompleted → always writes completion text (fallback: "Task completed")
- SupplementReceived → always writes user message
- Supplement → no mode/pref/location change, flash confirmation only

### 2.6 VD Viewer Touch Passthrough

- Only passes user touch to VD when mode is Takeover
- Otherwise consumed (user cannot interact with VD apps during Running)

---

## 3. Suggestions — Open Issues

### 3.1 FLAG_NOT_TOUCHABLE — P0 [AGREED, design TBD]

**Status**: Both Claude and Codex agree this is P0.

**Consensus**: Overlay capsule must be touchable in user-interaction modes.

**Approach options**:
- (a) Mode-driven derivation (Claude): touchable = mode in {Takeover, WI, WA, Error, Done}
- (b) Explicit OverlayInteractionMode enum (Codex): PASS_THROUGH vs INTERACTIVE

**Related sub-question**: Interaction lock during Running in A11y — user touches reach underlying app when FLAG_NOT_TOUCHABLE. Is this acceptable? [OPEN QUESTION for user]

### 3.2 A11y Island / ⊖ Policy — P1 [OPEN]

Current code allows A11y overlay to show ⊖ (minimize to island). Round6 design says no.

**Options**:
- (a) Keep ⊖ for A11y (current code): user can minimize capsule to island for screen space
- (b) Remove ⊖ for A11y (round6 design): capsule is always shown when active

[OPEN QUESTION for user]

### 3.3 UserResponse Path Asymmetry — P1 [AGREED]

**Consensus**: Main app path should call `onUserResponseSent(callId)` for immediate feedback, matching overlay behavior.

### 3.4 Interaction Lock During Running — P1 [BLOCKED on 3.1]

Interaction lock (full-screen touch-eating View) is non-functional due to FLAG_NOT_TOUCHABLE. Resolution depends on 3.1 touchability design.

### 3.5 resolveUserLocation Robustness — P2 [AGREED]

**Consensus**: Add trace log for "location change ignored" events. Low priority.

### 3.6 dismissError Routing — P2 [AGREED]

**Consensus**: Route through ServiceOverlayController for consistency. Low priority.

---

## Open Questions for User

1. **S1/S4**: During A11y Running, should user touches be blocked from reaching the underlying app? (Tension: blocking conflicts with dispatchGesture passthrough)
2. **S2**: Keep ⊖ minimize for A11y mode? (Current code: yes. Round6 design: no.)
3. **A11y investment level**: Is A11y the primary path or secondary to VD?
