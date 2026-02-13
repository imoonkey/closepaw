status: approved

# Qi Review Note:
1. Smart Capsule 在现在的two row基础上，改成3 rows。Input dock变成smart capsule的第三row。这样修改你的4.1和4.2的描述。
2. input 层的按钮在没任务的时候是[发送 →]，在有任务的时候是[补充]。补充可以从第二层拿掉了。
3. 在没任务的时候，在主app，只显示第三层。在有任务的时候，显示第一层第二层和第三层。
4. 接管/停止， 和1/2/3的按钮放到第二层。 第一层只显示agent thought。

> **v2 revision**: Addressed all 4 review notes. Capsule restructured to 3 rows. InputDock merged into Row 3. 补充 moved from Row 2 to Row 3 button. Row 1 is thought-only. Row 2 is controls + nav.


# Smart Capsule V2 — Round 3 UX Design

**Scope**: Deferred features from Round 1/2 — VD mode capsule, multi-context navigation [1][2][3], main app capsule, status island expansion, unified state.

**Prerequisite**: Round 1 (thought/takeover/supplement/ask_user) + Round 2 (expanded layouts, animations, VD ask_user fix, takeover timing) fully implemented.

---

## 0. What's Missing

Rounds 1–2 shipped the four core collaboration features. But the capsule only lives in ONE place — the A11y overlay. Three contexts remain broken or missing:

1. **VD LiveView has no capsule.** The user opens the VD viewer and sees a dumb "Live Preview" label with a close button. No thought text, no agent controls, no way to interact. The user must close the viewer, long-press the island, and hope the tiny pause/stop buttons appear. Unacceptable.

2. **Main app ignores the capsule.** When a task is active and the user is in the Android Agent app, they see the old InputDock with "Agent is working…" and a stop button. No thought text, no takeover, no supplement. The user has to leave the app to use capsule features. The capsule and InputDock are parallel UIs doing the same job.

3. **No navigation between contexts.** The user stumbles between the app, the viewer, and the island by accident — pressing Home, pressing Back, tapping the island, tapping row1. There are no explicit navigation affordances. The user can't say "show me the viewer" from the app or "take me to chat" from the viewer.

4. **Status island is a dead end.** Tap → opens VD viewer. Long-press → shows tiny stop/pause. No way to see the capsule controls, no way to navigate to the app, no way to read the full thought. The island is notification-only.

**Root cause**: The capsule was built as an overlay for A11y mode. It was never designed to live inside the app or inside the viewer. Round 3 fixes this by making the capsule a **universal widget** — same state, same controls, rendered in the right container for each context.

---

## 1. Design Principles (Round 3 additions)

**6. One state, many renderers.**
The capsule is driven by a single `CapsuleMode` value. The overlay, the in-app Compose widget, and the VD viewer widget all read from the same state. State management is unified — not duplicated per context.

**7. Navigate, don't discover.**
The user should never wonder "how do I get to the viewer?" or "how do I go back to chat?" Explicit navigation buttons [1][2][3] make every transition intentional and discoverable.

**8. The capsule IS the bottom widget.**
The InputDock doesn't coexist with the capsule — it IS the capsule. The capsule has three rows. When no task is active, only Row 3 (input) shows — that's the InputDock. When a task starts, Rows 1–2 expand above. The bottom slot is always the capsule; it just grows and shrinks.

---

## 2. Capsule Anatomy — Three-Row Layout

The capsule has **three rows**. Each row serves one purpose. Rows expand and collapse based on task state.

### 2.1 Full Layout (Task Active)

```
┌──────────────────────────────────────────────────────┐
│  ● 打开淘宝搜索包臀裙...                               │  ← Row 1: status dot + thought
├──────────────────────────────────────────────────────┤
│  [✋ 接管]  [⏹ 停止]              [⊖] [📱] [👁]      │  ← Row 2: controls + nav
├──────────────────────────────────────────────────────┤
│  [有想法? 补充一下...                    ] [💬 补充]   │  ← Row 3: input + supplement
└──────────────────────────────────────────────────────┘
```

