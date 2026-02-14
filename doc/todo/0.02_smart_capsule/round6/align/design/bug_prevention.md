# Bug Prevention Matrix

Maps every historical bug to the design rule(s) that prevent it, with verification method.

## Source: round5/qi_bug_note.md

### 1.3 [P2] No transition feedback on Takeover/Stop click

**Root cause:** UI doesn't change until server responds, leaving user unsure if click registered.

**Prevention rules:**
- `TakeoverRequested` event is emitted IMMEDIATELY on Takeover click (user-driven, not server-driven)
- State machine: Running → TakeoverPending is an IMMEDIATE transition (no server round-trip)
- TakeoverPending renders: amber dot, "Handing over...", [Handing over](disabled)
- Stop click也要有即时反馈（transient，不改主状态）：
  - [Stop] 立即显示为 [Stopping...] 并 disabled
  - 等待 `TaskCompleted/SessionEnded/SessionError` 任一终止事件后清除
- 这样 takeover/stop 都有 instant feedback，不再“点了没反应”

**Verification:**
- Test that clicking [Takeover] instantly changes mode to TakeoverPending with amber UI.
- Test that clicking [Stop] shows "Stopping..." within one frame and disables repeat clicks until terminal event.

**Design ref:** user_flow.md A1.R → A1.TP, user_flow.md Flow F15, design.md Section 2.1, Section 18

---

### 1.4 [P2] Status Island visible in MAIN_APP

**Root cause:** Island visibility not gated on location. Island shows regardless of user being in main app.

**Prevention rules:**
- Visibility rule: `surface == MAIN_APP → showStatusIsland = false` (absolute, no exceptions)
- user_flow.md Part 4 Prohibition P2
- design.md Section 3 rule: "MAIN_APP 永远不显示系统 overlay"

**Verification:** Test applyVisibility with location=MAIN_APP, assert showStatusIsland=false for all modes.

**Design ref:** design.md Section 3, user_flow.md P2

---

### 1.5 [P1] Input dock separate from Smart Capsule Row3

**Root cause:** Idle-state input and active-state capsule Row3 are two different components with different implementations.

**Prevention rules:**
- user_flow.md Flow F9: Compose Capsule Hidden mode 以 Row3 为基础，不允许 separate "input dock"。
- VD MAIN_APP Hidden 可在同组件内附加 `👁` 入口（不是第二组件）
- Implementation: ONE widget with three rows, Hidden mode hides Row1+Row2 (except allowed `👁` entry in VD main app)
- Transition: Running → Done → Hidden is animated collapse of Row1+Row2, NO flash or separate component swap

**Verification:** Code review: search for separate input dock component. Must not exist. Capsule widget handles all modes.

**Design ref:** user_flow.md Flow F9, design.md Section 12, P13

---

### 2.3 [bad] Return-to-app button in A11y overlay

**Root cause:** Navigation buttons (📱, 👁) rendered in A11y overlay despite being dangerous.

**Prevention rules:**
- NavSpec: A11y + ANY location → ⊖=no, 📱=no, 👁=no
- Row1 tap in A11y: null (disabled)
- Rationale: agent controls real screen, changing foreground disrupts agent

**Verification:** Test NavSpec computation for platform=A11y, assert all nav buttons false.

**Design ref:** design.md Section 4, user_flow.md P4, Flow F11

---

### 2.4/2.5 [P1] Island and Capsule simultaneously visible

**Root cause:** Island and Capsule visibility not mutually exclusive. Island auto-shows on new turn.

**Prevention rules:**
- design.md constraint: "OverlayCapsule 与 StatusIsland 永远互斥"
- applyVisibility: ALWAYS hide one before showing the other
- Island visibility driven by ShowPreference, NOT by turn start event
- user_flow.md P1

**Verification:** Test applyVisibility for all (showPref, mode) combinations — assert never both true.

**Design ref:** design.md Section 1, user_flow.md P1, Flow F10

---

### 2.6/2.7 [P0] Agent sees Smart Capsule UI / Focus conflict

**Root cause:** Smart Capsule overlay nodes appear in accessibility tree that agent perceives. Keyboard focus conflicts.

**Prevention rules (focus conflict):**
- A11y overlay Row3: DISABLED during Running/TakeoverPending
- Row3 ENABLED only during Takeover (agent paused) or WaitingForInput (agent waiting for input)
- design.md Section 8 Input Focus Policy

**Prevention rules (agent seeing capsule):**
- Out of scope for UI state machine, but referenced:
- Overlay windows should have `importantForAccessibility = NO` or equivalent filtering
- Agent's perception pipeline should exclude our package's overlay windows

**Verification:** Test Row3 enabled/disabled per mode in A11y overlay. Separate test needed for a11y tree filtering.

**Design ref:** design.md Section 8, user_flow.md A2 table

---

### 3.3 [P1] Weird rendering on task completion (flash separate component)

**Root cause:** Task end briefly renders capsule with only Row1+Row2, then swaps to separate input dock.

