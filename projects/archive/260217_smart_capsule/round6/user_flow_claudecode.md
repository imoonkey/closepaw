# Smart Capsule User Flow Specification

Complete enumeration of all states, user flows, and expected component behavior.

Conventions:
- **Visible** = rendered on screen. **Hidden** = not rendered.
- **Overlay Capsule** = system-window SmartCapsuleManager (on top of any app)
- **Compose Capsule** = in-app SmartCapsuleCompose (embedded in ChatScreen bottomBar)
- **Island** = StatusIslandManager (compact status pill)
- **Glow** = EdgeGlowManager (screen-edge color effect)
- Row 1 = thought line; Row 2 = controls + nav; Row 3 = input field

---

## Platform A: Accessibility Mode

In A11y mode, the agent operates on the user's real screen. The agent can see and interact
with the foreground app. **There is no VD viewer.** **There is no Status Island.**

### A1. User Location: Main App

The user is viewing our app (ChatScreen). The **Compose Capsule** is the bottom bar.
All system overlays (Overlay Capsule, Glow) are **hidden** because our own UI handles everything.

#### A1.1 No Task (Hidden mode)

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: hidden. Row 2: hidden. Row 3 only: "What can I help you with?" + "Send" button. |
| Overlay Capsule | Hidden |
| Glow | Hidden |

**User actions:**
- Type + Send → starts new task → transitions to A1.2

#### A1.2 Running

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: blue dot (pulsing) + thought text. Row 2 left: [Takeover] [Stop]. Row 2 right: no nav buttons (all hidden in MAIN_APP/A11y). Row 3: "Got ideas? Add a note..." + "Add note" button. |
| Overlay Capsule | Hidden |
| Glow | Hidden |

**User actions:**
- Click [Takeover] → `Op.Takeover` sent. UI immediately transitions to A1.3 (TakeoverPending).
- Click [Stop] → `Op.Shutdown` sent. Agent stops after current turn. Transitions to A1.6 (Done).
- Type + [Add note] → supplement sent. Text cleared. No visual state change. Supplement queued for agent's next turn.

#### A1.3 TakeoverPending

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: amber dot + "Handing over...". Row 2 left: [Handing over] (disabled) [Stop]. Row 2 right: none. Row 3: "Got ideas? Add a note..." + "Add note" (can still type). |
| Overlay Capsule | Hidden |
| Glow | Hidden |

**User actions:**
- Wait → server confirms → transitions to A1.4 (Takeover).
- Click [Stop] → agent stops.
- Type + [Add note] → supplement sent (queued for after resume).

#### A1.4 Takeover

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: amber dot + last thought (alpha 0.6). Row 2 left: [Resume] [Stop]. Row 2 right: none. Row 3: "Got ideas? Add a note..." + "Add note". |
| Overlay Capsule | Hidden |
| Glow | Hidden |

**User actions:**
- Click [Resume] → `Op.Resume` sent. Transitions to A1.2 (Running).
- Type + [Add note] → supplement sent.
- Click [Stop] → agent stops.
- User can switch to another app (transitions to A2.4 Takeover in Other App).

#### A1.5 WaitingForInput

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: "Awaiting response" (no dot). Expanded body: question text. Row 2 left: [Stop]. Row 2 right: none. Row 3: "Type your response..." + "Send" button. Keyboard auto-opens. |
| Overlay Capsule | Hidden |
| Glow | Hidden |

**User actions:**
- Type + [Send] → `Op.UserResponse(callId, text)` sent. Transitions to A1.2 (Running).
- Click [Stop] → agent stops.

#### A1.6 WaitingForAction

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: "Action needed" (no dot). Expanded body: instruction. Row 2 left: [Done] [Stop]. Row 2 right: none. Row 3: hidden. |
| Overlay Capsule | Hidden |
| Glow | Hidden |

**User actions:**
- Click [Done] → `Op.UserResponse(callId, "done")` sent. Transitions to A1.2 (Running).
- Click [Stop] → agent stops.

