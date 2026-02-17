# Smart Capsule Unified User Flow Specification

Date: 2026-02-14 (restart)
Purpose: Every state + every user action + every transition, exhaustively enumerated, directly testable.

---

## Conventions

- **Compose Capsule**: In-app bottom bar (ChatScreen). Only in MAIN_APP.
- **Overlay Capsule**: System window (SmartCapsuleManager). Outside MAIN_APP.
- **Island**: StatusIslandManager. Compact pill.
- **Glow**: EdgeGlowManager. Screen-edge color.
- **Row1**: Thought line (dot + text).
- **Row2-L**: Left action buttons (Takeover/Resume/Done/Close + Stop).
- **Row2-R**: Right nav buttons (Minimize ⊖, App 📱, Watch 👁).
- **Row3**: Input field + action button (Send / Add note).
- **visible/hidden**: Rendered or not rendered.
- **enabled/disabled**: Interactable or not (still rendered if visible).

---

## Part 1: State Catalog

Complete enumeration of all (PlatformMode, UserLocation, CapsuleMode, ShowPreference) states
with exact component visibility and rendering.

### 1A. Accessibility Mode

A11y has no Island, no VD_VIEWER. Only MAIN_APP and OTHER_APP.

#### A1: A11y + MAIN_APP

All system overlays (Overlay, Island, Glow) are ALWAYS hidden. Only Compose Capsule.

| ID | CapsuleMode | Compose Row1 | Compose Row2-L | Compose Row2-R | Compose Row3 | Input Focus |
|----|-------------|-------------|----------------|----------------|-------------|-------------|
| A1.H | Hidden | hidden | hidden | hidden | "What can I help you with?" + [Send] | enabled |
| A1.R | Running(thought) | blue-pulse dot + thought | [Takeover] [Stop] | hidden | "Got ideas? Add a note..." + [Add note] | enabled |
| A1.TP | TakeoverPending | amber dot + "Handing over..." | [Handing over](disabled) [Stop] | hidden | "Got ideas? Add a note..." + [Add note] | enabled |
| A1.T | Takeover(thought) | amber dot + thought (alpha 0.6) | [Resume] [Stop] | hidden | "Got ideas? Add a note..." + [Add note] | enabled |
| A1.WI | WaitingForInput(q, callId) | "Awaiting response" (no dot) + expanded question body | [Stop] | hidden | "Type your response..." + [Send]. Auto-focus, keyboard opens. | enabled + auto-focus |
| A1.WA | WaitingForAction(instr, callId) | "Action needed" (no dot) + expanded instruction body | [Done] [Stop] | hidden | hidden | N/A |
| A1.D | Done(msg) | teal dot + msg. Auto-hide 3s → A1.H | hidden | hidden | hidden | N/A |
| A1.E | Error(msg) | red dot + msg | [Close] | hidden | hidden | N/A |

**A11y + MAIN_APP invariants:**
- No 📱 (already in app)
- No 👁 (no VD in A11y)
- No ⊖ (no island in A11y)
- No system overlays ever

---

#### A2: A11y + OTHER_APP

Agent is controlling THIS screen. Overlay Capsule + Glow as system windows.

**When CapsuleMode is Hidden (no task): ALL overlays hidden. User sees nothing from our app.**

| ID | CapsuleMode | Overlay Row1 | Overlay Row2-L | Overlay Row2-R | Overlay Row3 | Glow | Input Focus |
|----|-------------|-------------|----------------|----------------|-------------|------|-------------|
| A2.H | Hidden | — (all hidden) | — | — | — | hidden | N/A |
| A2.R | Running(thought) | blue-pulse dot + thought. Row1 tap: **disabled** (null). | [Takeover] [Stop] | hidden (no nav in A11y) | visible but **DISABLED** (hint: "Take over to type note"). Not focusable. | blue, pulsing | disabled |
| A2.TP | TakeoverPending | amber dot + "Handing over..." | [Handing over](disabled) [Stop] | hidden | visible but **DISABLED** | amber | disabled |
| A2.T | Takeover(thought) | amber dot + thought (alpha 0.6). Row1 tap: disabled. | [Resume] [Stop] | hidden | **ENABLED**. "Got ideas? Add a note..." + [Add note]. Focusable (agent paused). | amber | **enabled** |
| A2.WI | WaitingForInput(q, callId) | "Awaiting response" + expanded question | [Stop] | hidden | **ENABLED** + auto-focus + keyboard. "Type your response..." + [Send]. Overlay is focusable. | amber | **enabled + auto-focus** |
| A2.WA | WaitingForAction(instr, callId) | "Action needed" + expanded instruction | [Done] [Stop] | hidden | hidden | amber | N/A |
| A2.D | Done(msg) | teal dot + msg. Auto-hide 3s → A2.H | hidden | hidden | hidden | teal, auto-hide 2s | N/A |
| A2.E | Error(msg) | red dot + msg | [Close] | hidden | hidden | red | N/A |