**Prevention rules:**
- Same as 1.5: ONE component, Hidden mode uses Row3 base (VD main app may include same-component `👁` entry)
- Done → Hidden transition: Row1 shows done message for 3s, then Row1+Row2 collapse, leaving Row3
- No intermediate state with "only Row1+Row2 and no Row3"
- Done mode: Row1=teal+msg, Row2=hidden, Row3=hidden. After 3s → Hidden: Row1=hidden, Row2=hidden, Row3=visible.

**Verification:** Test Done → Hidden transition renders correctly with no intermediate flash.

**Design ref:** user_flow.md A1.D → A1.H, Flow F9

---

### 3.4 [P1] complete_task not showing in chat history

**Root cause:** ChatViewModel.handleTaskCompleted skips appending when result is null/blank.

**Prevention rules:**
- user_flow.md Flow F4: TaskCompleted MUST produce chat message regardless of result content
- Implementation: `result?.takeIf { it.isNotBlank() } ?: "Task completed"`
- design.md Section 5 side effects contract #1

**Verification:** Test ChatViewModel.handleTaskCompleted with null, blank, and non-empty result. All must produce visible message.

**Design ref:** design.md Section 5, user_flow.md Flow F4, P6

---

### 3.5 [P2] Add note not showing as user message in chat history

**Root cause:** SupplementReceived event handler missing or not writing to chat history.

**Prevention rules:**
- user_flow.md Flow F5: SupplementReceived MUST insert user message into chat history
- design.md Section 5 side effects contract #2

**Verification:** Test ChatViewModel supplement handler. Assert user message appears.

**Design ref:** design.md Section 5, user_flow.md Flow F5, P7

---

### 4.2 [P2] VD background: clicking status island shows overlay on main screen

**Root cause:** Island tap shows overlay capsule on the current screen instead of navigating to VD Viewer.

**Prevention rules:**
- onIslandTapped in VD + OTHER_APP: MUST open VD Viewer first, then show capsule on viewer
- Island tap does NOT just toggle ShowPreference — it opens the viewer
- user_flow.md Flow F7

**Verification:** Test onIslandTapped with location=OTHER_APP. Assert it calls onOpenViewer.

**Design ref:** design.md Section 2.2, user_flow.md Flow F7, P15

---

### 5.1 [P1] Island and capsule simultaneously visible on VD Viewer

**Root cause:** Same as 2.4/2.5 but on VD Viewer. Island re-appears on turn start.

**Prevention rules:** Same as 2.4/2.5. Mutual exclusion via applyVisibility.

**Design ref:** Same as 2.4/2.5

---

### 5.2 [P2] 📱 button does nothing on VD Viewer

**Root cause (dual):**
1. Overlay capsule not visible on VD Viewer (VD Viewer treated as MAIN_APP → overlays hidden)
2. Even if visible, button handler might not be wired

**Prevention rules:**
- VD_VIEWER detection: handleWindowStateChanged distinguishes VD Viewer from Main App
- VD_VIEWER → overlay capsule visible (with CAPSULE ShowPreference)
- 📱 wired to onOpenApp → launches MainActivity
- user_flow.md Flow F3

**Verification:** Test that overlay is visible on VD_VIEWER. Test that 📱 click launches MainActivity.

**Design ref:** design.md Section 6, user_flow.md Flow F3, P11

---

### 5.3 [P1] Task completion launches VD app to main screen

**Root cause:** TaskCompleted handler or session end handler launches the VD-hosted app on the real screen.

**Prevention rules:**
- TaskCompleted handler: NO intent launch. Only mode transition + chat history update.
- user_flow.md Flow F6: "NO app launch intent. No activity launch from VD to real screen."
- VD content stays on VD. Period.

**Verification:** Test TaskCompleted handler. Assert no startActivity calls.

**Design ref:** user_flow.md Flow F6, P8

---

### 5.4 [P2] Status island stuck "Working..." / shows wallpaper on tap

**Root cause:** Island text not updating on mode transition. Or island visible when no task (isActive=false should hide).

**Prevention rules:**
- Island text derived from CapsuleMode (not set independently)
- isActive=false → all overlays hidden
- user_flow.md Flow F12, Flow F13

**Verification:** Test island text derivation for each mode. Test isActive=false hides island.

**Design ref:** user_flow.md Flow F12, Flow F13, P12

---

## Source: round6/qi_note.md

### round6 #1: VD Viewer capsule → island → tap island → capsule doesn't appear

**Root cause:** onIslandTapped calls onOpenViewer when already on VD Viewer. Re-launch has no effect.

**Prevention rules:**
- onIslandTapped: detect isViewerVisible. If true, directly toggle ShowPreference=CAPSULE. No onOpenViewer call.
- user_flow.md Flow F1

**Verification:** Test onIslandTapped with isViewerVisible=true. Assert ShowPreference changes to CAPSULE without onOpenViewer call.

**Design ref:** design.md Section 2.2, user_flow.md Flow F1, P10