#### A1.7 Done

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: teal dot + "completion message". Row 2: hidden. Row 3: hidden. Auto-transitions to A1.1 (Hidden) after 3 seconds. |
| Overlay Capsule | Hidden |
| Glow | Hidden |

**User actions:**
- Wait 3s → transitions to A1.1 (Hidden).

#### A1.8 Error

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: red dot + error message. Row 2 left: [Close]. Row 2 right: none. Row 3: hidden. |
| Overlay Capsule | Hidden |
| Glow | Hidden |

**User actions:**
- Click [Close] → dismisses error → transitions to A1.1 (Hidden).

---

### A2. User Location: Other App (Agent Operating on This Screen)

The user has left our app (e.g., Home, another app). The agent is running on and
controlling THIS screen. **Overlay Capsule + Glow are visible as system windows.**

**Transition from A1:** User presses Home or switches apps while a task is active.
**Transition to A1:** User opens our app (e.g., from recents).

#### A2.1 No Task

All overlays are hidden. User sees nothing from our app.

#### A2.2 Running

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: blue dot (pulsing) + thought text. NOT tappable (no Row1 tap in A11y). Row 2 left: [Takeover] [Stop]. Row 2 right: no nav buttons (A11y has none). Row 3: disabled (hint "Take over to type note"), unfocusable. Agent controls screen - no input conflict. |
| Glow | Active (blue, pulsing) |

**User actions:**
- Click [Takeover] → immediate visual: transitions to A2.3. `Op.Takeover` sent.
- Click [Stop] → agent stops.
- Tap Row 3 input → nothing (disabled). User must take over first.
- Switch to our app → transitions to A1.2.

#### A2.3 TakeoverPending

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: amber dot + "Handing over...". Row 2 left: [Handing over] (disabled) [Stop]. Row 3: disabled. |
| Glow | Paused (amber) |

**User actions:**
- Wait → server confirms → transitions to A2.4 (Takeover).
- Click [Stop] → agent stops.

#### A2.4 Takeover

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: amber dot + last thought (alpha 0.6). Row 2 left: [Resume] [Stop]. Row 3: ENABLED - user can type. "Got ideas? Add a note..." + "Add note". Focus allowed (agent is paused). |
| Glow | Paused (amber) |

**User actions:**
- Click [Resume] → transitions to A2.2 (Running). Row 3 re-disabled.
- Type + [Add note] → supplement sent. Text cleared. Keyboard hidden.
- Click [Stop] → agent stops.
- Switch to our app → transitions to A1.4.

#### A2.5 WaitingForInput

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: "Awaiting response". Expanded body: question. Row 2 left: [Stop]. Row 3: "Type your response..." + "Send". Input focused, keyboard opens. Overlay is focusable. |
| Glow | Paused (amber) |

**User actions:**
- Type + [Send] → response sent. Transitions to A2.2.
- Click [Stop] → agent stops.
- 4-minute nudge timer appends "Still waiting..." to body.

#### A2.6 WaitingForAction

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: "Action needed". Expanded body: instruction. Row 2 left: [Done] [Stop]. Row 3: hidden. |
| Glow | Paused (amber) |

**User actions:**
- Click [Done] → response sent. Transitions to A2.2.
- Click [Stop] → agent stops.

#### A2.7 Done

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: teal dot + message. Row 2: hidden. Row 3: hidden. Auto-hides after 3s → A2.1. |
| Glow | Success (teal), auto-hides after 2s |

#### A2.8 Error

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: red dot + error. Row 2 left: [Close]. Row 3: hidden. |
| Glow | Error (red) |

**User actions:**
- Click [Close] → transitions to A2.1. Overlays hide.

---

## Platform B: Virtual Display Mode

In VD mode, the agent operates on a hidden virtual display. The user's real screen is
unaffected by agent actions. The user can watch the agent work via VirtualDisplayViewerActivity.

Three user locations: Main App, Other App (background), VD Viewer.

### B1. User Location: Main App

The user is viewing our app (ChatScreen). **Compose Capsule** is the bottom bar.
All system overlays (Overlay Capsule, Island) are **hidden**.

