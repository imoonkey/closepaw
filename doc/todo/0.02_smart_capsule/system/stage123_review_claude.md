# Smart Capsule V2 — Independent Review (Claude)

**Date**: 2026-02-12
**Scope**: Full diff from `339448dd` — all 3 stages + simplification + docs
**Files reviewed**: All 30 changed files (see diff stat)

---

## Review Structure

1. Code correctness and quality (per stage)
2. Stage completion check (vs design docs)
3. UX design completion check (vs ux_design_1.md)
4. Original requirements check (vs qi_note.md + qi_ui.md)

---

## 1. Code Review

### 1.1 Architecture & Design Decisions — Positive

The implementation makes several excellent architectural choices:

- **CapsuleMode sealed interface** is the right abstraction. One value, one render. No boolean soup. This is the kind of thing that makes code maintainable.
- **CompletableDeferred for ask_user** is clean suspension. The tool just "takes long" — no special-casing in the turn loop. The agent has no idea it's waiting for a human.
- **UserResponseChannel** is a well-bounded bridge between UI and tool execution.
- **@Synchronized on HistoryManager** is the correct fix for the thread-safety concern. Simple, correct, not over-engineered.
- **AnimatorSet for pulse** bundles both scaleX/scaleY into one cancellable unit. No leak possible.
- **Tracked runnables** (delayedHideRunnable, supplementConfirmedRunnable, keyboardShowRunnable) with explicit cancellation in hide() and updateMode() — robust lifecycle management.

### 1.2 Code Issues — Bugs

#### [HIGH] thoughtText.maxLines not reset after WaitingFor* states

**Files**: `SmartCapsuleManager.kt` — renderRunning, renderTakeover, renderTakeoverPending, renderDone, renderError

**Problem**: `renderWaitingForInput()` and `renderWaitingForAction()` set `v.thoughtText.maxLines = 3`. When transitioning back to Running (e.g. after user responds to ask_user), `renderRunning()` does NOT reset `maxLines` to 1. The thought line in Running state would show up to 3 lines, breaking the single-line design.

Only `hideAnswerInputArea()` resets `maxLines = 1`, but this is only called for the WaitingForInput→stop path, not for WaitingForAction→complete or WaitingForInput→send.

**Fix**: Add `v.thoughtText.maxLines = 1` to renderRunning, renderTakeover, renderTakeoverPending, renderDone, renderError — any mode that expects single-line thought.

```kotlin
private fun renderRunning(v: CapsuleViews, mode: CapsuleMode.Running) {
    // ...
    v.thoughtText.text = mode.thought.ifEmpty { "思考中..." }
    v.thoughtText.alpha = 1f
    v.thoughtText.maxLines = 1  // Reset from WaitingFor* states
    v.thoughtText.isSingleLine = true
    // ...
}
```

#### [HIGH] VD mode: ask_user has no response mechanism

**File**: `ServiceOverlayController.kt` lines 304-317

**Problem**: In `PlatformMode.VIRTUAL_DISPLAY`, `onAskUser()` only updates the status island text to `"❓ ${message.take(20)}"`. There is no text input or confirmation button. The user cannot respond. `ask_user` will always timeout (5 minutes) in VD mode.

**Fix**: Either (a) open the main app to an ask_user response screen, (b) expand the status island with input capability, or (c) document that ask_user is A11y-only and have the tool return a graceful error in VD mode. Option (c) is simplest for now.

#### [MEDIUM] renderWaitingForAction: "完成" click doesn't clean up keyboard state

**File**: `SmartCapsuleManager.kt` — handlePrimaryClick, WaitingForAction branch

**Problem**: When WaitingForAction mode is entered, `setOverlayFocusable` is NOT called (correct — no keyboard needed). But if the user was previously in SupplementInput (which called `setOverlayFocusable(true)`), then task completes, then ask_user(action) fires, the overlay remains focusable from the previous supplement input. This is an edge case but violates the FLAG_NOT_FOCUSABLE contract.

**Fix**: Add `setOverlayFocusable(false)` at the start of `renderWaitingForAction()` to ensure consistent state.

