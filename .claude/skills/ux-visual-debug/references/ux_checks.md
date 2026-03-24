# UX Review Checks

Use this checklist while reading `report.md` and step artifacts, or during AI-interactive testing.

## 1. Interaction Correctness

- Does each tap trigger the intended state change?
- Is text input accepted and visible where expected?
- Do disabled/unavailable actions provide clear feedback?

## 2. Transition Quality

- Does the app transition to the expected screen within acceptable delay?
- Are there visual jumps, blank frames, or stale state?
- Does back navigation return to the expected state?

## 3. State Clarity

- Can user identify current state without ambiguity?
- Are labels/button semantics consistent across states?
- Are Smart Capsule state changes understandable after actions like Takeover / Supplement?

## 4. Error Handling UX

- On action failure, is error message visible and actionable?
- Is user blocked without recovery path?
- Are retry/recover controls obvious?

## 5. Severity Rubric

- **P0**: Crash, freeze, cannot proceed.
- **P1**: Main flow broken or misleading enough to fail task completion.
- **P2**: Noticeable friction/confusion, workaround exists.
- **P3**: Minor polish/consistency issue.

## 6. Report Template

Use this format for each issue:

- Title
- Severity
- Step index/name
- Expected behavior
- Actual behavior
- Evidence paths (`.png`, `.xml`, `.txt`)
- Suggested fix direction

---

## 7. Smart Capsule Visual Verification Guide

When examining screenshots or visible text dumps, use this reference to determine the current `CapsuleMode` and verify correct behavior.

### 7.1 State Identification

| What to look for | Identified State |
|---|---|
| Blue pulsing dot + thought text + "Takeover" button | **Running** |
| Amber dot + "Handing over..." + disabled primary button | **TakeoverPending** |
| Amber dot + dimmed thought + "Resume" button | **Takeover** |
| "Awaiting response" + expanded question text + "Send" in Row3 | **WaitingForInput** |
| "Action needed" + expanded instruction + "Done" button | **WaitingForAction** |
| Teal dot + completion message + no buttons | **Done** |
| Red dot + error message + "Close" button | **Error** |
| Only input dock with "What can I help you with?" (Main App) | **Hidden** |
| No capsule visible at all (overlay mode) | **Hidden** |

### 7.2 Per-State Verification Checklist

#### Running

- [ ] Blue dot is pulsing (animated)
- [ ] Row1 shows thought text (updates as agent thinks)
- [ ] "Takeover" button visible and enabled in Row2
- [ ] "Stop" button visible in Row2
- [ ] Row3 shows input with hint "Got ideas? Add a note..."
- [ ] "Add note" button in Row3

#### TakeoverPending

- [ ] Amber dot, static (not pulsing)
- [ ] Row1 shows "Handing over..."
- [ ] Primary button shows "Handing over" and is DISABLED (not tappable)
- [ ] "Stop" button still available
- [ ] This state is transient — should transition to Takeover within seconds

#### Takeover

- [ ] Amber dot, static
- [ ] Row1 shows last thought text with reduced opacity (visually dimmed)
- [ ] "Resume" button visible and enabled
- [ ] "Stop" button visible
- [ ] Row3 still shows "Add note" input (supplements allowed during takeover)

#### WaitingForInput

- [ ] No status dot visible
- [ ] Row1 shows "Awaiting response"
- [ ] Expanded body visible with agent's question text
- [ ] Row3 shows input with hint "Type your response..."
- [ ] Row3 button shows "Send"
- [ ] In overlay mode: overlay should be focusable, keyboard may auto-appear
- [ ] No "Takeover" or "Resume" button (only "Stop")

#### WaitingForAction

- [ ] No status dot visible
- [ ] Row1 shows "Action needed"
- [ ] Expanded body visible with agent's instruction text
- [ ] "Done" button visible
- [ ] "Stop" button visible
- [ ] Row3 is HIDDEN (no text input)

#### Done

- [ ] Teal dot, static
- [ ] Row1 shows completion message (e.g., "Task completed successfully")
- [ ] No buttons visible (no Takeover, no Stop, no Close)
- [ ] Row3 hidden
- [ ] Should auto-hide after ~3 seconds (capsule disappears)

#### Error

- [ ] Red dot, static
- [ ] Row1 shows error message
- [ ] Only "Close" button visible (no Takeover/Resume/Stop)
- [ ] Does NOT auto-hide — persists until user taps Close
- [ ] After Close: transitions to Hidden

#### Hidden (Main App)

- [ ] No Row1 (thought line) visible
- [ ] No Row2 (buttons) visible
- [ ] Row3 shows input dock with "What can I help you with?" hint
- [ ] "Send" button in Row3

### 7.3 Supplement Flash Verification

When user submits a supplement ("Add note") during Running/Takeover:
- [ ] Row1 briefly shows "Received, will apply next step" (if agent mid-turn) or "Received"
- [ ] Flash lasts ~1.5-2 seconds
- [ ] Original thought text restores after flash
- [ ] Task is NOT interrupted by the supplement

### 7.4 VD (Virtual Display) Mode Checks

#### Status Island

- [ ] Compact pill appears at top-center of real screen during active task
- [ ] Shows truncated thought text (max ~24 chars) + status dot
- [ ] Dot color matches current state (blue=Running, amber=Takeover, etc.)
- [ ] contentDescription = "Agent status island" (for ADB selectors)
- [ ] Tapping island expands full capsule overlay
- [ ] Island hides when capsule is expanded
- [ ] Island reappears after capsule minimize
- [ ] Island disappears on Done → Hidden transition

#### VD Navigation

- [ ] Minimize button ("Minimize" desc): hides capsule, shows island
- [ ] Open App button ("Open app" desc): opens main app in foreground
- [ ] View Screen button ("View screen" desc): opens VD viewer
- [ ] No "both hidden" dead state (either island or capsule must be visible during active task)
- [ ] AskUser (WaitingForInput) auto-expands capsule for text input

### 7.5 A11y Overlay Checks

- [ ] Overlay capsule appears when user leaves the main app during active task
- [ ] Overlay hides when user returns to main app (Compose capsule takes over)
- [ ] Row1 tap ("Open main app" desc) brings main app to foreground
- [ ] "Open app" nav button works same as Row1 tap
- [ ] Overlay EditText becomes focusable in WaitingForInput state
- [ ] Overlay EditText returns to non-focusable when exiting WaitingForInput

### 7.6 Timing Reference

| Event | Expected Timing |
|---|---|
| Done → Hidden auto-hide | ~3 seconds |
| Supplement flash duration | ~1.5-2 seconds |
| TakeoverPending → Takeover | Depends on agent response (1-10s typical) |
| Button debounce | 300ms between clicks |
| Nudge timer (WaitingForInput) | ~4 minutes before "Still waiting..." appears |