#### B1.1 No Task (Hidden mode)

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: hidden. Row 2: hidden. Row 3 only: "What can I help you with?" + "Send" button. Row 2 right nav: showWatch=true (👁), showApp=false, showMinimize=false. |
| Island | Hidden |
| Overlay Capsule | Hidden |

**User actions:**
- Type + Send → starts new task → transitions to B1.2.
- Click 👁 → opens VD Viewer (shows virtual display content; no capsule until task starts).

**Note:** In Hidden mode, showWatch=true because platformMode=VD and context=MAIN_APP (not SCREEN_VIEWING). The 👁 button lets the user open the VD viewer even before starting a task. This is debatable but not harmful.

#### B1.2 Running

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: blue dot (pulsing) + thought. Row 2 left: [Takeover] [Stop]. Row 2 right: showWatch=true (👁). Row 3: "Got ideas? Add a note..." + "Add note". |
| Island | Hidden |
| Overlay Capsule | Hidden |

**User actions:**
- Click [Takeover] → transitions to B1.3.
- Click [Stop] → agent stops.
- Type + [Add note] → supplement sent.
- Click 👁 → opens VD Viewer → transitions to B3.2 (Running on viewer).
- Switch to other app → transitions to B2.2 (Running in background).

#### B1.3 TakeoverPending

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: amber dot + "Handing over...". Row 2 left: [Handing over] (disabled) [Stop]. Row 2 right: 👁. Row 3: input available. |
| Island | Hidden |

**User actions:**
- Wait → transitions to B1.4 (Takeover).
- Click 👁 → opens VD viewer.

#### B1.4 Takeover

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: amber dot + last thought (alpha 0.6). Row 2 left: [Resume] [Stop]. Row 2 right: 👁. Row 3: input available. |
| Island | Hidden |

**User actions:**
- Click [Resume] → transitions to B1.2.
- Type + [Add note] → supplement sent.
- Click 👁 → opens VD viewer → transitions to B3.4.

#### B1.5 WaitingForInput

Same pattern as A1.5 but with 👁 in Row 2 right.

#### B1.6 WaitingForAction

Same pattern as A1.6 but with 👁 in Row 2 right.

#### B1.7 Done

| Component | State |
|-----------|-------|
| Compose Capsule | Row 1: teal dot + message. Row 2: hidden. Row 3: hidden. Auto-hides to B1.1 after 3s. |
| Island | Hidden |

#### B1.8 Error

Same pattern as A1.8 but with 👁 in Row 2 right.

---

### B2. User Location: Other App (Background)

The user has left our app and is using another app. The agent operates on the virtual
display. The user sees **Status Island** (compact pill) by default, toggleable to
**Overlay Capsule** via minimize/expand actions.

**Default ShowPreference: ISLAND** (less intrusive while user is doing other things).

#### B2.1 No Task

All overlays hidden. User sees nothing from our app.

#### B2.2 Running (Island visible, default)

| Component | State |
|-----------|-------|
| Island | Visible. Dot: blue. Text: thought (max 24 chars). |
| Overlay Capsule | Hidden |

**User actions:**
- Tap Island → opens VD Viewer → transitions to B3.2 (where Overlay Capsule shows).

#### B2.2b Running (Capsule visible, user toggled)

This state is reached when user previously expanded the capsule from the VD Viewer and then
left the viewer (with capsule still showing). Or any other path that set ShowPreference=CAPSULE.

| Component | State |
|-----------|-------|
| Island | Hidden |
| Overlay Capsule | Row 1: blue dot + thought. NOT tappable (Row1 tap is VD-specific but questionable here). Row 2 left: [Takeover] [Stop]. Row 2 right: Minimize (⊖), App (📱), Watch (👁). Row 3: input available (VD mode allows input even during Running). |

**User actions:**
- Click ⊖ → ShowPreference=ISLAND → transitions to B2.2 (Island).
- Click 📱 → opens Main App → transitions to B1.2.
- Click 👁 → opens VD Viewer → transitions to B3.2.
- Click [Takeover] → transitions to B2.3b (TakeoverPending with capsule).
- Click [Stop] → agent stops.