#### [MEDIUM] onTaskCompleted does not pass CompletionReason to capsule

**File**: `ServiceOverlayController.kt` line 205, `SmartCapsuleManager.kt` line 623

**Problem**: `capsuleManager.onTaskCompleted()` always shows "已完成". The caller has `CompletionReason` but doesn't pass it. For MAX_TURNS or TASK_IMPOSSIBLE, the user should see a more informative message. Current implementation: all completions show "✓ 已完成" with teal dot.

**Fix**: Pass the reason and customize the done message:
```kotlin
fun onTaskCompleted(reason: CompletionReason = CompletionReason.GOAL_ACHIEVED) {
    val message = when (reason) {
        CompletionReason.GOAL_ACHIEVED -> "已完成"
        CompletionReason.MAX_TURNS -> "已达到最大步数"
        CompletionReason.TASK_IMPOSSIBLE -> "无法完成任务"
        else -> "已完成"
    }
    updateMode(CapsuleMode.Done(message))
}
```

#### [LOW] updateStatus regex for emoji stripping is fragile

**File**: `SmartCapsuleManager.kt` line 634

**Problem**: `Regex("[🚀👀🧠💡✅⏸️❌⚠️✓]")` uses a character class to match emoji. Multi-codepoint emoji like `⏸️` (base + variation selector) may not match correctly. This function is a legacy fallback — it only fires when thought is "思考中..." and a StatusUpdate arrives.

**Fix**: This is low priority. If the thought pipeline works correctly, updateStatus rarely fires during Running state. Consider removing it entirely if StatusUpdate events are deprecated in favor of ThoughtUpdate.

### 1.3 Code Issues — Design

#### [MEDIUM] SmartCapsuleManager at 641 lines exceeds 400-line guideline

The file handles rendering for 8 modes, input management, keyboard control, animations, event handlers. Consider extracting:
- `CapsuleInputManager` — keyboard/focusable/edittext logic (~100 lines)
- Keep the rest in SmartCapsuleManager

#### [LOW] Pill buttons lack contentDescription updates

**File**: `SmartCapsuleManager.kt`

When primary button changes from "接管" to "继续" to "完成", the `contentDescription` on the container ViewGroup is not updated (it was set once at build time). Screen readers would announce the wrong action.

**Fix**: Update `v.primaryButton.contentDescription` whenever `v.primaryText.text` changes.

---

## 2. Stage Completion Check

### Stage 1: Capsule Foundation — ✅ COMPLETE

| Phase | Status | Notes |
|-------|--------|-------|
| Phase 1: CapsuleMode + ThoughtUpdate | ✅ Done | Sealed interface, event, dispatcher |
| Phase 2: Capsule UI Rebuild | ✅ Done | Two-row layout, CapsuleViews, all rendering |
| Phase 3: Thought Pipeline E2E | ✅ Done | ThoughtUpdate event flow works |
| Phase 4: StatusIsland Integration | ✅ Done | VD mode shows truncated thought |
| Testing | ✅ Done | sanitizeThought + displayThought tests |

**One gap**: Design mentions `navContainer: ViewGroup` for [1][2][3] navigation buttons in CapsuleViews. Not included — intentionally deferred to a later stage per the design ("Others are defined now to avoid refactoring later"). This is fine.

### Stage 2: Takeover & Supplement — ✅ COMPLETE

| Phase | Status | Notes |
|-------|--------|-------|
| Phase 1: Protocol Changes | ✅ Done | Op.Takeover, Op.Supplement, events |
| Phase 2: Takeover/Resume in Session | ✅ Done | handleTakeover, handleResume |
| Phase 3: Capsule Takeover States | ✅ Done | TakeoverPending, Takeover rendering |
| Phase 4: Supplement Flow | ✅ Done | SupplementInput, keyboard, "已收到" flash |
| Testing | ⚠️ Partial | No dedicated supplement/takeover tests |

**Everything in the Stage 2 design doc is implemented.** Thread safety was addressed with @Synchronized.

### Stage 3: ask_user Tool — ✅ MOSTLY COMPLETE