**A11y + OTHER_APP invariants:**
- No ⊖, no 📱, no 👁 (A11y has NO nav buttons)
- Row1 tap ALWAYS disabled (changing foreground disrupts agent)
- Row3 DISABLED during Running/TakeoverPending (focus conflict with agent's screen control)
- Row3 ENABLED during Takeover (agent paused, user has screen)
- Island NEVER shown (A11y has no island)

---

### 1B. Virtual Display Mode

VD has three locations: MAIN_APP, VD_VIEWER, OTHER_APP.
ShowPreference (ISLAND vs CAPSULE) matters when location is VD_VIEWER or OTHER_APP and task is active/terminal.

#### B1: VD + MAIN_APP

All system overlays (Overlay, Island) are ALWAYS hidden. Only Compose Capsule.
ShowPreference is irrelevant here.

| ID | CapsuleMode | Compose Row1 | Compose Row2-L | Compose Row2-R | Compose Row3 | Input Focus |
|----|-------------|-------------|----------------|----------------|-------------|-------------|
| B1.H | Hidden | hidden | hidden | 👁 (opens VD Viewer) | "What can I help you with?" + [Send] | enabled |
| B1.R | Running(thought) | blue-pulse dot + thought | [Takeover] [Stop] | 👁 | "Got ideas? Add a note..." + [Add note] | enabled |
| B1.TP | TakeoverPending | amber dot + "Handing over..." | [Handing over](disabled) [Stop] | 👁 | input available | enabled |
| B1.T | Takeover(thought) | amber dot + thought (alpha 0.6) | [Resume] [Stop] | 👁 | input available | enabled |
| B1.WI | WaitingForInput(q, callId) | "Awaiting response" + expanded question | [Stop] | 👁 | "Type your response..." + [Send]. Auto-focus. | enabled + auto-focus |
| B1.WA | WaitingForAction(instr, callId) | "Action needed" + expanded instruction | [Done] [Stop] | 👁 | hidden | N/A |
| B1.D | Done(msg) | teal dot + msg. Auto-hide 3s → B1.H | hidden | hidden | hidden | N/A |
| B1.E | Error(msg) | red dot + msg | [Close] | 👁 | hidden | N/A |

**VD + MAIN_APP invariants:**
- No ⊖ (no island in main app)
- No 📱 (already in app)
- 👁 visible in all modes EXCEPT Done (all Row2 hidden in Done)
- No system overlays ever

**Implementation note for B1.H 👁 placement:** In Hidden mode, Row1 and Row2-L are hidden. 👁 must still be accessible. It can be rendered as part of the "collapsed capsule" layout alongside Row3, or as a persistent icon in the chat screen header. The key constraint is: 👁 must be tappable when no task is running.

---

#### B2c: VD + OTHER_APP + ShowPreference=CAPSULE

Overlay Capsule visible. Island hidden. User is doing other things; agent works on VD.

| ID | CapsuleMode | Overlay Row1 | Overlay Row2-L | Overlay Row2-R | Overlay Row3 | Island | Input Focus |
|----|-------------|-------------|----------------|----------------|-------------|--------|-------------|
| B2c.H | Hidden | — (all hidden) | — | — | — | hidden | N/A |
| B2c.R | Running(thought) | blue-pulse dot + thought. Row1 tap → opens Main App. | [Takeover] [Stop] | ⊖ 📱 👁 | **ENABLED**. "Got ideas?..." + [Add note]. No focus conflict (agent on VD). | hidden | enabled |
| B2c.TP | TakeoverPending | amber dot + "Handing over..." | [Handing over](disabled) [Stop] | ⊖ 📱 👁 | enabled | hidden | enabled |
| B2c.T | Takeover(thought) | amber dot + thought (alpha 0.6) | [Resume] [Stop] | ⊖ 📱 👁 | enabled | hidden | enabled |
| B2c.WI | WaitingForInput(q, callId) | "Awaiting response" + expanded question | [Stop] | 📱 👁 (`⊖` hidden in WI) | **ENABLED** + auto-focus + keyboard. "Type your response..." + [Send]. | hidden | enabled + auto-focus |
| B2c.WA | WaitingForAction(instr, callId) | "Action needed" + expanded instruction | [Done] [Stop] | 📱 👁 (`⊖` hidden in WA) | hidden | hidden | N/A |
| B2c.D | Done(msg) | teal dot + msg. Auto-hide 3s → B2c.H | hidden | hidden | hidden | hidden | N/A |
| B2c.E | Error(msg) | red dot + msg | [Close] | 📱 👁 (`⊖` hidden in Error) | hidden | hidden | N/A |

---

#### B2i: VD + OTHER_APP + ShowPreference=ISLAND

Island visible. Overlay Capsule hidden. Default state when user leaves our app.

| ID | CapsuleMode | Island Dot | Island Text | Overlay | Force to CAPSULE? |
|----|-------------|-----------|-------------|---------|-------------------|
| B2i.H | Hidden | — (all hidden) | — | hidden | N/A |
| B2i.R | Running(thought) | blue | thought (max ~24 chars) | hidden | no |
| B2i.TP | TakeoverPending | amber | "Handing over..." | hidden | no |
| B2i.T | Takeover(thought) | amber | "Paused" | hidden | no |
| B2i.WI | WaitingForInput | — | — | — | **YES → B2c.WI** (need input UI) |
| B2i.WA | WaitingForAction | — | — | — | **YES → B2c.WA** (need Done button) |
| B2i.D | Done(msg) | teal | "Done: msg..." | hidden | no |
| B2i.E | Error(msg) | — | — | — | **YES → B2c.E** (need Close button) |

**Force CAPSULE rule:** When CapsuleMode enters WaitingForInput, WaitingForAction, or Error while ShowPreference=ISLAND, the system MUST force ShowPreference=CAPSULE and call applyVisibility(). This ensures the user has actionable UI (input field, Done button, Close button).

**Island tap behavior (B2i states):**
- Active task (Running/TakeoverPending/Takeover): opens VD Viewer → transition to B3c.* (corresponding mode)
- Done: opens VD Viewer → transition to B3c.D (or B3i.D)
- Hidden (no task): opens Main App → transition to B1.H

---

#### B3c: VD + VD_VIEWER + ShowPreference=CAPSULE

User is watching agent's virtual display. Overlay Capsule on top of viewer.

| ID | CapsuleMode | Overlay Row1 | Overlay Row2-L | Overlay Row2-R | Overlay Row3 | Island | Input Focus |
|----|-------------|-------------|----------------|----------------|-------------|--------|-------------|
| B3c.H | Hidden | — (all hidden) | — | — | — | hidden | N/A |
| B3c.R | Running(thought) | blue-pulse dot + thought. Row1 tap → opens Main App. | [Takeover] [Stop] | ⊖ 📱 (no 👁, already watching) | **ENABLED**. No focus conflict (agent on VD). | hidden | enabled |
| B3c.TP | TakeoverPending | amber dot + "Handing over..." | [Handing over](disabled) [Stop] | ⊖ 📱 | enabled | hidden | enabled |
| B3c.T | Takeover(thought) | amber dot + thought (alpha 0.6) | [Resume] [Stop] | ⊖ 📱 | enabled | hidden | enabled |
| B3c.WI | WaitingForInput(q, callId) | "Awaiting response" + expanded question | [Stop] | 📱 (`⊖` hidden in WI) | **ENABLED** + auto-focus + keyboard | hidden | enabled + auto-focus |
| B3c.WA | WaitingForAction(instr, callId) | "Action needed" + expanded instruction | [Done] [Stop] | 📱 (`⊖` hidden in WA) | hidden | hidden | N/A |
| B3c.D | Done(msg) | teal dot + msg. Auto-hide 3s → B3c.H | hidden | hidden | hidden | hidden | N/A |
| B3c.E | Error(msg) | red dot + msg | [Close] | 📱 (`⊖` hidden in Error) | hidden | hidden | N/A |

---

#### B3i: VD + VD_VIEWER + ShowPreference=ISLAND

User minimized capsule while on VD Viewer. Island visible at top, viewer still shows VD content.

| ID | CapsuleMode | Island Dot | Island Text | Overlay | Force to CAPSULE? |
|----|-------------|-----------|-------------|---------|-------------------|
| B3i.H | Hidden | — (all hidden) | — | hidden | N/A |
| B3i.R | Running(thought) | blue | thought (max ~24 chars) | hidden | no |
| B3i.TP | TakeoverPending | amber | "Handing over..." | hidden | no |
| B3i.T | Takeover(thought) | amber | "Paused" | hidden | no |
| B3i.WI | WaitingForInput | — | — | — | **YES → B3c.WI** |
| B3i.WA | WaitingForAction | — | — | — | **YES → B3c.WA** |
| B3i.D | Done(msg) | teal | "Done: msg..." | hidden | no |
| B3i.E | Error(msg) | — | — | — | **YES → B3c.E** |

**Island tap behavior (B3i states, already on VD Viewer):**
- Any active/terminal: **directly toggle ShowPreference=CAPSULE** → transition to B3c.* (NO re-launch of VD Viewer)
- Hidden: ShowPreference=CAPSULE → nothing visible (isActive=false, so applyVisibility hides everything)

---

## Part 2: Critical Flow Scenarios

Detailed step-by-step flows for bug-prone scenarios. Each step specifies exact state vector,
event, guard, new state, component changes, and side effects.

### Flow F1: VD Viewer Capsule ↔ Island Toggle (BUG: round6 #1)

**Goal:** Prove that capsule and island can be toggled back and forth indefinitely on VD Viewer.

**Precondition:** platform=VD, location=VD_VIEWER, mode=Running(thought), showPref=CAPSULE

**Step 1: User clicks ⊖ (minimize)**
- Event: onMinimize
- State change: showPref → ISLAND
- applyVisibility: capsule hidden, island shown
- Result: state=B3i.R (Island visible, dot blue, text=thought)
- Assertion: overlay capsule hidden, island visible, NOT both

**Step 2: User taps Island**
- Event: onIslandTapped
- Guard: location=VD_VIEWER (isViewerVisible=true)
- Action: showPref → CAPSULE (NO onOpenViewer call — already on viewer)
- applyVisibility: island hidden, capsule shown
- Result: state=B3c.R (Overlay capsule visible with full controls)
- Assertion: island hidden, overlay capsule visible, NOT both

**Step 3: User clicks ⊖ again**
- Same as Step 1. Reversible. Can repeat indefinitely.

**Bug prevention:** The key fix is that `onIslandTapped()` detects `isViewerVisible=true` and toggles ShowPreference directly instead of calling `onOpenViewer()`. This avoids the broken path where re-launching the same activity has no effect.

---

### Flow F2: VD Viewer Takeover + Add Note (BUG: round6 #4)

**Goal:** Prove that adding a supplement note during Takeover does not cause capsule to disappear.

**Precondition:** platform=VD, location=VD_VIEWER, mode=Running(thought), showPref=CAPSULE

**Step 1: User clicks [Takeover]**
- Event: TakeoverRequested
- Guard: mode is Running ✓
- New mode: TakeoverPending(thought)
- UI: amber dot, "Handing over...", disabled button
- Side effect: Op.Takeover sent

**Step 2: Server confirms**
- Event: TakeoverConfirmed
- Guard: mode is TakeoverPending ✓
- New mode: Takeover(thought)
- UI: amber dot, thought (alpha 0.6), [Resume] [Stop], Row3 enabled

**Step 3: User types "check the comments section" + clicks [Add note]**
- Event: handleRow3Submit
- Guard: mode is Takeover (Running/TakeoverPending/Takeover allowed for supplement)
- Action: onSupplement("check the comments section")
- Side effects:
  - Op.Supplement sent to session
  - Text cleared in Row3
  - Session emits SupplementReceived → ChatViewModel adds user message to chat history
  - Capsule flashes "Received" confirmation on thought line
- **NO mode change.** Mode stays Takeover(thought).
- **NO ShowPreference change.** Stays CAPSULE.
- **NO applyVisibility call** (no state dimension changed).
- Assertion: overlay capsule STILL visible. Island hidden. Mode still Takeover.

**Step 4: User clicks [Resume]**
- Event: Resumed
- Guard: mode is Takeover ✓
- New mode: Running("Thinking...")
- UI: blue-pulse dot, "Thinking...", [Takeover] [Stop]
- Assertion: overlay capsule still visible. Continuous operation.

**Bug prevention:** Supplement send does NOT trigger any visibility recalculation. The bug occurred because the overlay was never stably visible (VD Viewer was treated as MAIN_APP, hiding overlays). With VD_VIEWER detection fix, overlay is stably visible and supplement doesn't affect it.

---

### Flow F3: VD Viewer 📱 Button (BUG: round6 #3)

**Goal:** Prove that 📱 button on VD Viewer navigates to Main App.

**Precondition:** platform=VD, location=VD_VIEWER, mode=Running(thought), showPref=CAPSULE

**Step 1: User clicks 📱**
- Event: onOpenApp
- Action: launch MainActivity intent
- Location change: VD_VIEWER → MAIN_APP
  - handleWindowStateChanged detects our package + MainActivity className → isAppInForeground=true
  - onViewerClosed → showPref=ISLAND (viewer no longer foreground)
  - But location is MAIN_APP, so showPref doesn't matter
- applyVisibility: overlay hidden, island hidden (MAIN_APP rule)
- New state: B1.R (Compose Capsule visible in ChatScreen)
- Assertion: Compose capsule shows Running state. No system overlays.

**Bug prevention:** The bug occurred because overlay wasn't visible on VD Viewer (couldn't click 📱). With VD_VIEWER detection fix, overlay IS visible, button IS clickable, and clicking it navigates correctly.

