# Smart Capsule V2 — Product & UX Design

## 0. Executive Summary

The Smart Capsule is the agent's face. It's the one persistent element the user sees while the agent operates their phone. Today it's a dumb status bar. We're turning it into a collaboration surface — where the user can see, intervene, guide, and be asked.

Four capabilities: **thought** (see what agent is doing), **takeover** (grab the phone), **supplement** (say something mid-task), **ask_user** (agent asks for help).

---

## 1. The Problem

### Who

Every user, every session.

### What's broken

The agent is a black box. User says "find cheap pencil skirts on Temu", agent runs, user watches a ghost operate their phone with no explanation. Three failure modes:

1. **Opacity**: The agent scrolls past good results. The user has no idea why. Did it see them? Is it looking for something cheaper? No way to know.

2. **No exit ramp**: The agent hits a login screen. It can't type the password. It retries, fails, retries. The user watches helplessly. There's no way for the agent to say "hey, please log in" and hand over.

3. **No mid-course correction**: The user realizes they wanted a black skirt, not any skirt. The agent is already three screens deep. There's no way to say "by the way, I want black" without stopping and starting over.

4. **One-way street**: The agent finds two options at different prices. A human would ask "which one?" The agent picks one arbitrarily or gives up. There's no channel to ask.

### Why it matters

Trust. If the user can't see what's happening, can't course-correct, and can't help when asked, they won't use the agent for anything that matters. The agent stays a toy instead of becoming a tool.

### Success criteria

| Metric | Before | After |
|--------|--------|-------|
| User knows what agent is doing | Never | Always (thought line) |
| User can intervene without stopping | No | Yes (takeover + supplement) |
| Agent can ask for help | No | Yes (ask_user) |
| Tasks requiring login/auth | Fail | Succeed (ask_user + takeover) |
| User restarts task due to mis-direction | Often | Rarely (supplement) |

---

## 2. Design Principles

**1. The capsule is always honest.**
Show what the agent is actually doing. One line. No jargon. In the user's language.

**2. The user is always in control.**
Stop, takeover, supplement — available at all times during a task. No locked states. No "please wait."

**3. One widget, everywhere.**
The capsule looks and works the same whether it's inside the app, floating over another app, or expanded from the status island. Consistency builds muscle memory.

**4. Flat, not nested.**
The capsule has a small number of clearly distinct modes. Not a tree of states. Not modals inside modals. The user should always know where they are with one glance.

**5. Delete before you add.**
Remove the "Open App" button. Remove emoji status strings. Every removal makes the remaining features stronger.
---

## 3. Capsule Anatomy

### 3.1 Default Layout (Running State)

```
┌──────────────────────────────────────────────────────┐
│  ● 记录包臀裙信息继续滑动                               │  ← Row 1: status dot + thought
├──────────────────────────────────────────────────────┤
│  [💬 补充]      [✋ 接管]                   [⏹ 停止]  │  ← Row 2: controls
└──────────────────────────────────────────────────────┘
```

**Row 1 — Thought Line:**
- Status dot (8dp, color-coded, left-aligned)
- One line of text showing agent's current intent
- Full width, single line, ellipsize end
- Tappable → opens main app (chat view)

**Row 2 — Control Buttons:**
- Three pill-shaped buttons: icon + text label
- Left: 补充 (supplement) — secondary style
- Center: 接管/继续 (takeover/resume) — primary style
- Right: 停止 (stop) — destructive style
- Fixed positions, never reorder

### 3.2 Sizing

| Property | Value |
|----------|-------|
| Width | Screen width − 32dp (16dp margin each side) |
| Row 1 height | 36dp |
| Row 2 height | 44dp |
| Total height | ~88dp (including padding + divider) |
| Corner radius | 24dp (capsule shape) |
| Background | White, subtle shadow (elevation 4dp) |
| Position | Bottom of screen, 8dp above nav bar |

### 3.3 Button Specs