---

### round6 #2: complete_task message not in chat history

Same as round5 #3.4 above.

---

### round6 #3: 📱 button does nothing on VD Viewer

Same as round5 #5.2 above.

---

### round6 #4: VD takeover + add note → capsule disappears

**Root cause:** VD Viewer treated as MAIN_APP, overlay never stably visible. State change re-triggers applyVisibility which hides overlay.

**Prevention rules:**
- VD_VIEWER detection fix (overlay stably visible)
- Supplement does NOT change CapsuleMode, ShowPreference, or UserLocation
- No applyVisibility call on supplement
- user_flow.md Flow F2

**Verification:**
1. Test that overlay IS visible on VD_VIEWER
2. Test that supplement handler does not call applyVisibility
3. Test that mode stays Takeover after supplement

**Design ref:** user_flow.md Flow F2, P9

---

## Additional latent high-risk cases (not yet reported, must preempt)

### L1: WaitingFor* exits on wrong callId

**Root cause:** `UserResponseSent` handler ignores callId matching and always resumes Running.

**Prevention rules:**
- `UserResponseSent` only valid in WaitingForInput/WaitingForAction.
- `callId` mismatch → silently ignore (debug log), stay in waiting state.
- only matching callId can transition to Running("Processing response...").

**Verification:** user_flow.md Flow F14

**Design ref:** design.md Section 2.1 guarded events, user_flow.md P16

---

### L2: Viewer lifecycle / window callback reorder causes divergent UI

**Root cause:** `onViewerOpened/onViewerClosed` 与 `handleWindowStateChanged` 乱序时，多个写路径竞争导致最终可见性不一致。

**Prevention rules:**
- `applyVisibility` pure + idempotent（同状态向量必同结果）
- `UserLocation` 最终收敛为单一真值
- `showPreference` 只能由 design.md 2.2 列出的事件更新
- 乱序下最终状态必须一致（race-safe）

**Verification:** user_flow.md Flow F16

**Design ref:** design.md Section 19, user_flow.md P18

---

### L3: WI/WA/Error 时 ShowPreference 被覆盖为 ISLAND → 丢失交互 UI

**Root cause:** `onAskUser`/`onError` 的 force-CAPSULE 是事件驱动，后续事件（`onMinimize(⊖)` 或 `onViewerClosed`）可将 `showPref` 改回 `ISLAND`。Island 没有输入框/[Done]/[Close]，用户无法操作。

**场景举例：**
1. 用户在 B2c.WI（OTHER_APP + CAPSULE + WaitingForInput）点击 ⊖ → showPref=ISLAND → 只有 Island，没有输入框。
2. 用户在 B3c.WI（VD_VIEWER + CAPSULE + WaitingForInput）按 Home → onViewerClosed → showPref=ISLAND → B2i.WI（Island only，无法回答问题）。

**Prevention rules:**
- `applyVisibility()` 包含 state-invariant force：`mode in {WI, WA, Error} && showPref==ISLAND → force CAPSULE`
- 此 guard 确保 `user_flow.md` B2i/B3i 表的 "Force to CAPSULE" 不变量始终成立
- 在 WI/WA/Error 期间隐藏 `⊖`（避免用户点击 no-op 控件）
- design.md Section 2.3, Section 3

**Verification:**
- applyVisibility 笛卡尔测试断言 VD + WI/WA/Error + ISLAND → 最终显示 Capsule。
- B2c/B3c 的 WI/WA/Error 渲染断言 `⊖` 不可见。

**Design ref:** design.md Section 2.3, Section 3, Section 4, Section 20.3

---

## Summary: Critical Fixes Required

| Fix | Prevents |
|-----|----------|
| VD_VIEWER detection (split isAppInForeground) | round6 #1,#3,#4; round5 #5.2,#5.1 |
| onIslandTapped: detect viewer, toggle directly | round6 #1 |
| TaskCompleted: always append chat message | round6 #2; round5 #3.4 |
| SupplementReceived: always add user message | round5 #3.5 |
| No VD→real-screen app launch on completion | round5 #5.3 |
| Single capsule component (no separate input dock) | round5 #1.5, #3.3 |
| A11y overlay Row3 disabled during Running | round5 #2.7 |
| NavSpec: A11y no nav buttons | round5 #2.3 |
| applyVisibility: MAIN_APP → no overlays | round5 #1.4, #3.2 |
| Island/Capsule mutual exclusion in applyVisibility | round5 #2.4,#2.5,#5.1 |
| Island text derived from mode (not independent) | round5 #5.4 |
| Force CAPSULE on Error/WaitingFor* | design gap G2 |
| Stop click immediate pending feedback | round5 #1.3 |
| UserResponseSent callId strict guard | latent L1 (waiting loop break) |
| Lifecycle/window reorder deterministic convergence | latent L2 (race divergence) |
| applyVisibility force-CAPSULE state invariant for WI/WA/Error | latent L3 (interaction UI loss) |