| Phase | Status | Notes |
|-------|--------|-------|
| Phase 1: ask_user Tool + Suspension | ✅ Done | AskUserTool, UserResponseChannel |
| Phase 2: Protocol + Session Wiring | ✅ Done | Op.UserResponse, AskUser event, session |
| Phase 3: Capsule WaitingFor* States | ✅ Done | Rendering works, input area reused |
| Phase 4: Timeout + Edge Cases | ⚠️ Partial | See below |
| Testing | ⚠️ Partial | Basic UserResponseChannel tests, no timeout test |

**Gaps in Stage 3**:
1. **4-minute nudge** — Design says "At 4 minutes: capsule shows gentle nudge 'still waiting...'" Not implemented.
2. **VD mode ask_user** — Cannot respond in VD mode (see code issue above).
3. **WaitingForInput layout** — Design calls for expanded capsule (~160dp) with separate header/body/input/bottom sections. Current implementation reuses row1 for question text and the supplement input area for the answer. It works but doesn't match the visual hierarchy in the design.

---

## 3. UX Design Completion Check (vs ux_design_1.md)

### Implemented ✅

| Section | Feature | Status |
|---------|---------|--------|
| §3 | Two-row capsule layout | ✅ |
| §3 | Pill buttons with icon + label | ✅ |
| §3 | Status dot with color coding | ✅ |
| §3 | Button enable/disable per state | ✅ |
| §3 | 300ms button debounce | ✅ |
| §4 | Running state | ✅ |
| §4 | TakeoverPending state | ✅ |
| §4 | Takeover state | ✅ |
| §4 | SupplementInput state | ✅ |
| §4 | WaitingForInput state | ✅ (compact layout) |
| §4 | WaitingForAction state | ✅ (compact layout) |
| §4 | Done state (3s auto-hide) | ✅ |
| §4 | Error state (dismiss) | ✅ |
| §4.3 | Dot color per state | ✅ (except Purple for WaitingForInput) |
| §5 | agent_thought extraction | ✅ |
| §5 | 40-char sanitizer | ✅ |
| §5 | Fallback priority | ✅ |
| §6 | Takeover/resume flow | ✅ |
| §7 | Supplement flow + "已收到" | ✅ |
| §8 | ask_user question flow | ✅ |
| §8 | ask_user action flow | ✅ |
| §8 | 5-min timeout | ✅ |
| §8.6 | One pending ask_user | ✅ |
| §10 | Edge cases (mid-turn takeover, stop, empty thought, debounce) | ✅ |

### NOT Implemented ❌

| Section | Feature | Status | Was it designed? |
|---------|---------|--------|------------------|
| §3.1 | Row 1 tappable → open main app | ❌ | Yes (ux_design_1.md §3.1) |
| §4.3 | Purple dot for WaitingForInput | ❌ | Yes (ux_design_1.md §4.3) — currently uses no dot (hidden) |
| §8.3 | WaitingForInput: expanded capsule 160dp with header/body/input/bottom | ❌ Partial | Yes (ux_design_1.md §8.3) — uses compact reuse |
| §8.4 | WaitingForAction: expanded capsule 120dp with header/body/bottom | ❌ Partial | Yes (ux_design_1.md §8.4) — uses compact reuse |
| §8.5 | 4-minute nudge | ❌ | Yes (ux_design_1.md §8.5) |
| §9 | **Multi-Context Presence: [1][2][3] navigation buttons** | ❌ | Yes (full section) |
| §9.2 | Navigation between Main App / Screen Viewing / Background | ❌ | Yes (full section) |
| §9.3 | Status Island compact ↔ expanded capsule | ❌ | Yes (full section) |
| §9.4 | Main App: capsule replaces input dock | ❌ | Yes (full section) |
| §10.2 | "已收到，下一步生效" for supplement during LLM streaming | ❌ | Yes (§10.2) |
| §12 | Expand/collapse animation for WaitingFor* | ❌ | Yes (§12.1) |
| §12 | Done → Hidden fade out + slide down | ❌ | Yes (§12.1) |
| §12 | Running → Takeover dot color crossfade | ❌ | Yes (§12.1) |