| Button | Icon | Label | Style | Always visible |
|--------|------|-------|-------|----------------|
| Supplement | 💬 | 补充 | Outlined pill, muted | Running, Takeover |
| Takeover | ✋ | 接管 | Filled pill, primary | Running only |
| Resume | ▶ | 继续 | Filled pill, primary | Takeover, WaitingForAction |
| Stop | ⏹ | 停止 | Outlined pill, muted-red | Always |
| Send | → | 发送 | Filled pill, primary | Input modes |
| Done | ✅ | 完成 | Filled pill, primary | WaitingForAction |
| Cancel | ✕ | (icon only) | Ghost button | Input modes |

### 3.4 Button Enable/Disable Rules

| Button | Enabled States | Disabled States | Notes |
|--------|---------------|-----------------|-------|
| **补充** | Running, Takeover | TakeoverPending, WaitingForInput, Done, Error | Disabled when user is already providing input |
| **接管** | Running | TakeoverPending, Takeover, WaitingFor*, Done, Error | Only when agent is actively executing |
| **继续** | Takeover | WaitingForInput (until answered) | Only when user has control |
| **停止** | Running, TakeoverPending, Takeover, WaitingFor* | Done, Error, Hidden | Always available during active task |
| **发送** | SupplementInput (text non-empty), WaitingForInput (text non-empty) | When input is empty | Input validation |
| **完成** | WaitingForAction | — | Primary CTA |

All buttons: 300ms debounce. No button should ever produce zero feedback — either a state change or a visual press response.

---

## 4. Capsule States (State Machine)

The capsule has **six states**. Each is a flat, distinct configuration — not nested, not recursive.

### 4.1 State Diagram

```
                              ┌───────────────┐
                              │    Hidden      │ (no active task)
                              └───────┬───────┘
                                      │ TaskStarted
                                      ▼
                    ┌─────────────────────────────────┐
            ┌──────►│           Running                │◄──────────────┐
            │       └──┬──────┬────────┬──────────┬───┘               │
            │          │      │        │          │                   │
            │    user  │  user│  agent │    agent │                   │
            │   taps   │ taps │ calls  │   calls  │                   │
            │  继续     │ 接管 │ ask_user│ ask_user │                   │
            │          │      │(question)│(action) │                   │
            │          ▼      │        ▼          ▼                   │
            │   ┌──────────┐  │  ┌───────────┐  ┌──────────────┐     │
            │   │ Takeover │  │  │ Waiting   │  │ Waiting      │     │
            │   │ Pending  │  │  │ ForInput  │  │ ForAction    │     │
            │   └────┬─────┘  │  └─────┬─────┘  └──────┬───────┘     │
            │        │ current│        │ user sends     │ user taps   │
            │        │ action │        │ answer         │ 完成        │
            │        │ done   │        └────────────────┴─────────────┘
            │        ▼        │
            │   ┌──────────┐  │
            ├───│ Takeover │  │
            │   └──────────┘  │
            │                 │
            │           user  │
            │          types  │
            │           补充   │
            │          ┌──────▼──────┐          (transient overlay
            │          │ Supplement  │           on Running or
            └──────────│ Input       │           Takeover)
             on send/  └─────────────┘
             cancel
```

Terminal transitions (from any active state):
- User taps **停止** → `Stopped` (capsule shows brief "已停止", then hides)
- Agent completes → `Done` (capsule shows "✓ 已完成", auto-hides after 3s)
- Error → `Error` (capsule shows error message, stays until dismissed)

### 4.2 State Details

#### State: Running

The default. Agent is working.

| Aspect | Detail |
|--------|--------|
| **Row 1** | Blue dot (pulsing) + agent thought text |
| **Row 2** | [补充] [接管] [停止] |
| **Thought source** | `agent_thought` from tool call parameters (see Section 5) |
| **Thought fallback** | If no agent_thought: tool description → "思考中..." |
| **Entry** | TaskStarted, or resume from Takeover/WaitingFor* |
| **Exit** | User taps 接管/补充/停止, or agent calls ask_user, or task completes |

#### State: TakeoverPending

Transient state: user requested takeover, but agent is mid-action.

