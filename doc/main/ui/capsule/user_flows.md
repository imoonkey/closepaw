# User Flows — Location x Platform

> Overlay visibility, interaction rules, and key user flows by platform mode and user location.
> Last updated: 2026-02-22

## 1. Render Surfaces

1. `SmartCapsuleCompose` (Main App bottom bar)
2. `CapsuleOverlayHost` (system overlay capsule)
3. `IslandOverlayHost` (system overlay island)
4. `GlowOverlayHost` (decorative edge glow)

`SmartCapsuleSurface` is shared by in-app and overlay capsule rendering.

## 2. Capsule Mode UI Mapping

- `Hidden`: Row3 only (`What can I help you with?` + Send)
- `Running`: Row1 thought + Row2 (`Takeover`/`Stop`) + Row3 Add note
- `TakeoverPending`: Row1 `Handing over...` + Row2 (`Handing over` disabled / `Stop`) + Row3 Add note
- `Takeover`: Row1 paused thought (60% alpha) + Row2 (`Resume`/`Stop`) + Row3 Add note
- `WaitingForInput`: Row1 `Awaiting response` + Row2 (`Stop`) + Row3 expanded (question body + response input, auto-focus)
- `WaitingForAction`: Row1 `Action needed` + instruction body + Row2 (`Done`/`Stop`) + no Row3
- `Done`: Row1 only (`message`), then 3s auto-hide to Hidden
- `Error`: Row1 (`message`) + Row2 (`Close`) + no Row3

Input enablement:
- Main App: always enabled
- A11y overlay `Running|TakeoverPending`: disabled with hint `Take over to type note`
- Other modes: enabled

## 3. Location x Platform Flow

### 3.1 A11y + MAIN_APP

- System overlays hidden
- Only Compose capsule visible
- No nav icons (no minimize, no app, no viewer)

### 3.2 A11y + OTHER_APP

- Active (`Running/Takeover.../Waiting.../Done/Error`): overlay context active
- Capsule vs island depends on `showPreference`
- Glow shown while active
- Interaction locked: capsule expands to full-screen touch shield (unlocked in Takeover/Done/Error/Hidden)

### 3.3 VD + MAIN_APP

- System overlays hidden
- Compose capsule shown
- No Row3 viewer icon — VD viewer is only reachable via island tap or nav icon in overlay

### 3.4 VD + VD_VIEWER

- Active: capsule or island by `showPreference`
- `WaitingForInput|WaitingForAction|WaitingForApproval|Error`: forced to capsule
- Glow shown only when `hasActiveTask=true`
- Interaction locked: touch shield on (unlocked in Takeover/Done/Error/Hidden)
- Island tap in viewer toggles directly to capsule (no re-open viewer)

### 3.5 VD + OTHER_APP

- Active: capsule or island by `showPreference`
- `WaitingForInput|WaitingForAction|WaitingForApproval|Error`: forced to capsule
- Glow hidden in VD background
- Island tap behavior:
  - no active task and non-terminal: open Main App
  - active/terminal: open Viewer, then viewer lifecycle sets capsule preference

## 4. Key User Flows

1. **Start task from Hidden:**
   - Send input → `Op.UserInput` → `TaskStarted` → mode Running + showPreference CAPSULE

2. **Takeover/Resume:**
   - Takeover request gives immediate `TakeoverPending` feedback
   - `SessionTakeover` confirms to `Takeover` (after agent finishes current action)
   - Resume event returns to `Running("Thinking...")`

3. **Ask User flow:**
   - `ask_user` tool emits `AskUser`
   - mode enters `WaitingForInput` or `WaitingForAction`
   - visibility forces capsule
   - capsule becomes focusable for keyboard input (WaitingForInput)

4. **Supplement flow:**
   - Supplement does not change mode/location/preference
   - Chat appends user message on `SupplementReceived`
   - Capsule flash confirmation when overlay capsule is showing:
     - Between turns: "Received" (1500ms)
     - Mid-turn (`isAgentMidTurn=true`): "Received, will apply next step" (2000ms)

5. **Task completion flow:**
   - `TaskCompleted` maps to `Done`/`Error` per `TaskOutcome`
   - completion text always appended to chat (fallback `Task completed`)
   - `Done` auto-hides after 3s
   - Session transitions to `Idle` (Hot Idle) — lightweight state survives for follow-up

6. **Session follow-up (Hot Idle):**
   - After task completion, session stays in `Idle` state
   - User sends new input → platform re-acquired → new task starts
   - 5-minute idle timeout auto-shuts down session (`IDLE_TIMEOUT`)

## 5. Navigation and Special Behaviors

- Nav derivation is context-based (`NavSpec`)
- `Done` hides all nav buttons (Row2 hidden entirely)
- `WaitingForInput|WaitingForAction|WaitingForApproval|Error` hide minimize button
- A11y mode blocks app and viewer nav icons
- VD viewer: `showWatch` hidden when already in `SCREEN_VIEWING` context

## 6. Interaction Locking

When interaction is locked (A11y + OTHER_APP, VD + VD_VIEWER):
- Capsule layout expands to `MATCH_PARENT` as full-screen touch shield
- Running/TakeoverPending modes block user touch on underlying app
- Takeover mode unlocks interaction (user owns the screen)
- Done/Error/Hidden modes unlock interaction

Touch gate: during agent gesture injection, capsule temporarily becomes not-touchable via `OverlayTouchGate.beginGesturePassThrough()`, restoring after gesture completes.

## 7. Main App Visibility Convergence

`MainActivity` calls `onMainAppVisible()` in `onCreate/onStart/onResume/onNewIntent`.
This is an explicit convergence mechanism to enforce `MAIN_APP => no system overlays` even if accessibility window events are delayed.

## Related Docs

- [State Machine](state_machine.md) — formal state vector and transition rules
- [Overlay](../overlay.md) — rendering, overlay hosts, visual specs
- [User Interaction](../user_interaction.md) — in-app UI, event mapping, page layout
- [Session](../../infra/session.md) — session lifecycle, Hot Idle