#### B2.3 TakeoverPending (Island visible)

| Component | State |
|-----------|-------|
| Island | Dot: amber. Text: "Handing over..." |

#### B2.4 Takeover (Island visible)

| Component | State |
|-----------|-------|
| Island | Dot: amber. Text: "Paused" |

**User actions:**
- Tap Island → opens VD Viewer → B3.4 (Takeover on viewer with capsule). User can resume from there.

#### B2.5 WaitingForInput / WaitingForAction

When agent asks a question in VD background, the capsule MUST be shown (island doesn't
support text input). **ShowPreference is forced to CAPSULE** by `onAskUser()`.

| Component | State |
|-----------|-------|
| Island | Hidden |
| Overlay Capsule | Expanded with question/instruction. Input focused (WaitingForInput). |

**User actions:**
- Type + Send / Click Done → response sent. ShowPreference remains CAPSULE. Transitions back to B2.2b.

#### B2.6 Done (Island visible, default)

| Component | State |
|-----------|-------|
| Island | Dot: teal. Text: "Done: message..." |

Auto-hides after 3s (state → Hidden → B2.1 → island hides).

#### B2.7 Error (Island visible, default)

| Component | State |
|-----------|-------|
| Island | Dot: red. Text: "Error: message..." |

User taps Island → opens VD Viewer, where Overlay Capsule shows [Close] button.
But there's no way to dismiss error from just the Island...

**DESIGN GAP: Error in Island-visible mode has no dismiss mechanism without
opening the VD Viewer or Main App.**

---

### B3. User Location: VD Viewer

The user is watching the virtual display via VirtualDisplayViewerActivity. The **Overlay
Capsule** is shown as a system window on top of the viewer. The Island is **hidden**.

context = SCREEN_VIEWING. ShowPreference = CAPSULE.

#### B3.1 No Task

| Component | State |
|-----------|-------|
| Overlay Capsule | **Hidden** (isActive=false → overlay not shown) |
| Island | Hidden |

No overlay is visible. The VD Viewer shows virtual display content only.
The user must return to the Main App (back gesture, recents) to start a new task.

**User actions:**
- Swipe back / press Home → goes to other app or main app.

#### B3.2 Running

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: blue dot + thought. Row 1 tappable → opens Main App. Row 2 left: [Takeover] [Stop]. Row 2 right: Minimize (⊖), App (📱). (showWatch=false in SCREEN_VIEWING). Row 3: input available ("Got ideas? Add a note..." + "Add note"). |
| Island | Hidden |

**User actions:**
- Click [Takeover] → transitions to B3.3.
- Click [Stop] → agent stops.
- Type + [Add note] → supplement sent.
- Click ⊖ → ShowPreference=ISLAND, capsule hides, island shows → viewer remains foreground but island visible at top.
- Click 📱 → opens Main App → transitions to B1.2.
- Tap Row 1 → opens Main App → transitions to B1.2.
- Swipe back → goes to other app → B2.2 (island visible).

#### B3.3 TakeoverPending

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: amber dot + "Handing over...". Row 2 left: [Handing over] (disabled) [Stop]. Row 2 right: ⊖, 📱. Row 3: input available. |

#### B3.4 Takeover

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: amber dot + last thought (alpha 0.6). Row 2 left: [Resume] [Stop]. Row 2 right: ⊖, 📱. Row 3: input available. |

**User actions:**
- Click [Resume] → transitions to B3.2.
- Type + [Add note] → supplement sent. Capsule remains visible. **NO disappearing.**
- Click 📱 → opens Main App.
- Click ⊖ → minimize to island.

#### B3.5 WaitingForInput

| Component | State |
|-----------|-------|
| Overlay Capsule | Expanded body with question. Input focused, keyboard opens. Row 2 right: ⊖, 📱. |

#### B3.6 WaitingForAction

| Component | State |
|-----------|-------|
| Overlay Capsule | Expanded body with instruction. [Done] [Stop] buttons. Row 2 right: ⊖, 📱. |