| Aspect | Detail |
|--------|--------|
| **Row 1** | Amber dot (static) + "正在交接..." (handing over...) |
| **Row 2** | [补充 disabled] [接管 disabled] [停止] |
| **Entry** | User tapped 接管 while agent is mid-turn (LLM streaming or tool executing) |
| **Exit** | Current action completes → automatically transitions to Takeover |
| **Behavior** | No user interaction needed. Wait for current action to finish. Stop is always available. |

**Why not cancel immediately:** Interrupting a half-finished gesture can leave the screen in a corrupted state. Interrupting an LLM stream wastes the API call. Let the current operation complete; it takes at most a few seconds.

#### State: Takeover

User has grabbed control. Agent is paused.

| Aspect | Detail |
|--------|--------|
| **Row 1** | Amber dot (static) + last thought text (dimmed, 60% opacity) |
| **Row 2** | [补充] [▶ 继续] [停止] |
| **Entry** | TakeoverPending completes, or user tapped 接管 while agent was between turns (immediate) |
| **Exit** | User taps 继续 → Running (agent re-perceives screen), or 停止/补充 |
| **Behavior** | Agent paused. User operates phone freely. On resume, agent captures fresh screen state and plans from scratch. |

#### State: SupplementInput

User is typing a message to inject. Transient — overlays Running or Takeover.

| Aspect | Detail |
|--------|--------|
| **Row 1** | "补充你的想法" (prompt label) + [✕] cancel button |
| **Row 2** | [text input field] [发送 →] |
| **Keyboard** | Overlay drops `FLAG_NOT_FOCUSABLE`, keyboard rises |
| **Entry** | User tapped 补充 |
| **Exit** | User taps 发送 → message injected, return to previous state. User taps ✕ → cancel, return to previous state. |
| **Behavior** | On send: message added to agent history. Agent sees it on its next turn. If currently Running, agent continues — supplement is passive. If currently in Takeover, supplement is added and state stays in Takeover. |

#### State: WaitingForInput

Agent asked a question. Needs a text answer.

| Aspect | Detail |
|--------|--------|
| **Layout** | Capsule expands upward to ~160dp |
| **Header** | 💬 "等待答复" (Waiting for reply) |
| **Body** | Agent's question text (max 3 lines, scrollable if longer) |
| **Input** | [text input field] [发送 →] |
| **Bottom** | [补充] (disabled) [停止] |
| **Keyboard** | Auto-raises on entry |
| **Entry** | Agent called `ask_user(type="question", message="...")` |
| **Exit** | User taps 发送 → answer delivered to agent tool, agent resumes Running. User taps 停止. Timeout (5 min) → agent gets timeout error, continues. |
| **Behavior** | The agent's tool call is suspended, waiting for response. User's answer is returned as the tool result. 补充 is disabled — the input field IS the response channel. |

#### State: WaitingForAction

Agent asked the user to do something on the phone (login, permission, captcha).

| Aspect | Detail |
|--------|--------|
| **Layout** | Capsule expands upward to ~120dp |
| **Header** | ✋ "操作手机" (Operate phone) |
| **Body** | Agent's instruction text (max 2 lines) |
| **Bottom** | [✅ 完成] (primary, centered) [停止] (right) |
| **Entry** | Agent called `ask_user(type="action", message="...")` |
| **Exit** | User taps 完成 → agent gets "done" result, re-perceives screen, resumes Running. User taps 停止. Timeout (5 min) → agent gets timeout error. |
| **Behavior** | User operates the phone to fulfill the instruction. When done, tap 完成. Agent captures fresh screen and continues from there. No supplement button — the user is busy operating the phone. |

#### State: Done

Task completed successfully. Transient.

| Aspect | Detail |
|--------|--------|
| **Row 1** | Teal dot + "✓ 已完成" |
| **Row 2** | (no buttons) |
| **Duration** | Auto-hides after 3 seconds |

#### State: Error

Something went wrong. Stays until dismissed.

| Aspect | Detail |
|--------|--------|
| **Row 1** | Red dot + error message (one line) |
| **Row 2** | [关闭] (dismiss) |