### 2.2 Idle Layout (No Task, Main App only)

```
┌──────────────────────────────────────────────────────┐
│  [有什么可以帮你?                         ] [发送 →]  │  ← Row 3 only: input + send
└──────────────────────────────────────────────────────┘
```

### 2.3 Row Definitions

**Row 1 — Thought Line** (visible when task is active):
- Status dot (8dp, color-coded, left-aligned) + thought text
- Single line, ellipsize end
- Tappable → opens main app (chat view)
- Height: 36dp

**Row 2 — Controls + Navigation** (visible when task is active):
- Left side: agent control buttons ([接管/继续], [停止], or [完成])
- Right side: context navigation icons ([1] [2] [3])
- Height: 44dp

**Row 3 — Input** (always visible):
- Text input field (single line, expandable) + action button
- No task: placeholder "有什么可以帮你?", button = [发送 →]
- Task active: placeholder "有想法? 补充一下...", button = [💬 补充]
- WaitingForInput: placeholder "输入你的答复...", button = [发送 →]
- Height: 52dp (including padding)

### 2.4 Row Visibility Rules

| State | Row 1 | Row 2 | Row 3 |
|-------|-------|-------|-------|
| No task (main app) | Hidden | Hidden | ✓ (send mode) |
| Running | ✓ | ✓ | ✓ (supplement mode) |
| TakeoverPending | ✓ | ✓ | ✓ (supplement mode) |
| Takeover | ✓ | ✓ | ✓ (supplement mode) |
| WaitingForInput | ✓ (shows question header) | ✓ | ✓ (answer mode) |
| WaitingForAction | ✓ (shows instruction header) | ✓ | Hidden (user operates phone) |
| Done | ✓ (shows "✓ 已完成") | Hidden | Hidden |
| Error | ✓ (shows error) | ✓ ([关闭] only) | Hidden |

### 2.5 Row 2 Button Layout Per State

| State | Left controls | Right nav |
|-------|--------------|-----------|
| Running | [✋ 接管] [⏹ 停止] | [1] [2] [3] (per context) |
| TakeoverPending | [✋ 接管 disabled] [⏹ 停止] | [1] [2] [3] |
| Takeover | [▶ 继续] [⏹ 停止] | [1] [2] [3] |
| WaitingForInput | [⏹ 停止] | [1] [2] [3] |
| WaitingForAction | [✅ 完成] [⏹ 停止] | [1] [2] [3] |
| Error | [关闭] | [1] [2] [3] |

### 2.6 Row 3 Input Behavior

| State | Placeholder | Button | On button tap |
|-------|------------|--------|---------------|
| No task | "有什么可以帮你?" | [发送 →] | Send message → start task |
| Running | "有想法? 补充一下..." | [💬 补充] | Inject supplement into agent history |
| TakeoverPending | "有想法? 补充一下..." | [💬 补充] | Inject supplement |
| Takeover | "有想法? 补充一下..." | [💬 补充] | Inject supplement |
| WaitingForInput | "输入你的答复..." | [发送 →] | Send answer to agent tool |

### 2.7 Expanded States (WaitingFor*)

For `WaitingForInput` and `WaitingForAction`, the capsule adds an **expanded body** between Row 1 and Row 2 to display the agent's question or instruction:

```
WaitingForInput:
┌──────────────────────────────────────────────────────┐
│  💬 等待答复                                           │  ← Row 1 (header mode)
├──────────────────────────────────────────────────────┤
│  请问你想要哪个平台的包臀裙？                            │  ← Expanded body (question)
│  1. Temu $4.98  2. Shein $2.99                       │     max 3 lines
├──────────────────────────────────────────────────────┤
│  [⏹ 停止]                          [⊖] [📱] [👁]    │  ← Row 2
├──────────────────────────────────────────────────────┤
│  [输入你的答复...                      ] [发送 →]     │  ← Row 3 (answer mode)
└──────────────────────────────────────────────────────┘

WaitingForAction:
┌──────────────────────────────────────────────────────┐
│  ✋ 操作手机                                           │  ← Row 1 (header mode)
├──────────────────────────────────────────────────────┤
│  请登录您的淘宝账户                                     │  ← Expanded body (instruction)
├──────────────────────────────────────────────────────┤
│  [✅ 完成]  [⏹ 停止]               [⊖] [📱] [👁]    │  ← Row 2
└──────────────────────────────────────────────────────┘
```
(Row 3 hidden in WaitingForAction — user is operating the phone, not typing.)