### Summary

**Core collaboration features** (thought, takeover, supplement, ask_user) are **fully functional**. The capsule works as a floating overlay in A11y mode.

**Major gap**: The entire **Multi-Context Presence** system (Section 9) — which defines how the capsule appears in the main app, during screen viewing, and as a status island — is **not implemented**. This was explicitly designed in both ux_design_1.md and qi_ui.md.

**Secondary gap**: **WaitingFor* expanded layout** doesn't match the designed visual hierarchy. It reuses the compact layout, which works but feels cramped for multi-line questions/instructions.

**Polish gap**: **Animations/transitions** are missing. States switch instantly.

---

## 4. Original Requirements Check (vs qi_note.md + qi_ui.md)

### qi_note.md — 4 items

| # | Requirement | Implemented? | Notes |
|---|-------------|-------------|-------|
| 1 | Agent thought display (prompt → UI) | ✅ | Full pipeline |
| 2 | Takeover (接管): stale tool calls discarded on resume | ✅ | Agent re-perceives screen |
| 3 | Supplement (补充): inject user message | ✅ | Works in Running and Takeover |
| 4 | ask_user tool (question + action) | ✅ | CompletableDeferred suspension |

**All 4 qi_note.md requirements are implemented.**

### qi_ui.md — 3 systems

| # | System | Implemented? | Notes |
|---|--------|-------------|-------|
| 1 | Smart Capsule in 3 contexts (Main App, Screen Viewing, Background) | ❌ | Only floating overlay in A11y |
| 2 | [1][2][3] navigation buttons for context switching | ❌ | Not implemented at all |
| 3 | Status Island ↔ Smart Capsule interaction | ❌ | StatusIsland exists but no bidirectional switching |

**qi_ui.md is largely unimplemented.** The navigation/context system was designed in ux_design_1.md §9 but not in the system design docs (stage 1/2/3), and thus not implemented.

---

## 5. Gap Analysis Summary

### Category A: Code bugs (fix locally, no design needed)
1. thoughtText.maxLines not reset after WaitingFor* → Running
2. VD mode ask_user cannot be responded to
3. renderWaitingForAction doesn't ensure non-focusable state
4. onTaskCompleted doesn't pass CompletionReason
5. ContentDescription not updated when button label changes
6. SmartCapsuleManager exceeds 400-line guideline

### Category B: Designed but not implemented (needs system design + code)
1. **Multi-Context Presence** — [1][2][3] buttons, context switching (ux_design_1.md §9)
2. **Main App capsule integration** — capsule replaces input dock (ux_design_1.md §9.4)
3. **Status Island expansion** — compact ↔ full capsule (ux_design_1.md §9.3)
4. **WaitingFor* expanded layout** — proper header/body/input sections (ux_design_1.md §8.3-8.4)
5. **4-minute nudge** for ask_user timeout (ux_design_1.md §8.5)
6. **State transition animations** (ux_design_1.md §12)

### Category C: Designed in UX but missing system design
1. Multi-Context Presence was designed in ux_design_1.md but NOT in any stage doc
2. Main App integration was described in ux_design_1.md but has no system design
3. WaitingFor* expanded layout was designed in ux_design_1.md but stage 3 reuses compact layout

---

## Recommendation

**Code quality is solid.** The refactoring/simplification pass cleaned up the critical issues from stage 1/2/3 reviews. The architecture (CapsuleMode, UserResponseChannel, event pipeline) is clean and correct.

**Fix Category A issues** (local code fixes, no design needed) — straightforward, can be done immediately.

**For Category B + C**: A "Round 2" is needed to design and implement the Multi-Context Presence system. This is the single biggest gap between the original vision (qi_ui.md) and what was built. The capsule currently only exists as a floating overlay — it doesn't integrate with the main app or the status island navigation.

Priority for Round 2:
1. WaitingFor* expanded layout (improves core ask_user UX)
2. Multi-Context Presence [1][2][3] (the qi_ui.md vision)
3. State transition animations (polish)