### 4.3 Status Dot Colors

| State | Color | Hex | Animation |
|-------|-------|-----|-----------|
| Running (thinking) | Blue | `#2563EB` | Pulsing |
| Running (executing) | Light Blue | `#3B82F6` | Pulsing |
| TakeoverPending | Amber | `#F59E0B` | Static |
| Takeover | Amber | `#F59E0B` | Static |
| WaitingForInput | Purple | `#7C3AED` | Slow pulse |
| WaitingForAction | Amber | `#F59E0B` | Static |
| Done | Teal | `#0D9488` | Static |
| Error | Red | `#EF4146` | Static |

---

## 5. Agent Thought

### 5.1 The Design Decision

**Use `agent_thought` from tool parameters as the thought source.** Every tool call already includes an `agent_thought` parameter — a brief, structured, intentional explanation of what the agent is about to do. This is more reliable and predictable than raw LLM streaming text, which can contain verbose reasoning or markdown.

`agent_thought` is the single source of truth for the capsule thought line.

### 5.2 Prompt Instruction

Added to all agent system prompts (in English, as all prompts are English):

```
## Agent Thought
Every tool call MUST include `agent_thought` — a brief user-facing explanation of what you're about to do.
This text is shown directly to the user on a single line of their screen.
Rules:
- One short sentence. No markdown. No internal reasoning.
- Under 40 characters.
- Be concrete: "Opening Taobao to search" not "Executing search operation"
- Write in the same language as the user's goal.

Examples: "打开淘宝" / "向下滑动查看更多" / "Comparing prices" / "点击第一个结果"
```

### 5.3 Display Pipeline

```
Tool call received
  → Extract agent_thought from first selected tool call
  → Sanitize (trim whitespace, truncate to 40 chars + "...")
  → Emit ThoughtUpdate event
  → Capsule Row 1 text update
```

**Sanitizer:** Simple and minimal.
1. Trim leading/trailing whitespace
2. If length > 40 characters: truncate and append "..."
3. If empty: use fallback (see 5.4)

**Update cadence:**
- Turn starts → "思考中..." (thinking)
- Tool call selected → agent_thought displayed
- Stays on display until next turn starts or task ends

### 5.4 Fallback Priority

If `agent_thought` is missing or empty:

1. **Primary**: `agent_thought` from the selected tool call
2. **Fallback**: Tool action description (e.g., "Click '加入购物车'")
3. **Last resort**: "思考中..."

---

## 6. Takeover & Resume

### 6.1 Mental Model

**接管** = "I'll drive." User takes the wheel. Agent stops, waits.
**继续** = "Your turn again." Agent re-examines the screen and plans fresh.

### 6.2 Flow

```
User taps [接管]
  │
  ├── If agent is between turns:
  │     → Immediate takeover. Capsule enters Takeover state.
  │
  └── If agent is mid-turn (LLM streaming / tool executing):
        → Capsule shows "正在暂停..." (transitional text)
        → Current action completes (don't interrupt mid-flight)
        → Then enters Takeover state.

User operates phone (browse, type, scroll, login, whatever)

User taps [继续]
  │
  ├── Agent captures fresh screen state
  ├── Queued/pending tool calls from before takeover are DISCARDED
  ├── Agent plans a new turn based on current screen
  └── Capsule returns to Running
```

### 6.3 Key Semantics

- **No stale actions.** When resuming, the agent starts fresh. It sees what the user did and adapts. Pre-takeover queued tool calls are gone.
- **Supplement during takeover.** User can tap 补充 while in takeover to provide context for the agent's next turn (e.g., "I logged in, continue from here").
- **Stop during takeover.** Always available.

---

## 7. Supplement

### 7.1 Mental Model

补充 = "By the way..." User injects a message into the agent's context without interrupting it.

### 7.2 Flow

