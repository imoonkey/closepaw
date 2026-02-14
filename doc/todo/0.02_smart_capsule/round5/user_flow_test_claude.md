# Smart Capsule v2 — User Flow Test Plan

Date: 2026-02-13
Author: Claude (based on Round 4 codebase review)

## 1. Goal

Enumerate every user-facing interaction flow in the Smart Capsule v2 system, across all three rendering surfaces (Main App, A11y Overlay, VD mode). Each flow maps to a concrete state machine transition and has explicit pass/fail criteria.

This is not a "run a few demos" plan. It is a regression-grade test matrix where:
- Every CapsuleMode transition reachable at runtime has at least one flow covering it.
- Every interactive element (button, input, island) has at least one flow exercising it.
- Every rendering surface is tested independently for the flows it supports.

## 2. State Machine Reference

Source: `CapsuleStateHolder.kt` + `CapsuleMode.kt`

### 2.1 Modes (8 total)

| Mode | Data | Description |
|---|---|---|
| `Hidden` | — | No task. Capsule invisible (Main App: only Row3 input dock visible) |
| `Running` | `thought: String` | Agent executing. Blue pulsing dot. |
| `TakeoverPending` | `lastThought: String` | Takeover requested, waiting for agent to yield. Amber static dot. |
| `Takeover` | `lastThought: String` | Agent paused, user has control. Amber dot, dimmed thought. |
| `WaitingForInput` | `question, callId` | Agent asked a QUESTION. Expanded body shows question. "Send →" in Row3. |
| `WaitingForAction` | `instruction, callId` | Agent asked user to perform physical action. "✅ Done" button. No Row3. |
| `Done` | `message: String` | Task finished successfully. Teal dot. Auto-hides after 3s. |
| `Error` | `message: String` | Error occurred. Red dot. "✕ Close" button. Stays until dismissed. |

### 2.2 UI Elements per Mode

From `CapsuleRenderSpec.from()`:

| Mode | Dot | Row1 (thought) | Expanded Body | Primary Btn | Stop Btn | Row3 |
|---|---|---|---|---|---|---|
| Running | 🔵 pulse | thought text | — | ✋ Takeover | ⏹ Stop | hint="Got ideas? Add a note..." btn="Add note" |
| TakeoverPending | 🟡 static | "Handing over..." | — | ✋ Handing over (disabled) | ⏹ Stop | same as Running |
| Takeover | 🟡 static | lastThought (α=0.6) | — | ▶ Resume | ⏹ Stop | same as Running |
| WaitingForInput | — | "💬 Awaiting response" | question text | — | ⏹ Stop | hint="Type your response..." btn="Send →" |
| WaitingForAction | — | "✋ Action needed" | instruction text | ✅ Done | ⏹ Stop | — (hidden) |
| Done | 🟢 static | "✓ {message}" | — | — | — | — |
| Error | 🔴 static | "⚠ {message}" | — | — | ✕ Close | — |
| Hidden | — | "" | — | — | — | hint="What can I help you with?" btn="Send →" |

### 2.3 Content Descriptions (for ADB selectors)

From `SmartCapsuleLayoutBuilder.kt`:

| Element | `contentDescription` | Notes |
|---|---|---|
| Primary button | Dynamic: "Takeover" / "Handing over" / "Resume" / "Done" | Matches label text |
| Stop button | Dynamic: "Stop" / "Close" | Matches label text |
| Row1 container | "Open main app" | Only when `onRow1Tap` is set (overlay mode) |
| Nav: Minimize | "Minimize" | |
| Nav: Open App | "Open app" | |
| Nav: View Screen | "View screen" | |
| Status Island | None (⚠ gap) | StatusIslandManager does not set contentDescription |

### 2.4 Navigation Button Visibility

From `NavSpec.from()`:

| Context | Platform | Minimize | Open App | View Screen |
|---|---|---|---|---|
| MAIN_APP | A11y | ✗ | ✗ | ✗ |
| MAIN_APP | VD | ✗ | ✗ | ✗ |
| SCREEN_VIEWING | A11y | ✗ | ✓ | ✗ |
| SCREEN_VIEWING | VD | ✗ | ✓ | ✗ |
| BACKGROUND | A11y | ✗ | ✓ | ✓ |
| BACKGROUND | VD | ✓ | ✓ | ✓ |

## 3. Test Surfaces

| Surface | Renderer | Context | When visible |
|---|---|---|---|
| **Main App** | `SmartCapsuleCompose` | MAIN_APP | User is inside the Android Agent app |
| **A11y Overlay** | `SmartCapsuleManager` (View) | SCREEN_VIEWING | User left the app while task is active (A11y mode) |
| **VD: Status Island** | `StatusIslandManager` (View) | BACKGROUND | Agent on VD, user on real screen, capsule not expanded |
| **VD: Expanded Capsule** | `SmartCapsuleManager` (View) | SCREEN_VIEWING / BACKGROUND | User tapped island or capsule was auto-shown |

## 4. Test Environment

### 4.1 Commands

A11y mode baseline:
```bash
./scripts/setup.sh && ./scripts/debug-run.sh --basic "play a <singer> song on youtube"
```

VD mode baseline:
```bash
./scripts/setup.sh && ./scripts/debug-run.sh --basic --vd "play a <singer> song on youtube"
```

Singer rotation pool: Adele, Ed Sheeran, Bruno Mars, Taylor Swift, The Weeknd, Billie Eilish, Dua Lipa, BTS.

### 4.2 Discipline

- Each flow: at least 2 runs with different singers.
- A11y and VD tested independently.
- Every failure: record step, screenshot, logcat timestamp, observed CapsuleMode text.

---

## 5. User Flow Catalog

### Category A: Task Lifecycle

| ID | Priority | Surfaces | Flow | Transition | Pass Criteria |
|---|---|---|---|---|---|
| **A1** | P0 | Main | Send task from idle | Hidden → Running | Row3 "Send →" submits text. Row1+Row2 appear with blue pulsing dot, "✋ Takeover" and "⏹ Stop" visible. Row3 hint changes to "Got ideas? Add a note..." |
| **A2** | P0 | Main, A11y Overlay | Task completes successfully | Running → Done → Hidden | "✓ {message}" appears with teal dot. Both buttons hidden. Row3 hidden. After ~3s, capsule auto-hides. |
| **A3** | P0 | Main, A11y Overlay | Task completes with error | * → Error | "⚠ {message}" appears with red dot. Only "✕ Close" visible. No auto-hide. |
| **A4** | P0 | Main, A11y Overlay | User stops task | Running → Hidden | Tap "⏹ Stop". Capsule transitions to Hidden (no Done intermediate for USER_STOPPED). |
| **A5** | P1 | Main, A11y Overlay | Task impossible / max turns | Running → Done → Hidden | Done state with appropriate message ("Task impossible" / "Max steps reached"). Auto-hides. |

### Category B: Takeover & Resume

| ID | Priority | Surfaces | Flow | Transition | Pass Criteria |
|---|---|---|---|---|---|
| **B1** | P0 | Main, A11y Overlay | Takeover request | Running → TakeoverPending → Takeover | Tap "✋ Takeover". Immediately shows TakeoverPending ("Handing over...", disabled button, amber dot). When agent yields: Takeover state (dimmed thought α=0.6, "▶ Resume" button). |
| **B2** | P0 | Main, A11y Overlay | Resume after takeover | Takeover → Running | Tap "▶ Resume". Blue pulsing dot returns, thought shows "Thinking...", "✋ Takeover" re-enabled. |
| **B3** | P1 | Main, A11y Overlay | Stop during takeover | Takeover → Hidden | Tap "⏹ Stop" in Takeover state. Capsule hides cleanly. |
| **B4** | P1 | Main, A11y Overlay | Stop during pending | TakeoverPending → Hidden | Tap "⏹ Stop" while handing over. |

### Category C: AskUser (Agent → User)