### 2.8 Sizing

| Property | Value |
|----------|-------|
| Width | Screen width − 32dp (16dp margin each side) |
| Row 1 height | 36dp |
| Row 2 height | 44dp |
| Row 3 height | 52dp |
| Expanded body | Flexible (max 3 lines) |
| Idle mode total | ~52dp (Row 3 only + padding) |
| Running mode total | ~140dp (all 3 rows + padding + dividers) |
| Corner radius | 24dp |
| Background | White, subtle shadow (elevation 4dp) |
| Position | Bottom, 8dp above nav bar |

---

## 3. Context Map

The Smart Capsule appears in three contexts. The three-row structure is identical everywhere. The difference is where it lives, which rows are visible, and which navigation buttons appear in Row 2.

```
┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐
│   (A) Main App      │     │  (B) Screen Viewing  │     │  (C) Background     │
│                     │     │                     │     │                     │
│  ┌───────────────┐  │     │  ┌───────────────┐  │     │  ╭─────────────╮    │
│  │  Chat history │  │     │  │  Screen the   │  │     │  │   Island    │    │
│  │  ...          │  │     │  │  agent is     │  │     │  ╰─────────────╯    │
│  │               │  │     │  │  operating    │  │     │         ↓ tap       │
│  ╞═══════════════╡  │     │  │               │  │     │  ┌───────────────┐  │
│  │ Smart Capsule │  │     │  ╞═══════════════╡  │     │  │ Smart Capsule │  │
│  │ (Compose)     │  │     │  │ Smart Capsule │  │     │  │ (overlay)     │  │
│  └───────────────┘  │     │  │ (overlay/view)│  │     │  └───────────────┘  │
│                     │     │  └───────────────┘  │     │                     │
└─────────────────────┘     └─────────────────────┘     └─────────────────────┘
```

| Context | When | Capsule type | Capsule position |
|---------|------|-------------|-----------------|
| **(A) Main App** | User is in the Android Agent app | Compose widget (embedded) | Bottom of ChatScreen |
| **(B) Screen Viewing** | A11y: agent operating real screen. VD: VD viewer open. | System overlay (View-based) | Bottom of real screen |
| **(C) Background** | VD mode, user on own screen | Collapsed: Status Island (top). Expanded: System overlay (bottom). | Island at top; expanded capsule at bottom |

---

## 4. Navigation Buttons [1][2][3]

Three small icon buttons in Row 2, right-aligned after the control buttons.

| Button | Icon | Action |
|--------|------|--------|
| **[1] Minimize** | `⊖` | Collapse to Status Island (VD only) |
| **[2] App** | `📱` | Open main app (chat view) |
| **[3] Watch** | `👁` | Open VD LiveView |

### 4.1 Button sizing

- 28dp tap target, 18dp icon
- 4dp spacing between buttons
- Right-aligned in Row 2, after the control buttons
- Subtle gray (`#9CA3AF`) default, slightly darker on press (`#6B7280`)

### 4.2 Which buttons appear where

The rule is simple: don't show a button for where you already are, and don't show [1] when there's no island.

| Context | [1] ⊖ | [2] 📱 | [3] 👁 |
|---------|-------|--------|--------|
| **(A) Main App, A11y** | — | — | — |
| **(A) Main App, VD** | ✓ | — | ✓ |
| **(B) Viewing, A11y** | — | ✓ | — |
| **(B) Viewing, VD** | ✓ | ✓ | — |
| **(C) Expanded, VD** | ✓ | ✓ | ✓ |