```
User taps [补充]
  │
  ├── Capsule enters SupplementInput mode
  │     Row 1: "补充你的想法" + [✕]
  │     Row 2: [text input] [发送]
  │     Keyboard rises
  │
  ├── User types message
  │
  └── User taps [发送]
        │
        ├── Message inserted into agent's conversation history
        ├── Capsule returns to previous state (Running or Takeover)
        ├── Keyboard dismissed
        └── Brief confirmation flash: "已收到" (received) for 1.5s
```

### 7.3 What the Agent Sees

The supplement is a user message in history. On the agent's next turn, it appears in context alongside the screen perception and previous turns. The agent naturally incorporates it.

Example: User supplements "我要黑色的" (I want black). Agent's next turn sees this message, observes the current screen, and adjusts its search accordingly.

### 7.4 Timing

- **In Running:** Agent is working. Supplement is passive — it doesn't interrupt the current action. Agent sees it on the next turn.
- **In Takeover:** Agent is paused. Supplement is added to history. When user taps 继续, agent sees the supplement plus the new screen state.
- **Brief confirmation:** Show "已收到" overlay on thought line for 1.5s, then restore thought text.

### 7.5 Keyboard Handling

The overlay is `FLAG_NOT_FOCUSABLE` by default (so it doesn't steal focus from the app the agent is controlling). When entering SupplementInput:

1. Remove `FLAG_NOT_FOCUSABLE` from window params
2. Update window layout
3. Focus the EditText
4. Show soft keyboard

When exiting SupplementInput:

1. Hide keyboard
2. Add `FLAG_NOT_FOCUSABLE` back
3. Update window layout

---

## 8. ask_user

### 8.1 Mental Model

The agent raises its hand: "I need your help." Two flavors:

- **Question**: "Which one do you want?" → User types an answer.
- **Action**: "Please log into your account." → User does it, taps Done.

### 8.2 When to Use (Agent Guidelines)

The agent should call `ask_user` when:
- Login / authentication is required
- User preference is needed (ambiguous choice)
- Permission prompt appears that the agent can't handle
- Captcha or human verification
- Information the agent genuinely can't find on screen

The agent should NOT use `ask_user` for:
- Progress updates (that's what thought text is for)
- Things it can determine from the screen
- Lazy "I'm stuck" fallbacks

### 8.3 Question Flow

```
Agent calls ask_user(type="question", message="请问你想要哪个平台的包臀裙？\n1. Temu $4.98\n2. Shein $2.99")
  │
  ├── Agent tool call suspends (waiting for response)
  │
  ├── Capsule expands to WaitingForInput state:
  │     ┌──────────────────────────────────────────────┐
  │     │  💬 等待答复                                   │
  │     ├──────────────────────────────────────────────┤
  │     │  请问你想要哪个平台的包臀裙？可选：              │
  │     │  1. Temu $4.98  2. Shein $2.99               │
  │     ├──────────────────────────────────────────────┤
  │     │  [text input                    ] [发送 →]   │
  │     ├──────────────────────────────────────────────┤
  │     │                                    [⏹ 停止]  │
  │     └──────────────────────────────────────────────┘
  │
  ├── User types "Temu那个" and taps [发送]
  │
  ├── Response delivered to agent tool → tool returns {"answer": "Temu那个"}
  ├── Agent continues to next turn
  └── Capsule returns to Running
```

### 8.4 Action Flow

```
Agent calls ask_user(type="action", message="请登录您的淘宝账户")
  │
  ├── Agent tool call suspends
  │
  ├── Capsule expands to WaitingForAction state:
  │     ┌──────────────────────────────────────────────┐
  │     │  ✋ 操作手机                                   │
  │     ├──────────────────────────────────────────────┤
  │     │  请登录您的淘宝账户                             │
  │     ├──────────────────────────────────────────────┤
  │     │         [✅ 完成]                   [⏹ 停止]  │
  │     └──────────────────────────────────────────────┘
  │
  ├── User logs in on the phone
  ├── User taps [完成]
  │
  ├── Agent gets {"done": true}
  ├── Agent captures fresh screen, continues
  └── Capsule returns to Running
```

### 8.5 Timeout

If user doesn't respond within **5 minutes**:
- At 4 minutes: capsule shows gentle nudge "还在等待您的回复..." (still waiting...)
- At 5 minutes: tool returns timeout error
- Agent handles gracefully (may retry, skip, or complete with note)
- Capsule returns to Running with thought "未收到回复，继续执行"

### 8.6 One Request at a Time

Only one pending `ask_user` per session. If the agent somehow calls `ask_user` twice (shouldn't happen given tool arbitration), the second call is rejected with an error.

---

## 9. Multi-Context Presence

The Smart Capsule appears in three contexts. Its agent controls (Row 2) are identical everywhere. The difference is where it lives and what navigation options are available.

### 9.1 Context Map

```
┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐
│   (A) Main App      │     │  (B) Screen Viewing  │     │  (C) Background     │
│                     │     │                     │     │                     │
│  ┌───────────────┐  │     │  ┌───────────────┐  │     │  ╭─────────────╮    │
│  │  Chat history │  │     │  │  Screen the   │  │     │  │   Island    │    │
│  │  ...          │  │     │  │  agent is     │  │     │  ╰─────────────╯    │
│  │               │  │     │  │  operating    │  │     │                     │
│  │               │  │     │  │               │  │     │  (user's own        │
│  ╞═══════════════╡  │     │  │               │  │     │   screen below)     │
│  │ Smart Capsule │  │     │  ╞═══════════════╡  │     │                     │
│  └───────────────┘  │     │  │ Smart Capsule │  │     │                     │
│                     │     │  └───────────────┘  │     │                     │
└─────────────────────┘     └─────────────────────┘     └─────────────────────┘
```

| Context | When | Capsule position | Notes |
|---------|------|-----------------|-------|
| **(A) Main App** | User is in the Android Agent app | Embedded at bottom, replacing input dock | Full capsule, all controls |
| **(B) Screen Viewing** | A11y mode: user sees agent operating. VD mode: VD viewer open. | Floating overlay at bottom | Full capsule, all controls |
| **(C) Background** | VD mode only: user is on their own screen | Status Island at top | Compact pill, tap to expand |

### 9.2 Navigation Between Contexts

Each context has a small set of navigation actions to reach the other contexts. These are rendered as **small icon buttons** at the trailing edge of Row 1 (the thought line), clearly secondary to the agent controls in Row 2.

#### Navigation Buttons

| Button | Icon | Action | Meaning |
|--------|------|--------|---------|
| **Minimize** [1] | ⊖ | Minimize capsule to Status Island | "Hide this, I'll check later" |
| **App** [2] | 📱 | Open main app (chat view) | "Show me the full conversation" |
| **Watch** [3] | 👁 | Open screen viewer (VD LiveView) | "Show me what the agent sees" |

#### Which buttons appear where

| Context | Agent idle (waiting for task) | Agent running | Agent paused / waiting |
|---------|------|------|------|
| **(A) Main App, A11y** | — (normal input dock) | — (no nav needed, agent controls visible) | — |
| **(A) Main App, VD** | [1] [3] | [1] [3] | [1] [3] |
| **(B) Screen Viewing, A11y** | [2] | [1] [2] | [1] [2] |
| **(B) Screen Viewing, VD** | [1] [2] | [1] [2] | [1] [2] |
| **(C) Island** | [2] [3] (on expand) | [2] [3] (on expand) | [2] [3] (on expand) |

**Rationale:**
- In A11y mode, no [1] (minimize) when agent is running on the user's screen — user MUST see the capsule. Exception: when agent is idle.
- [2] (App) never shown when already in the app.
- [3] (Watch) never shown when already watching, or in A11y mode (user is already seeing the real screen).
- In the expanded island state, [1] returns to collapsed island.

### 9.3 Status Island (VD Background)

When in VD mode and user is on their own screen, the capsule collapses to a compact pill:

```
╭────────────────────╮
│  ● 打开淘宝搜索...  │
╰────────────────────╯
```

| Interaction | Effect |
|-------------|--------|
| **Tap** | Opens VD viewer (Context B) |
| **Long press** | Expands to full capsule with controls + [2][3] nav |
| **Expanded tap outside** | Collapse back to island |

Status island shows: dot color + truncated thought (24 chars max). No buttons in compact form.

### 9.4 Main App Integration

When a task is active and user is in the main app:

- The capsule **replaces the input dock** at the bottom of the chat screen
- Chat history scrolls above the capsule
- The capsule has the same two-row layout as the overlay
- [补充] on the capsule opens the supplement input (same as overlay behavior)
- This ensures consistency: the widget at the bottom is always the Smart Capsule during a task

When no task is active, the normal input dock returns for starting new tasks.

---

## 10. Edge Cases & Error States

### 10.1 Mid-Turn Takeover

**Scenario:** User taps 接管 while LLM is streaming or a tool is executing.

**Behavior:** Takeover is queued. Capsule shows transitional state: Row 1 changes to "正在暂停..." with amber dot. When the current turn's in-flight operation completes, the takeover activates. Current turn's remaining queued (not-yet-started) tool calls are dropped.

**Why not cancel immediately:** Interrupting a half-finished gesture (e.g., swipe in progress) can leave the screen in a corrupted state. Interrupting an LLM stream wastes the API call. Let the current operation complete; it takes at most a few seconds.

### 10.2 Supplement While Agent Is Thinking

**Scenario:** User sends supplement while LLM is streaming its response.

**Behavior:** The supplement goes into history. The current LLM response is already in-flight — it won't see the supplement. The supplement is visible starting from the **next** turn.

**Feedback:** Show "已收到，下一步生效" (Received, takes effect next step) for 1.5s.

### 10.3 ask_user While User Is Typing Supplement

**Scenario:** User is typing a supplement. Simultaneously, the agent calls ask_user.

**Behavior:** The supplement input completes first (user was already typing). After the supplement is sent, the capsule transitions to the ask_user state. Two sequential interactions, not competing ones.

### 10.4 Network Error During ask_user Wait

**Scenario:** While waiting for user's answer, the session encounters a network error.

**Behavior:** Error takes priority. Capsule transitions to Error state. The pending ask_user is cancelled. User can dismiss error and the capsule hides.

### 10.5 User Closes App During ask_user

**Scenario:** Agent is waiting for user input. User closes the main app or switches away.

**Behavior:** The capsule overlay (or status island) persists. It continues showing the question/instruction. The 5-minute timeout is still running. User can return and respond at any time within the timeout.

### 10.6 Stop During Any State

**Scenario:** User taps 停止 at any point.

**Behavior:** Always honored. Agent loop stops. Any pending tool call (including ask_user) is cancelled. Capsule shows "已停止", hides after 2 seconds. Non-negotiable — stop means stop.

### 10.7 Empty or Garbage Thought Text

**Scenario:** LLM emits very long reasoning, non-sensical text, or nothing before tools.

**Behavior:** The sanitizer handles it:
- Very long → truncated to 40 chars + "..."
- Multiline → collapsed to single line
- Empty → fallback to "思考中..."
- Only whitespace → fallback
- Markdown/code → displayed as-is after collapse (it'll look a bit odd but won't break)

### 10.8 Rapid Repeated Button Taps

**Scenario:** User taps 接管 three times quickly.

**Behavior:** Debounced. First tap registers, subsequent taps within 300ms are ignored. Button shows pressed state on first tap.

### 10.9 Partial Failure

**Scenario:** One tool execution fails, but the task is not necessarily impossible.

**Behavior:** A single step failure does not auto-terminate the task. The agent sees the error in its observation and decides the next action. The capsule continues showing Running state. The agent may retry, skip, ask_user, or call complete_task with a failure note.

---

## 11. Scope Boundaries

What this design explicitly does NOT include:

| Out of scope | Why |
|---|---|
| **Voice input** | Text-only for V2. Voice is a separate feature with its own complexity (noise, language detection, streaming). |
| **Capsule drag/repositioning** | The capsule sits at the bottom. Fixed position builds predictability. |
| **Thought history in overlay** | Only latest thought shown. Full history lives in the main app's chat view. |
| **Capsule expand/collapse animation for Running↔Takeover** | Same height for both. Only WaitingFor* states expand. Keeps transitions fast. |
| **Multi-language auto-detection for thought** | Thought language matches user's goal language (via prompt instruction). No runtime detection. |
| **Dark mode capsule** | Follow system dark mode in a later pass. V2 uses light capsule only. |
| **Smart timeout scaling for ask_user** | Fixed 5-minute timeout. Tune later with usage data. |
| **Capsule for tablet/foldable** | Phone form factor only for now. |

---

## 12. Animation & Polish

### 12.1 State Transitions

| Transition | Animation |
|------------|-----------|
| Running → Takeover | Dot color crossfade (blue→amber, 200ms). Button label crossfade. |
| Running → WaitingFor* | Capsule height expand (200ms, ease-out). Content fade in (150ms). |
| WaitingFor* → Running | Capsule height collapse (200ms, ease-out). Content fade out (100ms). |
| Any → SupplementInput | Row 2 content crossfade (150ms). Keyboard rise handled by system. |
| SupplementInput → Previous | Row 2 content crossfade (150ms). |
| Done → Hidden | Capsule fade out + slide down (300ms, after 3s delay). |

### 12.2 Thought Text Updates

- New text appears character by character as MessageDelta streams (already happening)
- On turn boundary: brief fade-to-fallback (100ms), then new text streams in
- Truncation ellipsis appears smoothly (not a jarring cut)

### 12.3 Status Dot

- **Pulsing** (Running): scale 1.0 → 1.3 → 1.0, 1.5s cycle, ease-in-out
- **Static** (all other states): no animation, just color

---

## 13. Copy & Microcopy

All user-facing text in the capsule. Keep it short, warm, clear.

| Context | Text | Note |
|---------|------|------|
| Thinking fallback | 思考中... | Animated ellipsis |
| Pausing transition | 正在暂停... | Shown during mid-turn takeover |
| Supplement prompt | 补充你的想法 | Row 1 in SupplementInput |
| Supplement confirmation | 已收到 | Flash 1.5s |
| Supplement timing note | 已收到，下一步生效 | When sent during active LLM streaming |
| WaitingForInput header | 等待答复 | With 💬 icon |
| WaitingForAction header | 操作手机 | With ✋ icon |
| Timeout nudge (4min) | 还在等待您的回复... | Gentle reminder |
| Timeout (5min) | 未收到回复，继续执行 | Agent continues |
| Task done | ✓ 已完成 | Teal dot |
| Task stopped | 已停止 | Brief, then hide |
| Error prefix | ⚠ | Followed by error message |
| Dismiss error button | 关闭 | |
| Done button (ask action) | ✅ 完成 | Primary CTA |
| Send button | 发送 | Primary CTA |

---

## 14. Data Model (for reference)

The capsule's visual state is fully determined by a single sealed interface:

```
CapsuleMode
├── Running(thought: String)
├── TakeoverPending(lastThought: String)
├── Takeover(lastThought: String)
├── SupplementInput(previousMode: CapsuleMode)
├── WaitingForInput(question: String, callId: String)
├── WaitingForAction(instruction: String, callId: String)
├── Done(message: String)
├── Error(message: String)
└── Hidden
```

One `CapsuleMode` value drives the entire UI. No ambient state, no side channels, no `isLoading && !isPaused && hasThought` boolean soup. You look at the mode, you know exactly what to render.

---

## 15. Success Metrics (Post-Ship)

| Metric | Target | How to measure |
|--------|--------|----------------|
| Tasks completed without user stop | +30% vs current | Session logs |
| Tasks requiring login that succeed | >80% | ask_user usage + task completion |
| User uses supplement | >20% of sessions | Op.Supplement count |
| User uses takeover | >15% of sessions | Op.Pause count during tasks |
| Agent ask_user response time | <30s median | Time from ask_user to resolution |
| Capsule-related bug reports | <5 in first 2 weeks | User feedback |