| ID | Priority | Surfaces | Flow | Transition | Pass Criteria |
|---|---|---|---|---|---|
| **C1** | P0 | Main, A11y Overlay | AskUser QUESTION — answer | Running → WaitingForInput → Running | Row1 shows "💬 Awaiting response". Expanded body shows question text. Row3 changes to "Type your response..." / "Send →". User types + submits. Capsule returns to Running. |
| **C2** | P0 | Main, A11y Overlay | AskUser ACTION — complete | Running → WaitingForAction → Running | Row1 shows "✋ Action needed". Expanded body shows instruction. "✅ Done" button visible. Row3 hidden. Tap "✅ Done" → returns to Running. |
| **C3** | P1 | Main, A11y Overlay | Stop during WaitingForInput | WaitingForInput → Hidden | Keyboard should dismiss. Input cleared. |
| **C4** | P1 | Main, A11y Overlay | Stop during WaitingForAction | WaitingForAction → Hidden | Clean exit. |
| **C5** | P1 | A11y Overlay | Keyboard behavior in WaitingForInput | — | On entering WaitingForInput: overlay becomes focusable (`FLAG_NOT_FOCUSABLE` removed), EditText gets focus, keyboard shows automatically. On submitting/exiting: keyboard hides, overlay becomes non-focusable again. |

### Category D: Supplement (User → Agent, non-blocking)

| ID | Priority | Surfaces | Flow | Transition | Pass Criteria |
|---|---|---|---|---|---|
| **D1** | P0 | Main, A11y Overlay | Supplement during Running | Running (no mode change) | Type in Row3, tap "Add note". Flash confirmation "✓ Received, will apply next step" (if mid-turn) or "✓ Received" on thought line for ~1.5-2s. Original thought restores. Task is not interrupted. |
| **D2** | P1 | Main, A11y Overlay | Supplement during Takeover | Takeover (no mode change) | "Add note" still available. Text submits. No auto-resume triggered. |
| **D3** | P2 | Main, A11y Overlay | Empty supplement submit | — | Empty text → submit button does nothing (no crash, no empty supplement sent). |

### Category E: Visual Feedback

| ID | Priority | Surfaces | Flow | Transition | Pass Criteria |
|---|---|---|---|---|---|
| **E1** | P1 | Main, A11y Overlay | Thought text updates | Running → Running (new thought) | Row1 text updates as agent thinks. Single line, truncated with ellipsis if too long. |
| **E2** | P2 | A11y Overlay | Edge glow during execution | Running + EXECUTION phase | Edge glow changes to purple when agent is executing an action, back to blue when planning. |
| **E3** | P2 | VD: Island | Island text tracks thought | Running | Island shows first 24 chars of thought text. Truncated with "..." if longer. Dot color matches glow state. |
| **E4** | P1 | A11y Overlay | Entry/exit animation | show/hide | Capsule slides in from bottom on show. Slide-out animation on hide. No visual glitch. |
| **E5** | P2 | Main | Expand/collapse animation | Hidden ↔ Running, Running ↔ WaitingFor* | Row1+Row2 expand/collapse with animation. No layout jump. |

### Category F: VD-Specific Navigation

| ID | Priority | Surfaces | Flow | Transition | Pass Criteria |
|---|---|---|---|---|---|
| **F1** | P0 | VD: Island | Island appears on task start | Hidden → Running | Status Island appears at top-center of real screen. Shows compact pill with blue dot + truncated thought. |
| **F2** | P0 | VD: Island → Capsule | Island tap expands capsule | — | Tap island → full capsule overlay appears at bottom. Island hides. Full controls available. |
| **F3** | P0 | VD: Capsule → Island | Minimize capsule | — | Tap "⊖" (Minimize) → capsule hides, island reappears. No state change (still Running/Takeover/etc). |
| **F4** | P1 | VD: Capsule | Open viewer from capsule | — | Tap "👁" (View screen) → VD viewer opens. Context changes to SCREEN_VIEWING. Nav buttons update accordingly. |
| **F5** | P1 | VD: Capsule | Open main app from capsule | — | Tap "📱" (Open app) → Main app comes to foreground. Capsule hides (user now sees Compose capsule in main app). |
| **F6** | P1 | VD | AskUser auto-expands in VD | Running → WaitingForInput (while in BACKGROUND) | When agent asks question, capsule should auto-expand so user can respond. Island alone is not sufficient for text input. |
| **F7** | P1 | VD: Island | Island tap with no active task | Hidden | Tapping island when no task → should open main app (not dead-end). |
| **F8** | P1 | VD: Island | Island auto-hides on Done/Hidden | Done → Hidden | Island disappears when capsule mode becomes Hidden (after Done auto-hide or manual dismissal). |
| **F9** | P2 | VD: Capsule | Viewer close returns to island | — | Closing VD viewer → capsule hides, island reappears (if task still active). |