---

### Flow F4: Task Completion → Chat History (BUG: round6 #2, round5 #3.4)

**Goal:** Prove that task completion ALWAYS produces a visible message in chat history.

**Scenario A: result is non-empty**
- Event: TaskCompleted(GOAL_ACHIEVED, "YouTube video found and playing")
- Mode: → Done("YouTube video found and playing")
- ChatViewModel: append "YouTube video found and playing" to agent message content blocks
- Chat history: completion text visible ✓

**Scenario B: result is null**
- Event: TaskCompleted(GOAL_ACHIEVED, null)
- Mode: → Done("Task completed") — use default text
- ChatViewModel: append "Task completed" to agent message content blocks
- Chat history: completion text visible ✓

**Scenario C: result is blank string**
- Event: TaskCompleted(GOAL_ACHIEVED, "")
- Mode: → Done("Task completed") — use default text
- ChatViewModel: append "Task completed" to agent message content blocks
- Chat history: completion text visible ✓

**Implementation rule:** `ChatViewModel.handleTaskCompleted()` MUST use `event.result?.takeIf { it.isNotBlank() } ?: "Task completed"`. Never skip appending.

---

### Flow F5: Supplement → Chat History (BUG: round5 #3.5)

**Goal:** Prove that add note produces a visible user message in chat history.