**Rationale:**
- [1] (Minimize): Only in VD mode where a Status Island exists. A11y has no island.
- [2] (App): Never shown when already in the app.
- [3] (Watch): Never shown when already watching. In A11y mode, the user IS watching the real screen — there's no separate viewer.
- In Main App A11y: No nav buttons at all. The agent is operating the real screen; the user leaves the app naturally to see it.

### 4.3 Navigation button actions

| Button | From context | Effect |
|--------|-------------|--------|
| **[1]** | (A) Main App VD | Finish activity → island visible on home screen |
| **[1]** | (B) Viewing VD | Finish VD viewer → island visible |
| **[1]** | (C) Expanded VD | Collapse capsule overlay → island visible |
| **[2]** | (B) Viewing A11y | Launch MainActivity (overlay hides, in-app capsule shows) |
| **[2]** | (B) Viewing VD | Launch MainActivity, finish VD viewer |
| **[2]** | (C) Expanded VD | Launch MainActivity, collapse capsule overlay |
| **[3]** | (A) Main App VD | Launch VirtualDisplayViewerActivity |
| **[3]** | (C) Expanded VD | Launch VirtualDisplayViewerActivity, collapse capsule overlay |

---

## 5. Main App Capsule (Context A)

### 5.1 The Insight

The InputDock and the Smart Capsule are NOT separate widgets — they are the SAME widget. The InputDock is Row 3 of the capsule in idle mode. When a task starts, Rows 1–2 expand above Row 3. The bottom slot is always the capsule; it just grows and shrinks.

### 5.2 Bottom Widget State Machine

```
              ┌─────────────────┐
              │ Row 3 only      │  (text field + [发送])
              │ "有什么可以帮你?"  │
              └───────┬─────────┘
                      │ TaskStarted
                      ▼
              ┌─────────────────┐
      ┌──────►│ Rows 1+2+3     │  (thought + controls + input)
      │       │ "补充一下..."    │  Row 3 button → [补充]
      │       └───────┬─────────┘
      │               │ TaskCompleted → Done → Hidden
      │               ▼
      │       ┌─────────────────┐
      └───────│ Row 3 only      │  (text field + [发送])
              └─────────────────┘
```

The transition is:
- `CapsuleMode.Hidden` → Row 3 only (idle input mode)
- Any active `CapsuleMode` → Rows 1+2 expand, Row 3 switches to supplement mode
- `CapsuleMode.Done` → brief done display → `Hidden` → back to Row 3 only

### 5.3 Compose Implementation

The `ChatScreen` bottom bar is always the `SmartCapsuleCompose` widget:

```kotlin
bottomBar = {
    val capsuleMode by capsuleStateHolder.mode.collectAsStateWithLifecycle()
    SmartCapsuleCompose(
        mode = capsuleMode,
        platformMode = platformMode,
        context = CapsuleContext.MAIN_APP,
        onSend = viewModel::sendMessage,        // Row 3 [发送] in idle
        onSupplement = ...,                      // Row 3 [补充] in active
        onTakeover = ...,
        onResume = ...,
        onStop = ...,
        onUserResponse = ...,
        onNavigate = ...
    )
}
```

No conditional. The capsule handles both idle (Row 3 only) and active (all rows) states internally. `InputDock` as a separate component is **deprecated**.

### 5.4 Supplement Flow (Simplified)

With Row 3 always visible, supplement is trivially simple:
1. User types in Row 3's text field
2. Taps [💬 补充]
3. Message injected into agent history
4. Text field clears, brief "✓ 已收到" flash
5. No mode change, no SupplementInput state, no keyboard hacks

The old `CapsuleMode.SupplementInput` is no longer needed in the main app. Row 3 is always there.

### 5.5 ask_user in Main App

When `ask_user` fires while user is in the main app:
- Capsule expands: expanded body appears between Row 1 and Row 2
- For WaitingForInput: Row 3 switches to answer mode (placeholder "输入你的答复...", button [发送 →])
- For WaitingForAction: Row 3 hides (user operates phone). [完成] button is in Row 2.
- Chat history scrolls up to make room for expanded capsule