### Category G: A11y Overlay Navigation

| ID | Priority | Surfaces | Flow | Transition | Pass Criteria |
|---|---|---|---|---|---|
| **G1** | P1 | A11y Overlay | Row1 tap opens main app | — | Tap Row1 (thought line) with "Open main app" contentDescription → Android Agent app comes to foreground. |
| **G2** | P1 | A11y Overlay | Overlay appears on app leave | — | During Running task in A11y mode: press Home or switch app → overlay capsule appears on whatever screen user is on. |
| **G3** | P1 | A11y Overlay | Overlay hides on app return | — | Return to Android Agent main app → overlay hides (user sees Compose capsule instead). |
| **G4** | P1 | A11y Overlay | "Open app" nav button | — | Tap 📱 → same as G1. |

### Category H: Edge Cases & Stress

| ID | Priority | Surfaces | Flow | Transition | Pass Criteria |
|---|---|---|---|---|---|
| **H1** | P1 | Main, A11y Overlay | Rapid Takeover → Resume toggling | Running → Takeover → Running (repeat) | No crash, no stuck state. Each transition renders correctly. Debounce (300ms) prevents double-fire. |
| **H2** | P1 | Main, A11y Overlay | Error during Takeover | Takeover → Error | Error state overrides Takeover. "✕ Close" available. |
| **H3** | P2 | Main, A11y Overlay | Multiple AskUser in sequence | WaitingFor* → Running → WaitingFor* | Each transition clears previous input, shows new question/instruction. |
| **H4** | P2 | Main | Task complete during WaitingForInput | WaitingForInput → Done | If agent completes while user is typing, Done state overrides. No stuck input UI. |
| **H5** | P2 | A11y Overlay | Overlay survives activity recreation | — | Rotate device or config change → overlay remains visible and functional. |
| **H6** | P1 | VD | Rapid island/capsule/viewer toggle | — | Toggle between island ↔ capsule ↔ viewer rapidly. No "both hidden" dead state. No double-window. |
| **H7** | P2 | Main, A11y Overlay | Supplement flash during TakeoverPending | TakeoverPending | "✓ Received" flash does not interfere with "Handing over..." thought display. |
| **H8** | P2 | A11y Overlay | Nudge timer in WaitingFor* | WaitingForInput for >4 min | Expanded body appends "Still waiting for your response..." after NUDGE_DELAY_MS (4 min). |

---

## 6. Test Execution Matrix

### 6.1 A11y Mode (--basic)

Run command:
```bash
./scripts/setup.sh && ./scripts/debug-run.sh --basic "play a <singer> song on youtube"
```

Cover these flows per run:

| Phase | Flows | User Actions |
|---|---|---|
| 1. Task Start | A1 | Send from Main App input dock |
| 2. Running Observation | E1 | Watch thought updates in Main App and after switching away (overlay) |
| 3. Supplement | D1 | Type note in Row3, tap "Add note", watch flash confirmation |
| 4. Takeover/Resume | B1, B2 | Tap "✋ Takeover", observe pending → taken over. Tap "▶ Resume". |
| 5. AskUser (if triggered) | C1 or C2 | Wait for agent to ask. Respond. Verify return to Running. |
| 6. Overlay Navigation | G1, G2, G3 | Switch to Home, verify overlay. Tap Row1/📱, verify app opens. |
| 7. Completion | A2 | Wait for "✓" Done state. Verify auto-hide after ~3s. |
| 8. Error (if occurs) | A3 | Verify "⚠" + "✕ Close". Tap close. |