**Precondition:** Any platform, any active mode, user types "look at the second result"

**Step 1: handleRow3Submit**
- Action: onSupplement("look at the second result")
- Side effect: Op.Supplement sent

**Step 2: Session processes supplement**
- Session emits AgentEvent.SupplementReceived("look at the second result")
- AgentService receives event → calls overlayController.onSupplementReceived (flash confirmation)
- ChatViewModel receives event → inserts user message: "look at the second result" into chat history
- Assertion: chat history shows user message with "look at the second result"

**Implementation rule:** SupplementReceived handler in ChatViewModel MUST always add a user message to chat history. Flash confirmation MUST work in both A11y and VD modes.

---

### Flow F6: VD Task Completion — No App Launch (BUG: round5 #5.3)

**Goal:** Prove that task completion in VD mode does NOT launch any VD app to the real screen.

**Precondition:** platform=VD, location=VD_VIEWER, mode=Running, VD has YouTube open

**Step 1: TaskCompleted event**
- Event: TaskCompleted(GOAL_ACHIEVED, "Found the video")
- Mode: → Done("Found the video")
- Overlay: teal dot + "Found the video"
- VD state: YouTube stays open on virtual display (no change)
- Real screen: VD Viewer activity stays foreground (no change)
- **NO app launch intent.** No activity launch from VD to real screen.
- Side effect: chat history gets completion message