#### B3.7 Done

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: teal dot + message. Auto-hides after 3s. |

After auto-hide, overlay capsule disappears. VD Viewer stays visible but with no capsule.

#### B3.8 Error

| Component | State |
|-----------|-------|
| Overlay Capsule | Row 1: red dot + error. [Close] button. |

**User actions:**
- Click [Close] → capsule hides. VD Viewer stays visible.

---

## Cross-Cutting Flows

### Flow X1: Island ↔ Capsule Toggle (VD Background)

This should be a reversible toggle:

1. **B2.2 (Island)** → Tap Island → opens VD Viewer → **B3.2 (Capsule)**.
2. **B3.2 (Capsule)** → Click ⊖ → Island shows → Island visible over VD Viewer.
3. **Island over VD Viewer** → Tap Island → Capsule shows over VD Viewer.

**Step 3 is where the current code has a bug.** In `onIslandTapped()`, VD mode path calls
`onOpenViewer?.invoke()`. If the user is ALREADY on the VD Viewer, this tries to re-open
the same activity. The VD Viewer may not re-trigger `onViewerOpened()` since it's already
foreground. Fix needed: detect that we're already on the viewer and just toggle
ShowPreference directly.

### Flow X2: Takeover → Supplement → Resume (A11y Overlay)

1. **A2.2 (Running)** → Click [Takeover] → **A2.3 (TakeoverPending)**.
2. Server confirms → **A2.4 (Takeover)**.
3. Row 3 input is now ENABLED. User types note, clicks [Add note].
4. Supplement sent. Capsule remains on Takeover mode. Confirmation flash: "Received".
5. User clicks [Resume] → **A2.2 (Running)**. Row 3 input re-disabled.

### Flow X3: Task Completion → Chat History

When `TaskCompleted` event arrives (any platform mode):
1. Compose Capsule shows Done state with teal dot + completion message.
2. ChatViewModel appends completion message to agent's content blocks.
3. **Even if event.result is null/blank, a completion message should appear** (e.g., "Task completed").
4. Chat banner shows "Task complete" and auto-hides.

### Flow X4: Supplement → Chat History

When user sends a supplement (any mode):
1. `Op.Supplement(text)` is sent to session.
2. Session emits `AgentEvent.SupplementReceived`.
3. ChatViewModel adds a user message: "message text" to chat history.
4. For overlay capsule: flash confirmation on thought line (A11y only; VD mode: no flash currently).

### Flow X5: VD Viewer Foreground Detection

When VD Viewer (VirtualDisplayViewerActivity) becomes foreground:
1. AccessibilityEvent fires with `packageName = "com.moonkey.androidagent"`.
2. `handleWindowStateChanged` sees our package → `isAppInForeground = true`.
3. `applyVisibility()` hides all system overlays.
4. BUT the VD Viewer is NOT the Main App. The Compose capsule is NOT available.
5. The user has NO capsule UI.

**This is a fundamental bug.** The VD Viewer must be treated differently from the Main App.
`isAppInForeground` should be true ONLY when the Main Activity is foreground, not when
the VD Viewer is foreground. The overlay capsule MUST remain visible on the VD Viewer.

---

## Bug Root Cause Analysis

### Bug 1: Island tap → capsule doesn't appear (VD mode)

**Root cause (dual):**
1. `onIslandTapped()` calls `onOpenViewer()` which launches VD Viewer. But VD Viewer sets
   `isAppInForeground=true` (same package), so `applyVisibility()` hides everything.
2. If user is ALREADY on VD Viewer, `onOpenViewer()` re-launches the same activity with no effect.

**Fix:** (a) Treat VD Viewer as "not our app" for overlay suppression. (b) In `onIslandTapped()`,
if already on VD Viewer, directly toggle ShowPreference to CAPSULE.

### Bug 2: Chat history missing completion message

**Root cause:** `ChatViewModel.handleTaskCompleted()` only appends text when `event.result`
is not null/blank. If the agent session sends `TaskCompleted` with null result, no message
appears in chat.

**Fix:** Always append a completion message. If result is blank, use a default like "Task completed".

