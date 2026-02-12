# Smart Capsule V2 — Round 2 UX Design

**Status**: DESIGN
**Scope**: Gaps remaining from ux_design_1.md after Round 1 implementation
**Prerequisite**: Round 1 (Stages 1-3) fully implemented

---

## 0. What's Missing

Round 1 shipped the four core collaboration features (thought, takeover, supplement, ask_user). What's missing falls into three categories:

1. **ask_user is half-baked.** The WaitingFor* states reuse the compact 2-row layout. The question is crammed into the thought line. No visual hierarchy. No 4-minute nudge. No context-aware supplement confirmation. In VD mode, ask_user is completely broken — the status island can't accept text input or show a "完成" button, so every ask_user times out.

2. **Transitions are jarring.** State changes happen instantly — no crossfade, no height animation, no slide. The capsule snaps between modes like a PowerPoint slide. It works but doesn't feel native.

3. **Takeover lies.** The capsule shows "Takeover" (user in control) immediately when `SessionTakeover` fires, but the agent's current turn may still be executing tool calls in the background. The user thinks they're in control but the agent is still touching the screen.

This document designs the fix for all three.

---

## 1. ask_user Expanded Layout

### 1.1 Problem

When the agent asks a question ("Which platform?") or requests an action ("Please log in"), the user needs to:
- Read the question/instruction clearly
- Respond (type answer or tap Done)
- Understand the state they're in

The current implementation stuffs everything into the compact 2-row capsule. The question competes with the header for the single thought line. There's no visual hierarchy — the user has to parse meaning from a cramped space.

### 1.2 WaitingForInput Layout (Question)

The capsule expands upward. Three distinct sections:

```
┌──────────────────────────────────────────────────────┐
│  💬 等待答复                                           │  ← Header (fixed, muted)
├──────────────────────────────────────────────────────┤
│  请问你想要哪个平台的包臀裙？                            │  ← Body (agent's question)
│  1. Temu $4.98  2. Shein $2.99                       │     max 3 lines, scrollable if more
├──────────────────────────────────────────────────────┤
│  [输入你的答复...                      ] [发送 →]      │  ← Input row
├──────────────────────────────────────────────────────┤
│                                             [⏹ 停止]  │  ← Bottom row
└──────────────────────────────────────────────────────┘
```

| Property | Value |
|----------|-------|
| Total height | ~160dp |
| Header row | 28dp — status icon + label, 12sp, muted gray |
| Body | Flexible — question text, 14sp, dark, max 3 lines |
| Input row | 44dp — EditText + 发送 button |
| Bottom row | 36dp — stop button only (right-aligned) |

**Behavior:**
- On entry: keyboard auto-rises, EditText focused
- 发送 enabled only when text is non-empty
- Overlay is focusable (for keyboard input)
- On send: response delivered to agent, capsule collapses back to Running

### 1.3 WaitingForAction Layout (Action)

Simpler — no text input needed.

```
┌──────────────────────────────────────────────────────┐
│  ✋ 操作手机                                           │  ← Header
├──────────────────────────────────────────────────────┤
│  请登录您的淘宝账户                                     │  ← Body (instruction)
│                                                      │     max 2 lines
├──────────────────────────────────────────────────────┤
│           [✅ 完成]                        [⏹ 停止]   │  ← Bottom row
└──────────────────────────────────────────────────────┘
```

| Property | Value |
|----------|-------|
| Total height | ~120dp |
| Header row | 28dp |
| Body | Flexible — instruction text, 14sp, max 2 lines |
| Bottom row | 44dp — 完成 (primary, left-center) + 停止 (right) |

**Behavior:**
- Overlay is NOT focusable (user needs to operate the phone freely)
- On 完成: "done" delivered to agent, capsule collapses back to Running
- User operates phone normally during this state

### 1.4 Visual Hierarchy

The expanded layout uses clear visual separation:
- **Header**: Small text + icon, muted color (`#6B7280`). Not the main focus.
- **Body**: Larger text, full black (`#171717`). The thing the user needs to read.
- **Dividers**: 1dp `#E5E5E5` between sections.
- **Background**: Same white card as compact capsule, same corner radius.

The expansion is upward — the bottom edge stays pinned, the top grows. This keeps the buttons in the same position as the compact layout.

---

## 2. ask_user Timeout Nudge

### 2.1 Problem