---

## 6. VD Viewer Capsule (Context B, VD mode)

### 6.1 Current State

The VD viewer shows a useless `ViewerCapsule`:
```
╭────────────────╮
│ [X]  Live Preview │
╰────────────────╯
```

No thought, no controls, no interaction. The user can only close the viewer.

### 6.2 Design

Replace `ViewerCapsule` with the real Smart Capsule. The capsule appears as a system overlay at the bottom of the VD viewer activity, using the existing `SmartCapsuleManager` overlay.

```
┌──────────────────────────────────────────────────────┐
│                                                      │
│              VD Live Preview                         │
│              (SurfaceView)                           │
│                                                      │
│  ┌──────────────────────────────────────────────┐    │
│  │  ● 打开淘宝搜索...                            │    │  ← Row 1
│  ├──────────────────────────────────────────────┤    │
│  │  [✋ 接管] [⏹ 停止]          [⊖] [📱]       │    │  ← Row 2 (controls + nav)
│  ├──────────────────────────────────────────────┤    │
│  │  [有想法? 补充一下...          ] [💬 补充]    │    │  ← Row 3 (input)
│  └──────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────┘
```

**Key changes:**
- When VD viewer is visible: show SmartCapsule overlay (all 3 rows, same as A11y mode)
- When VD viewer is hidden: hide SmartCapsule overlay, island continues
- Remove the `ViewerCapsule` Composable entirely
- Remove swipe-up dismiss (user navigates via [1] minimize or [2] app)
- Keep the SurfaceView (LivePreviewSurface) — that stays
- The "Swipe up to exit" hint is removed — [1] ⊖ and [2] 📱 are the exit paths

### 6.3 VD Viewer Lifecycle

```
VD Viewer opened:
  → StatusIsland hides (no double-info)
  → SmartCapsule overlay shows at bottom (Rows 1+2+3)
  → CapsuleContext set to SCREEN_VIEWING

VD Viewer closed (via [1], [2], or system back):
  → SmartCapsule overlay hides
  → StatusIsland shows again
  → CapsuleContext set to BACKGROUND
```

### 6.4 Overlay Row 3 Keyboard Handling