**Step 2: Auto-hide (3s)**
- Mode: → Hidden
- Overlay: hidden (isActive=false)
- VD state: YouTube still open on VD (unchanged)
- Real screen: VD Viewer still visible (no navigation change)
- User can swipe back or press Home to leave viewer

**Implementation rule:** TaskCompleted handler MUST NOT launch any intent to bring VD-hosted apps to the real screen. Virtual display content stays on virtual display. The only side effects are: mode transition + chat history update.

---

### Flow F7: VD Background → Island → Tap → Viewer (BUG: round5 #4.2)

**Goal:** Prove that tapping island in VD background opens VD Viewer (not overlay on main screen).

**Precondition:** platform=VD, location=OTHER_APP, mode=Running(thought), showPref=ISLAND

**Step 1: Island is visible**
- State: B2i.R (island dot blue, text=thought)
- Overlay capsule: hidden
- User sees compact pill on their screen

**Step 2: User taps island**
- Event: onIslandTapped
- Guard: isViewerVisible=false (NOT on viewer), hasActiveTask=true
- Action: onOpenViewer() → launches VirtualDisplayViewerActivity
- VD Viewer lifecycle triggers onViewerOpened()
- showPref → CAPSULE
- handleWindowStateChanged: our package + VDViewer className → isAppInForeground=false, location=VD_VIEWER
- applyVisibility: island hidden, overlay capsule shown
- New state: B3c.R (overlay capsule on top of VD Viewer)
- Assertion: user sees VD content with overlay capsule. NOT a wallpaper or empty screen.

