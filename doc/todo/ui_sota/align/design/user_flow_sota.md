# User Flow SOTA — Aligned Design (v2)

Date: 2026-02-20
Status: CODEX round update (code-truth baseline)

## 1. Runtime Premise

Current overlay capsule window is created with `FLAG_NOT_TOUCHABLE`.
So overlay capsule can render but cannot be directly interacted with by touch in current build.

Implication:
- Flow definitions below describe intended code path and visibility truth.
- Some overlay actions are currently not user-triggerable until touchability policy is adjusted.

## 2. Render Surfaces

1. `SmartCapsuleCompose` (Main App bottom bar)
2. `CapsuleOverlayHost` (system overlay capsule)
3. `IslandOverlayHost` (system overlay island)
4. `GlowOverlayHost` (decorative edge glow)

`SmartCapsuleSurface` is shared by in-app and overlay capsule rendering.

## 3. Capsule Mode UI Mapping

- `Hidden`: Row3 only (`What can I help you with?` + Send)
- `Running`: Row1 thought + Row2 (`Takeover`/`Stop`) + Row3 Add note
- `TakeoverPending`: Row1 `Handing over...` + Row2 (`Handing over` disabled / `Stop`) + Row3 Add note
- `Takeover`: Row1 paused thought + Row2 (`Resume`/`Stop`) + Row3 Add note
- `WaitingForInput`: Row1 + question body + Row2 (`Stop`) + Row3 response input (auto-focus)
- `WaitingForAction`: Row1 + instruction body + Row2 (`Done`/`Stop`) + no Row3
- `Done`: Row1 only, then 3s auto-hide to Hidden
- `Error`: Row1 + Row2 (`Close`) + no Row3

Input enablement:
- Main App: always enabled
- A11y overlay `Running|TakeoverPending`: disabled with hint `Take over to type note`
- Other modes: enabled

## 4. Location × Platform Flow (Code Reality)

### 4.1 A11y + MAIN_APP

- System overlays hidden
- Only Compose capsule visible
- No 📱/👁; ⊖ not shown in MAIN_APP context

### 4.2 A11y + OTHER_APP

- Active (`Running/Takeover.../Waiting.../Done/Error`): overlay context active
- Capsule vs island depends on `showPreference` (code allows both branches)
- Glow shown while active
- Row1 tap disabled in A11y overlay

Note:
- Code currently allows A11y island path if preference is `ISLAND`.
- Whether this should remain is a policy decision (see suggestions).

### 4.3 VD + MAIN_APP

- System overlays hidden
- Compose capsule shown
- No Row3 viewer icon — VD viewer is only reachable via Row1 nav 👁 (active modes) or island tap

### 4.4 VD + VD_VIEWER

- Active: capsule or island by `showPreference`
- `WaitingForInput|WaitingForAction|Error`: forced to capsule
- Glow shown only when `hasActiveTask=true`
- Island tap in viewer toggles directly to capsule (no re-open viewer)

### 4.5 VD + OTHER_APP

- Active: capsule or island by `showPreference`
- `WaitingForInput|WaitingForAction|Error`: forced to capsule
- Glow hidden in VD background
- Island tap behavior:
  - no active task and non-terminal: open Main App
  - active/terminal: open Viewer then viewer lifecycle sets capsule preference

## 5. Key User Flows

1. Start task from Hidden:
- Send input -> `Op.UserInput` -> `TaskStarted` -> mode Running + showPreference CAPSULE

2. Takeover/Resume:
- Takeover request gives immediate `TakeoverPending` feedback
- `SessionTakeover` confirms to `Takeover`
- Resume event returns to `Running("Thinking...")`

3. Ask User flow:
- `ask_user` tool emits `AskUser`
- mode enters `WaitingForInput` or `WaitingForAction`
- visibility forces capsule

4. Supplement flow:
- Supplement does not change mode/location/preference
- Chat appends user message on `SupplementReceived`
- Capsule flash confirmation when overlay capsule is showing

5. Task completion flow:
- `TaskCompleted` maps to `Done`/`Error`
- completion text always appended to chat (fallback `Task completed`)
- `Done` auto-hides after 3s

## 6. Navigation and Special Behaviors

- Nav derivation is context-based (`NavSpec`)
- `Done` hides all nav buttons
- `WaitingForInput|WaitingForAction|Error` hide ⊖
- A11y blocks 📱/👁, but ⊖ can be permitted by current code path when not in MAIN_APP
- VD viewer touch forwarding to virtual display is only allowed in `Takeover` mode

## 7. Main App Visibility Convergence

`MainActivity` calls `onMainAppVisible()` in `onCreate/onStart/onResume/onNewIntent`.
This is an explicit convergence mechanism to enforce `MAIN_APP => no system overlays` even if accessibility window events are delayed.