In the overlay (View-based), Row 3's text field requires special handling:
- Default: overlay is `FLAG_NOT_FOCUSABLE` (doesn't steal focus from the app)
- When user taps Row 3's text field:
  1. Remove `FLAG_NOT_FOCUSABLE`
  2. Update window layout
  3. Focus the EditText
  4. Show soft keyboard
- When user taps [补充] or taps elsewhere:
  1. Hide keyboard
  2. Add `FLAG_NOT_FOCUSABLE` back
  3. Update window layout

This is the same mechanism used in the current SupplementInput mode, just always-visible.

### 6.5 Navigation from VD Viewer

- **[1] ⊖**: Finish VD viewer → island reappears on user's screen
- **[2] 📱**: Launch MainActivity → VD viewer finishes → in-app capsule takes over
- Row 1 tap: Same as [2] — open app

---

## 7. Status Island Enhancement (Context C)

### 7.1 Current State

- Tap: opens VD viewer directly
- Long-press: shows inline stop/pause buttons (auto-hide 3s)

### 7.2 New Behavior

- **Tap**: Expand SmartCapsule overlay at bottom of screen (full controls + navigation)
- **Long-press**: (removed — tap now shows full controls, long-press is no longer needed)

### 7.3 Island Tap → Expanded Capsule

When the user taps the status island, the SmartCapsule overlay appears at the bottom of the real screen. This is Context (C) Expanded.

```
┌──────────────────────────────────────────────────────┐
│ ╭──────────────────╮                                 │
│ │ ● 打开淘宝搜索... │  ← Island (stays visible)      │
│ ╰──────────────────╯                                 │
│                                                      │
│          (user's own screen)                         │
│                                                      │
│  ┌──────────────────────────────────────────────┐    │
│  │  ● 打开淘宝搜索...                            │    │
│  ├──────────────────────────────────────────────┤    │
│  │  [✋ 接管] [⏹ 停止]    [⊖] [📱] [👁]        │    │
│  ├──────────────────────────────────────────────┤    │
│  │  [有想法? 补充一下...        ] [💬 补充]      │    │
│  └──────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────┘
```

**Interactions:**
- [1] ⊖ → Collapse capsule overlay, island stays → back to Context (C) collapsed
- [2] 📱 → Open main app, collapse overlay, island stays
- [3] 👁 → Open VD viewer, collapse overlay, island hides (viewer has its own capsule)
- Tap outside capsule → collapse back to island-only (same as [1])
- All agent controls work: 接管, 停止, 补充 (via Row 3)

### 7.4 Island During ask_user

When `ask_user` fires while user is on their own screen (Context C):
- SmartCapsule overlay appears automatically (no tap needed)
- Island dot turns purple (WaitingFor* state)
- Capsule shows expanded WaitingForInput or WaitingForAction layout
- User responds, capsule collapses, island continues with updated status

This is already partially implemented (Round 2 VD ask_user fix). Round 3 ensures it works seamlessly with the new island tap → expand flow.

---

## 8. Unified State Management

### 8.1 The Problem

Currently, two separate state systems drive the UI:
- `SmartCapsuleManager.mode: CapsuleMode` — drives overlay capsule
- `ChatViewModel._uiState.inputState: InputState` — drives InputDock (Idle/Working)

These duplicate state, can drift, and require parallel event handling.

### 8.2 The Solution: CapsuleStateHolder

A single shared state holder that processes agent events and emits `CapsuleMode`. All renderers subscribe.

```
AgentSession.events
        ↓
ServiceOverlayController (event routing)
        ↓
CapsuleStateHolder
   ├── mode: StateFlow<CapsuleMode>
   ├── thought: the current thought text
   ├── context: which context is currently active
   └── platformMode: A11y or VD
        ↓                    ↓                      ↓
SmartCapsuleManager     SmartCapsuleCompose     StatusIslandManager
(overlay, View-based)   (in-app, Compose)       (island, View-based)
```

### 8.3 CapsuleStateHolder API

```kotlin
class CapsuleStateHolder {
    val mode: StateFlow<CapsuleMode>        // Single source of truth
    val context: StateFlow<CapsuleContext>   // Where capsule is currently shown
    val platformMode: PlatformMode           // A11y or VD

    // Event handlers (called by ServiceOverlayController)
    fun onTaskStarted(taskId: String, input: String)
    fun onThoughtUpdate(thought: String)
    fun onTakeoverRequested()
    fun onTakeoverConfirmed()
    fun onResumed()
    fun onSupplementConfirmed()
    fun onAskUser(type: AskUserType, message: String, callId: String)
    fun onTaskCompleted(reason: CompletionReason)
    fun onError(message: String)

    // Context tracking
    fun setContext(context: CapsuleContext)
}

enum class CapsuleContext {
    MAIN_APP,        // Context A
    SCREEN_VIEWING,  // Context B
    BACKGROUND       // Context C
}
```

### 8.4 What Changes

| Component | Before | After |
|-----------|--------|-------|
| **InputDock** | Reads `InputState` from `ChatViewModel` | Deprecated. Replaced by `SmartCapsuleCompose` which shows Row 3 only when Hidden, all rows otherwise. |
| **ChatViewModel** | Manages `InputState` | Reads `CapsuleMode` from `CapsuleStateHolder`. `InputState` removed. |
| **SmartCapsuleManager** | Computes own `CapsuleMode` internally | Subscribes to `CapsuleStateHolder.mode`. Rendering only. |
| **ServiceOverlayController** | Calls capsuleManager methods directly | Calls `CapsuleStateHolder` methods. Decides which renderer to activate based on context + platformMode. |
| **StatusIslandManager** | Receives status strings | Reads thought from `CapsuleStateHolder.mode`. Simpler API. |

### 8.5 InputState Migration

The old `InputState.Working` → replaced by any non-Hidden `CapsuleMode`.
The old `InputState.Idle` → replaced by `CapsuleMode.Hidden`.

```kotlin
// Old
val isWorking = uiState.inputState == InputState.Working

// New
val isTaskActive = capsuleMode !is CapsuleMode.Hidden
```

### 8.6 SupplementInput Mode Removal

With Row 3 always providing a text input, `CapsuleMode.SupplementInput` is **deprecated**:

- In main app: Row 3 is always visible. User types and taps [补充]. No mode change needed.
- In overlay: Row 3 is visible. When user taps the text field, focus/keyboard management activates. Tapping [补充] sends the message. No `SupplementInput` transition.

The `SupplementInput` state was needed because the old 2-row layout had to swap Row 2 controls for a text field. With the 3-row layout, the text field has its own dedicated row.

Updated `CapsuleMode`:
```
CapsuleMode
├── Running(thought: String)
├── TakeoverPending(lastThought: String)
├── Takeover(lastThought: String)
├── WaitingForInput(question: String, callId: String)
├── WaitingForAction(instruction: String, callId: String)
├── Done(message: String)
├── Error(message: String)
└── Hidden
```

`SupplementInput` is removed. Six states instead of eight.

---

## 9. Capsule Display Rules Per Context

### 9.1 A11y Mode

| User location | Rows visible | Capsule type |
|--------------|-------------|-------------|
| In main app, no task | Row 3 only | Compose (embedded) |
| In main app, task active | Rows 1+2+3 | Compose (embedded) |
| In other app, task active | Rows 1+2+3 + EdgeGlow | System overlay (View-based) |
| In main app, returns from other app | Rows 1+2+3 | Compose (overlay → embedded) |

When the app enters foreground with an active task: hide overlay capsule (the in-app Compose capsule shows the same state). When the app goes to background with an active task: show overlay capsule.

### 9.2 VD Mode

| User location | Rows visible | Nav buttons | Capsule type |
|--------------|-------------|-------------|-------------|
| In main app, no task | Row 3 only | — | Compose (embedded) |
| In main app, task active | Rows 1+2+3 | [1] [3] | Compose (embedded) |
| In VD viewer | Rows 1+2+3 | [1] [2] | System overlay |
| On own screen (background) | — (island only) | — | Status Island |
| Island tapped (expanded) | Rows 1+2+3 | [1] [2] [3] | System overlay |
| ask_user on own screen | Rows 1+2+3 (expanded) | [1] [2] [3] | System overlay |

---

## 10. Transition Animations

### 10.1 Row 3 Only → Full Capsule (Main App)

| Transition | Animation |
|------------|-----------|
| Task starts (Row 3 → Rows 1+2+3) | Rows 1+2 expand upward above Row 3 (250ms, ease-out). Content fades in. |
| Task ends (Rows 1+2+3 → Done → Row 3) | Done shows for 3s, then Rows 1+2 collapse (200ms). Row 3 button switches back to [发送]. |
| Task stopped | Rows 1+2 collapse immediately (200ms). |

### 10.2 Island → Expanded Capsule (VD Background)

| Transition | Animation |
|------------|-----------|
| Island tap → Capsule appears | Capsule slides up from bottom (200ms, ease-out). |
| [1] ⊖ → Capsule dismisses | Capsule slides down + fades out (200ms). |
| Tap outside → Capsule dismisses | Same as [1]. |

### 10.3 Context Switches

| Switch | Animation |
|--------|-----------|
| Main App → VD Viewer | Standard activity transition. Overlay capsule appears. |
| VD Viewer → Main App | Standard activity transition. Overlay hides, in-app capsule appears. |
| VD Viewer → Island | Viewer closes, capsule overlay hides, island visible. |
| Island → VD Viewer | Capsule overlay hides, viewer opens with overlay capsule. |

---

## 11. Edge Cases

### 11.1 Task Starts While User Is Typing

The user is typing in Row 3. They tap [发送] → task starts → Rows 1+2 expand, Row 3 button changes to [补充], text field clears. Normal flow.

If a background session starts unexpectedly (defensive): Rows 1+2 expand, Row 3 switches to supplement mode. Any unsent text stays in the field — the user can tap [补充] to send it as a supplement.

### 11.2 Context Switch While Typing Supplement

User is typing a supplement in Row 3 (main app). They press Home (A11y) or tap [1] (VD).

**Behavior**: The typed text is discarded. In the new context (overlay), Row 3 shows an empty text field.

**Rationale**: Moving contexts is an explicit user action. Preserving typed text across contexts (Compose → View overlay) adds complexity for minimal benefit.

### 11.3 ask_user While User Is in Main App (A11y)

Agent calls `ask_user`. User is in the main app viewing chat history.

**Behavior**: The in-app capsule expands (expanded body appears). Row 3 switches to answer mode. User types answer in Row 3 and taps [发送]. No need for overlay.

### 11.4 ask_user While User Is in VD Viewer

Agent calls `ask_user`. User is watching the VD viewer.

**Behavior**: The capsule overlay (already showing at the bottom of the viewer) transitions to the expanded layout. Row 3 switches to answer mode. User responds there.

### 11.5 Island Tap During ask_user

Agent called `ask_user`. Capsule is already expanded on the real screen (auto-shown for ask_user). User taps the island.

**Behavior**: No-op — the capsule is already showing. The tap is ignored.

### 11.6 App Goes Foreground During VD Capsule

User is in VD mode, capsule overlay is showing (either from viewer or island tap). User switches to the Android Agent app (via recent apps, not via [2]).

**Behavior**: Overlay capsule hides. In-app capsule shows the same CapsuleMode from CapsuleStateHolder. Seamless switch.

### 11.7 VD Viewer Opens Without Active Task

User somehow opens the VD viewer when no task is active (e.g., from a stale intent).

**Behavior**: No capsule shows (CapsuleMode is Hidden). VD viewer shows the live preview with a simple "No active task" label. Close button available.

### 11.8 Multiple Rapid Navigation

User taps [3] (watch) from the app, then immediately taps [2] (app) from the viewer.

**Behavior**: Standard Android activity lifecycle handles this. Both are intent-based. No special handling needed.

### 11.9 Supplement Confirmation

When user taps [补充] in Row 3:
- Text field clears
- Brief flash "✓ 已收到" (or "✓ 已收到，下一步生效" if agent is mid-turn) appears above Row 3 for 1.5s
- If agent is mid-turn, the supplement takes effect on the next turn

---

## 12. Scope Boundaries (Round 3)

### In scope
- 3-row capsule layout (Section 2)
- Main app capsule replacing InputDock (Section 5)
- VD viewer capsule replacing ViewerCapsule (Section 6)
- Status island tap → expand capsule (Section 7)
- [1][2][3] navigation buttons (Section 4)
- Unified state management via CapsuleStateHolder (Section 8)
- SupplementInput mode removal (Section 8.6)

### Out of scope
| Feature | Why |
|---------|-----|
| Voice input in capsule | Separate feature, separate complexity |
| Capsule drag/repositioning | Fixed position, builds predictability |
| Dark mode capsule | Follow system theme in a later pass |
| Island drag/repositioning | Fixed position at top |
| Island → full capsule slide animation | Simple appear/disappear is sufficient for now |
| Capsule in split-screen / foldable | Phone form factor only |

---

## 13. Success Criteria (Round 3)

| What | Before | After |
|------|--------|-------|
| VD viewer interaction | Close button only | Full capsule with thought + all controls |
| Main app during task | "Agent is working…" + stop | Full capsule with thought + takeover + supplement |
| Navigate app → viewer | Leave app, tap island, hope | [3] 👁 button |
| Navigate viewer → app | Close viewer, open app manually | [2] 📱 button |
| Navigate anything → island | Press Home, hope | [1] ⊖ button |
| Island utility | Tap = viewer, long-press = tiny controls | Tap = full capsule with all controls + navigation |
| State consistency | Two separate state systems | One CapsuleStateHolder, one CapsuleMode |