### 6.2 VD Mode (--basic --vd)

Run command:
```bash
./scripts/setup.sh && ./scripts/debug-run.sh --basic --vd "play a <singer> song on youtube"
```

Cover these flows per run:

| Phase | Flows | User Actions |
|---|---|---|
| 1. Task Start | A1, F1 | Send from Main App. Switch to home. Verify island appears. |
| 2. Island Interaction | F2, F3 | Tap island → capsule expands. Tap ⊖ → capsule hides, island returns. |
| 3. Capsule Controls | B1, B2, D1 | From expanded capsule: Takeover, Resume, Supplement. |
| 4. Navigation | F4, F5 | Tap 👁 → viewer. Tap 📱 → main app. |
| 5. AskUser in VD | F6 | If agent asks, verify capsule auto-expands for input. |
| 6. Completion | F8 | Verify island + capsule both hide after Done → Hidden. |
| 7. No-task island | F7 | After task ends, if island still visible, tap → should open main app. |

## 7. Exit Criteria

| Level | Requirement |
|---|---|
| **Minimum (ship-ready)** | All P0 flows pass in both A11y and VD. No crash. No permanently stuck state. |
| **Acceptable** | P0 all green + P1 fail ≤ 2 with known workarounds. |
| **Full regression** | All P0 + P1 pass. P2 documented but not blocking. |

Specific numbers:
- Each P0 flow passes in ≥ 2 consecutive runs (different singers).
- No "both hidden" dead state ever observed in VD mode.
- WaitingForInput/WaitingForAction always returns to Running after user action (not stuck).
- Auto-hide fires within 2.5s–4s of Done (accounting for timer precision).

## 8. Test Report Template

```
Run ID:      run_<timestamp>
Mode:        A11y | VD
Goal text:   "play a <singer> song on youtube"
Singer:      <name>
Build:       <git commit sha>

Flow Results:
- A1: PASS | FAIL
- A2: PASS | FAIL
- B1: PASS | FAIL
- B2: PASS | FAIL
...

Failures:
- Flow ID:
  - Step:
  - Expected:
  - Actual:
  - Evidence: <screenshot path> <logcat timestamp> <observed mode text>
  - Suspected layer: state holder | renderer | overlay controller | session
```

## 9. Recommended Execution Order

1. **A11y Core** (A1–A4, B1–B2, D1, E1): Get the basic lifecycle working.
2. **A11y AskUser** (C1–C5): Requires agent to trigger ask_user — may need specific prompts or mock.
3. **A11y Overlay** (G1–G4): Test surface switching.
4. **VD Navigation** (F1–F9): Test island/capsule/viewer interactions.
5. **Edge Cases** (H1–H8): Stress test after core flows are green.

## 10. Known Risks

1. **TakeoverPending is transient**: The TakeoverPending → Takeover transition depends on agent response time. If very fast, you may never visually see TakeoverPending. Accept this as "works correctly" if Takeover appears. To truly test TakeoverPending visuals, may need to add artificial delay or test with slow LLM.

2. **AskUser is agent-initiated**: C1/C2 depend on agent's decision to ask. Cannot be triggered on-demand. Options:
   - Use prompts likely to trigger ask_user (e.g., ambiguous instructions).
   - Add a debug/test mode that forces ask_user.
   - Test AskUser flows in isolation via instrumented state injection.

3. **`onUserResponseSent()` wiring**: The codex review flags that this may not be wired from the runtime event path. If WaitingForInput/WaitingForAction never returns to Running after user submit, this is a **P0 blocker**.

4. **StatusIsland lacks contentDescription**: No stable ADB selector for the island. Makes automated testing harder. Recommend adding `contentDescription = "Agent status"` to the island's pill view.