### Bug 3: 📱 button does nothing (VD mode)

**Root cause:** The 📱 button's behavior depends on WHERE it's rendered:
- **Overlay Capsule:** Wired to `onOpenApp` → launches MainActivity. This should work IF the overlay is visible. But due to Bug 1, the overlay capsule is hidden on the VD Viewer, so the button can't be clicked.
- **Compose Capsule (Main App):** `NavAction.OPEN_APP` is NOT handled in ChatScreen's `onNavigate` lambda. Only `OPEN_VIEWER` is handled. But this button shouldn't even be visible in MAIN_APP context (NavSpec hides it). So this is a non-issue.

**True fix:** Fix Bug 1 so the overlay capsule shows on VD Viewer. Then 📱 button will work.

### Bug 4: VD Takeover → Add note → capsule disappears

**Root cause:** Same as Bug 1. The overlay capsule was never stably visible on the VD Viewer
in the first place. Due to foreground detection treating VD Viewer as "our app", the overlay
capsule gets hidden. Any subsequent state change or window event re-confirms this hiding.

**Fix:** Same as Bug 1 — treat VD Viewer as "not our app" for overlay visibility.

---

## Design Gaps Identified

### Gap G1: VD Viewer foreground detection

The visibility system has a binary `isAppInForeground` that is true for ANY activity in our
package. This is wrong — the VD Viewer needs overlay capsule while other "our app" activities
(MainActivity) use Compose capsule. Need a tri-state or activity-specific detection.

### Gap G2: Error dismissal from Island

When error occurs and only the Island is visible (B2.7), the user has no way to dismiss
the error without navigating to another location where the capsule is visible. The Island
only shows text, no buttons. Options:
- Auto-dismiss errors after a timeout (like Done)
- Tap Island while in Error mode → dismiss error (instead of opening viewer)
- Always force ShowPreference=CAPSULE for Error mode (like WaitingForInput)

Recommendation: Force CAPSULE for Error mode, same as WaitingForInput.

### Gap G3: Supplement flash feedback in VD mode

`onSupplementReceived()` only flashes confirmation in A11y mode. VD mode gets no feedback.
The overlay capsule should show the same "Received" flash in VD mode.

### Gap G4: onIslandTapped when already on VD Viewer

If the user minimized capsule to island while on VD Viewer, tapping the island should
toggle back to capsule. Currently it calls `onOpenViewer()` which is redundant (already on viewer).
Need to detect this case and toggle ShowPreference directly.

### Gap G5: Chat history - OPEN_APP from Compose Capsule

ChatScreen's `onNavigate` only handles `OPEN_VIEWER`. `OPEN_APP` is silently ignored.
In MAIN_APP context this button is hidden by NavSpec, so it's a latent issue only. But
MINIMIZE is also unhandled (though also hidden in MAIN_APP). These should either have
handlers or NavAction should document which actions are not applicable per context.

### Gap G6: Done/Error visibility window - capsule vs island

When task completes in VD background mode (B2.6/B2.7), the island shows "Done" or "Error"
text. The state machine auto-hides Done after 3s (→Hidden), which triggers `applyVisibility()`
to hide the island. This means the user has only a 3-second window to see completion status
on the island. This may be too brief. Consider:
- Longer timeout for island-only Done display?
- Or keep island visible until user taps it?

Currently acceptable but worth noting.

---

## Convergence Status

This user flow document and `state_machine_claudecode.md` have been cross-checked:

- **Every user flow** (A1.x, A2.x, B1.x, B2.x, B3.x, X1-X5) maps to valid state machine
  transitions (verified in state_machine Section 12).
- **Every state machine transition** (Section 2A/2B) is exercised by at least one user flow.
- **Visibility table** (Section 3) matches all component states described in user flows.
- **B3.1 correction**: VD Viewer with no task → overlay hidden (aligned with visibility table isActive=false).
- **7 code changes** (C1-C7 in state_machine Section 13) are required to make the current
  implementation match these specifications.
- **6 design gaps** (G1-G6) identified and addressed in state_machine Sections 4, 5, 8.