The 5-minute timeout is silent. The user might forget they were asked something (they're busy on their phone). The agent just silently continues after timeout. The user might not even notice.

### 2.2 Design

At **4 minutes** (1 minute before timeout):

```
┌──────────────────────────────────────────────────────┐
│  💬 等待答复                                           │
├──────────────────────────────────────────────────────┤
│  请问你想要哪个平台的包臀裙？                            │
│  还在等待您的回复...                                    │  ← Nudge text appended
├──────────────────────────────────────────────────────┤
│  [输入你的答复...                      ] [发送 →]      │
├──────────────────────────────────────────────────────┤
│                                             [⏹ 停止]  │
└──────────────────────────────────────────────────────┘
```

The nudge is a second line in the body, below the original question. Muted color, italic feel: "还在等待您的回复...". Not aggressive, not dismissible — just a reminder.

At **5 minutes**: the tool times out server-side. The capsule transitions to Running with thought "未收到回复，继续执行". No special UI needed — this is handled by the agent's next ThoughtUpdate.

**Implementation**: A 4-minute `postDelayed` runnable in SmartCapsuleManager, started when entering any WaitingFor* state, cancelled on exit.

---

## 3. Context-Aware Supplement Confirmation

### 3.1 Problem

When the user sends a supplement while the agent is actively thinking (LLM streaming), the supplement won't be seen until the NEXT turn. The current implementation shows "已收到" — which is true but misleading. The user might think the agent immediately adjusted.

### 3.2 Design

Two confirmation messages based on timing:

| When supplement is sent | Confirmation message | Duration |
|-------------------------|---------------------|----------|
| Agent between turns (idle/waiting) | "✓ 已收到" | 1.5s |
| Agent mid-turn (LLM streaming or tool executing) | "✓ 已收到，下一步生效" | 2s |

The determination is simple: if the capsule is in `Running` mode and we know the agent is mid-turn (we can track this via `TurnPhaseChanged` events), show the longer message. Otherwise, show the short one.

The flash replaces the thought line text temporarily, same as current implementation. The longer message gets 2s instead of 1.5s because it's more text to read.

---

## 4. VD Mode ask_user Fix

### 4.1 Problem

In VD mode, the only overlay on the real screen is the Status Island — a tiny pill showing dot + truncated thought. When the agent calls `ask_user`, the status island shows "❓ message" and... nothing else. The user can't type an answer. Can't tap "完成". The request always times out after 5 minutes.

This makes VD mode completely broken for any task requiring user interaction (login, captcha, ambiguous choice).

### 4.2 Design

When `ask_user` fires in VD mode, **show the full SmartCapsule overlay** on the real screen — the same expanded WaitingFor* layout designed in Section 1. The status island stays visible at the top; the capsule appears at the bottom.

```
┌──────────────────────────────────────────────────────┐
│ ╭──────────────────╮                                 │
│ │ ● ask_user中...   │  ← Status island (still visible)│
│ ╰──────────────────╯                                 │
│                                                      │
│          (user's own screen below)                   │
│                                                      │
│ ┌──────────────────────────────────────────────────┐ │
│ │  💬 等待答复                                       │ │
│ ├──────────────────────────────────────────────────┤ │
│ │  请问你想要哪个平台的包臀裙？                        │ │  ← SmartCapsule overlay
│ ├──────────────────────────────────────────────────┤ │
│ │  [输入答复...                    ] [发送 →]       │ │
│ ├──────────────────────────────────────────────────┤ │
│ │                                        [⏹ 停止]  │ │
│ └──────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

**Flow:**
1. `ask_user` event arrives in VD mode
2. `ServiceOverlayController` shows SmartCapsule overlay (same one used in A11y mode)
3. User sees the question/instruction with full UI
4. User types answer / taps 完成
5. Response delivered to agent
6. SmartCapsule hides, status island continues

**Key points:**
- The capsule overlay is the SAME SmartCapsuleManager, just shown temporarily in VD mode
- The capsule is shown at the bottom of the real screen (same position as A11y mode)
- Keyboard works because the capsule manages focusable state
- When the response is sent, the capsule hides and we're back to status-island-only
- The status island dot turns purple during WaitingFor* (matching A11y behavior)

This is the simplest fix: reuse the existing capsule instead of building a new VD-specific UI.

---

## 5. Takeover Timing Fix

### 5.1 Problem

When the user taps 接管, the current flow is:
1. `pause()` sets a boolean flag
2. `SessionTakeover` event emits immediately
3. Capsule transitions: TakeoverPending → Takeover
4. Agent's current turn **continues executing** in the background
5. Agent only actually pauses when the current turn finishes (loop top check)

Between steps 3 and 5, the user sees "Takeover" but the agent is still touching the screen. The user thinks they're in control, but the agent is still acting.

### 5.2 Design

**User experience stays the same.** The capsule still shows TakeoverPending → Takeover. The difference is timing:

1. User taps 接管 → Capsule shows **TakeoverPending** ("正在交接...")
2. `pause()` sets the boolean flag (current turn continues finishing)
3. Current turn completes → agent loop reaches pause check → **actually pauses**
4. `SessionTakeover` event emits **now** (not in step 1)
5. Capsule transitions to **Takeover** state

The user sees TakeoverPending for a few seconds (while the current action finishes), then Takeover. This matches reality: the handover isn't instant.

**Edge case**: What if the agent is between turns (not mid-action)? Then the pause takes effect immediately — TakeoverPending is brief (<100ms) and transitions right away.

**Stop during TakeoverPending**: Always works. Stop cancels the in-flight action and terminates.

---

## 6. State Transition Animations

### 6.1 Problem

Every state change is a hard cut — views appear/disappear instantly, heights jump, colors switch without transition. This feels broken to users accustomed to iOS/Android system animations.

### 6.2 Design

Minimal, tasteful animations. Each serves a purpose.

| Transition | Animation | Duration | Why |
|------------|-----------|----------|-----|
| Running → TakeoverPending/Takeover | Dot color crossfade (blue→amber) | 200ms | Show state change visually |
| Running → WaitingFor* | Height expand upward + content fade in | 250ms | Draw attention to new content |
| WaitingFor* → Running | Height collapse + content fade out | 200ms | Return to compact smoothly |
| Any → SupplementInput | Row 2 content crossfade | 150ms | Swap buttons → input |
| SupplementInput → Previous | Row 2 content crossfade | 150ms | Swap input → buttons |
| Any → Done | Content crossfade to done message | 200ms | Clean transition |
| Done → Hidden | Fade out + slide down 16dp | 300ms (after 3s delay) | Graceful exit |
| Running thought text update | No animation (instant replace) | — | Frequent updates shouldn't animate |

### 6.3 Principles

- **Don't animate what changes frequently.** Thought text updates happen every few seconds. Animating them would be distracting.
- **Animate what changes user context.** Height changes, mode changes, appear/disappear — these change the user's understanding of the interface.
- **Keep durations under 300ms.** The capsule is a secondary UI — it should never make the user wait.
- **Use ease-out.** Natural deceleration for all animations.
- **Cancel on mode change.** If a new mode arrives during animation, snap to the new state.

### 6.4 Dot Pulse

Already implemented: Running state has a pulsing blue dot (scale 1.0→1.3→1.0, 1.5s cycle). No change needed.

---

## 7. Scope Boundaries (Round 2)

### In scope
- ask_user expanded layout (Section 1)
- 4-minute nudge (Section 2)
- Context-aware supplement confirmation (Section 3)
- VD mode ask_user fix (Section 4)
- Takeover timing fix (Section 5)
- State transition animations (Section 6)

### Deferred to future round
| Feature | Why deferred |
|---------|-------------|
| Multi-context navigation [1][2][3] buttons | Complex feature touching many files. Natural navigation already works: row1 tap → app, island tap → VD viewer. Buttons are convenience, not functional gaps. |
| Main app capsule replaces input dock | Requires Compose integration into existing ChatScreen. The overlay capsule still works when user is in the app (they can leave and see it). |
| Status Island ↔ full capsule expansion | Long-press on island already shows controls. Full expansion adds complexity for marginal benefit. |

These deferred features are real gaps from ux_design_1.md §9 but they are **not functional blockers**. Every collaboration feature works without them. They improve navigation convenience and will be addressed in Round 3.

---

## 8. Success Criteria (Round 2)

| What | Before | After |
|------|--------|-------|
| ask_user readability | Question crammed in thought line | Clear header/body/input sections |
| ask_user in VD mode | Always times out (no UI) | User can respond via capsule overlay |
| Takeover accuracy | User sees "in control" while agent still acts | Takeover confirmed only when agent actually stopped |
| Transition smoothness | Hard cuts | Animated expand/collapse/fade |
| Supplement clarity | "已收到" always (ambiguous timing) | "已收到" or "已收到，下一步生效" based on agent state |