**Bug prevention:** Island tap in VD mode MUST open VD Viewer, not show overlay capsule on current screen. The overlay capsule should only appear ON TOP of the VD Viewer.

---

### Flow F8: VD + MAIN_APP — No Status Island (BUG: round5 #1.4, #3.2)

**Goal:** Prove that Status Island never appears when user is in Main App.

**Precondition:** platform=VD, location=MAIN_APP, mode=Running(thought)

**Assertion at all times:**
- Compose Capsule: visible (shows Running state)
- Status Island: **hidden**
- Overlay Capsule: **hidden**

**Even after events like:**
- ThoughtUpdate → still no island
- TakeoverRequested → still no island
- TaskCompleted → still no island (Done state in compose, then Hidden)

**Implementation rule:** applyVisibility MUST check location=MAIN_APP as first condition and hide ALL system overlays. This is invariant regardless of CapsuleMode or ShowPreference.

---

### Flow F9: Compose Capsule Hidden State = Row3 Base (Single Component) (BUG: round5 #1.5, #3.3)

**Goal:** Prove that the idle input and the capsule Row3 are the SAME component, not separate.

**Precondition:** location=MAIN_APP, mode=Hidden

**Visible UI (by platform):**
- A11y MAIN_APP Hidden: Compose Capsule shows ONLY Row3 (`input + [Send]`).
- VD MAIN_APP Hidden: Compose Capsule shows Row3 (`input + [Send]`) + `👁` entry（同一组件内）。

共同约束：这仍然是**同一个 Compose Capsule 组件**，不是单独 input dock。

**Task starts:** TaskStarted event → mode=Running
- Row1 and Row2 EXPAND (animate in)
- Row3 changes: "What can I help you with?" → "Got ideas? Add a note...", [Send] → [Add note]
- All three rows are part of ONE widget

**Task completes:** Done → auto-hide → Hidden
- Row1 and Row2 COLLAPSE (animate out)
- Row3 changes back to: "What can I help you with?" + [Send]
- Continuous transition, NO flash/flicker, NO momentary empty state

**Implementation rule:** There MUST be only ONE input component. Hidden 态基础是 Row3；VD 主界面可在同组件内附加 `👁` 入口。禁止 separate input dock / separate capsule 双实现。

---

### Flow F10: Island ↔ Capsule Mutual Exclusivity (BUG: round5 #2.4, #2.5, #5.1)

**Goal:** Prove that island and capsule are NEVER simultaneously visible.

**Invariant:** At any point in time:
- `showOverlayCapsule && showStatusIsland` = FALSE (never both true)

**Enforcement in applyVisibility:**
```
if showPref == CAPSULE:
    capsule.show()
    island.hide()
elif showPref == ISLAND:
    capsule.hide()
    island.show()
```

**Order matters:** ALWAYS hide one before showing the other. Never have both visible even for a single frame.

**Scenarios that previously broke this:**
1. Status Island auto-appearing on new turn (now fixed: island appearance only via ShowPreference)
2. Both components visible on VD Viewer (now fixed: applyVisibility enforces mutual exclusion)

---

### Flow F11: A11y Row1 Tap Disabled (BUG: round5 #2.3)

**Goal:** Prove that in A11y mode, tapping Row1 or any "return to app" control does NOT change the foreground app.

**Invariant (A11y):**
- Row1 tap handler: null (disabled)
- No 📱 button
- No 👁 button
- No ⊖ button (no island to minimize to)

**Rationale:** Agent is controlling the real screen. Changing the foreground app disrupts the agent's perception and actions.

---

### Flow F12: Island Stuck "Working..." after Task End (BUG: round5 #5.4)

**Goal:** Prove that island correctly reflects task state transitions.

**Precondition:** platform=VD, location=OTHER_APP, mode=Running, showPref=ISLAND

**Step 1: Task completes**
- Event: TaskCompleted(GOAL_ACHIEVED, "Done")
- Mode: → Done("Done")
- Island: dot=teal, text="Done: Done"
- Mode stays Done for 3 seconds

**Step 2: Auto-hide**
- Mode: → Hidden
- isActive = false
- applyVisibility: island hidden (isActive=false → everything hidden)

**Assertion:** Island disappears after 3s. It does NOT stay stuck on "Working..." because the mode transition to Done/Hidden drives the island text update.

**Implementation rule:** Island text is DERIVED from CapsuleMode. It's not set independently. When mode changes to Done, island text changes. When mode changes to Hidden, island hides. There is no separate "island text" state variable — it's computed from `mode`.

---

### Flow F13: VD OTHER_APP → Click Island → Error: should not show empty/wallpaper (BUG: round5 #5.4 related)

**Goal:** Prove that tapping island when VD has no content doesn't show empty screen.

**Precondition:** platform=VD, location=OTHER_APP, mode=Hidden, VD has nothing open

**Step 1: Island tapped (but mode=Hidden, no active task)**
- Event: onIslandTapped
- Guard: !hasActiveTask && mode !is Done && mode !is Error
- Action: onOpenApp() → launch Main App (NOT VD Viewer)
- Rationale: no reason to show VD Viewer when there's no task. Go to main app instead.
- New state: B1.H

**If VD Viewer were opened instead:**
- User would see an empty virtual display (or desktop wallpaper)
- This is confusing UX
- Prevention: always open Main App when no active task

---

### Flow F14: WaitingFor* Response with callId Mismatch (Guard Integrity)

**Goal:** Prove that `UserResponseSent` must match current waiting callId; mismatch cannot exit waiting state.

**Precondition:** mode=WaitingForInput(callId=`abc-123`) or WaitingForAction(callId=`abc-123`)

**Step 1: User submits response with wrong callId (`wrong-999`)**
- Event: UserResponseSent(callId=`wrong-999`)
- Guard: callId mismatch
- Result: event ignored (debug log), mode remains WaitingForInput/WaitingForAction
- UI: question/instruction remains visible; input/button remains actionable

**Step 2: User submits response with correct callId (`abc-123`)**
- Event: UserResponseSent(callId=`abc-123`)
- Guard: callId match
- Result: mode → Running("Processing response...")

**Bug prevention:** 防止错误 wiring 或重复/串线事件导致 waiting 态提前退出，造成状态机闭环破裂。

---

### Flow F15: Stop Click Immediate Feedback (BUG: round5 #1.3)

**Goal:** Prove that clicking Stop gives immediate user feedback even before session termination event arrives.

**Precondition:** any visible Running/Takeover/Waiting mode with `[Stop]` button

**Step 1: User clicks [Stop]**
- Event: StopRequested (UI interaction)
- Result:
  - `CapsuleMode` 暂不变化（等待 session 终止事件）
  - UI 立即出现 transient feedback：`[Stopping...]` + disabled
  - 防重复点击

**Step 2: Session terminal event arrives**
- Event: TaskCompleted(USER_STOPPED) or SessionEnded(USER_STOPPED) or SessionError
- Result:
  - 进入 Done/Error/Hidden 终态路径
  - 清除 stop pending transient flag

**Assertion:** 用户在点击后 1 帧内就有“已接收 stop 请求”的可见反馈，不会出现“按钮点了没反应”的不确定感。

---

### Flow F16: Viewer Lifecycle / Window Event Reorder (Race Safety)

**Goal:** Prove that event order differences do not change final visibility outcome.

**Case A: `onViewerOpened()` first, `handleWindowStateChanged()` later**
- Final expected state: `location=VD_VIEWER`, `showPref=CAPSULE`, overlay visible, island hidden.

**Case B: `handleWindowStateChanged(VD_VIEWER)` first, `onViewerOpened()` later**
- Final expected state: same as Case A.

**Case C: leaving viewer to MAIN_APP with reversed callback order**
- Regardless of `onViewerClosed` vs `windowChanged(MainActivity)` 先后：
  - final expected: `location=MAIN_APP`
  - overlay/island hidden (Compose-only)

**Bug prevention:** 防止乱序导致瞬态卡死、错误 showPref 覆盖或“同一位置显示不同 UI”。

---

## Part 3: Location Transition Matrix

All valid UserLocation transitions and their triggers.

### A11y

| From | To | Trigger |
|------|----|---------|
| MAIN_APP | OTHER_APP | User presses Home, switches app, or agent navigates away |
| OTHER_APP | MAIN_APP | User opens our app from recents/launcher |

### VD

| From | To | Trigger | ShowPref Effect |
|------|----|---------|-----------------|
| MAIN_APP | OTHER_APP | User presses Home, switches to other app | (showPref doesn't change, was irrelevant in MAIN_APP) |
| MAIN_APP | VD_VIEWER | User clicks 👁 | showPref → CAPSULE (via onViewerOpened) |
| VD_VIEWER | OTHER_APP | User presses Home, swipes back | showPref → ISLAND (via onViewerClosed) |
| VD_VIEWER | MAIN_APP | User clicks 📱, taps Row1, or opens our app from recents | showPref → ISLAND (via onViewerClosed) |
| OTHER_APP | MAIN_APP | User opens our app from recents/launcher | (showPref doesn't change) |
| OTHER_APP | VD_VIEWER | User taps island (active task) or opens VD Viewer from recents | showPref → CAPSULE (via onViewerOpened) |

---

## Part 4: Prohibited Behaviors (Explicit)

These MUST NOT happen. If observed, it's an implementation bug.

| # | Prohibition | Related Bug |
|---|-------------|-------------|
| P1 | Status Island and Overlay Capsule both visible at the same time | round5 #2.4, #2.5, #5.1 |
| P2 | Any system overlay (island, capsule, glow) visible in MAIN_APP | round5 #1.4, #3.2 |
| P3 | A11y mode showing Status Island (ever) | round5 #2.4 |
| P4 | A11y overlay showing navigation buttons (📱, 👁, ⊖) | round5 #2.3 |
| P5 | A11y overlay Row3 focusable during Running/TakeoverPending | round5 #2.7 |
| P6 | TaskCompleted without chat history message | round6 #2, round5 #3.4 |
| P7 | Supplement sent without user message in chat history | round5 #3.5 |
| P8 | VD task completion launching app from VD to real screen | round5 #5.3 |
| P9 | Capsule disappearing during Takeover + Add note | round6 #4 |
| P10 | Island tap on VD Viewer re-launching viewer activity | round6 #1 |
| P11 | 📱 button having no effect on VD Viewer | round6 #3 |
| P12 | Island stuck on "Working..." after task ends | round5 #5.4 |
| P13 | Separate "input dock" component from capsule Row3 | round5 #1.5, #3.3 |
| P14 | No transition feedback on Takeover/Stop click | round5 #1.3 |
| P15 | VD overlay capsule appearing on main screen (non-VD-Viewer) when island is tapped | round5 #4.2 |
| P16 | callId mismatch still exits WaitingFor* state | latent high-risk |
| P17 | Stop click has no immediate visual feedback | round5 #1.3 |
| P18 | Lifecycle/window event reorder leads to different final visibility for same location | latent race-risk |
