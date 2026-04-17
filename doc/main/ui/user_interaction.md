# UI User Interaction

> Pages, components, and user interaction flows.
> Last updated: 2026-02-22 (commit: 2d13bb1)

## Overview

Chat-first conversational interface built with Jetpack Compose and Material 3. The Smart Capsule replaces the traditional input dock, serving as the unified interaction surface across all contexts.

---

## Page Layout

```
┌────────────────────────────────────────────────────────────────┐
│                        ChatScreen                              │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ ChatHeader     │ [≡] ClosePaw [+]                       │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ MessageList   │ User/Agent bubbles, Action cards        │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ SmartCapsule  │ 3-row: status/controls/input            │   │
│  └─────────────────────────────────────────────────────────┘   │
├────────────────────────────────────────────────────────────────┤
│  NavigationDrawer (session history + settings entry)           │
├────────────────────────────────────────────────────────────────┤
│  SettingsSheet (modal bottom sheet)                            │
└────────────────────────────────────────────────────────────────┘
```

---

## Screen Components

| Component | Purpose |
|-----------|---------|
| **ChatHeader** | Menu (≡) left, title center, new chat (+) right (visible when messages exist) |
| **MessageBubble** | User/Agent message bubbles (asymmetric corner shapes) |
| **StreamingText** | Text with blinking cursor (530ms alpha animation) |
| **ThinkingIndicator** | 3 animated dots (staggered 200ms delay, 600ms alpha cycle) |
| **ActionCard** | Tool execution card with state-dependent styling |
| **EmptyState** | First-launch with SmartToy icon + 3 suggestion chips |
| **SmartCapsuleCompose** | 3-row capsule: status dot + thought, controls, input |

### ActionCard States

| State | Background | Border | Icon |
|-------|-----------|--------|------|
| Proposed | Surface | Outline | None |
| Executing | Blue | Blue | Spinner |
| Success | Teal | Teal | Checkmark |
| Failed | Red | Red | X |
| Skipped | Surface | Dashed outline | None |

### EmptyState Suggestion Chips

- "Check my unread emails"
- "Turn on Do Not Disturb"
- "Search for nearby restaurants"

---

## Session History

### Navigation Drawer

Opens via menu button (≡). Contains session history and settings access.

```
┌─────────────────────────────────┐
│  Sessions                   [X] │
├─────────────────────────────────┤
│  [ + New Conversation ]         │
├─────────────────────────────────┤
│  ┌───────────────────────────┐  │
│  │ "Check my email and..."   │  │
│  │  5 messages · 2 hours ago │🗑│
│  └───────────────────────────┘  │
│  ...                            │
├─────────────────────────────────┤
│  ⚙ Settings                     │
│    gpt-5.2 · v1.0               │
└─────────────────────────────────┘
```

- Session items: title (2 lines max) + message count + relative time + delete icon
- Settings entry at bottom: icon + model name + app version

### Relative Time Formatting

> See: `ui/session/TimeUtils.kt`

| Time Difference | Output |
|----------------|--------|
| < 1 minute | "Just now" |
| 1-59 minutes | "5 minutes ago" |
| 1-23 hours | "2 hours ago" |
| 1 day | "Yesterday" |
| 2-6 days | "3 days ago" |
| 7+ days (same year) | "Jan 15" |
| Different year | "Jan 15, 2025" |

---

## Event → UI Mapping

| AgentEvent | UI Update |
|------------|-----------|
| `TaskStarted` | Add user message + agent message (Thinking) |
| `TurnStarted` | Clear streaming buffer |
| `MessageDelta` | Append to agent bubble, show streaming cursor |
| `ActionProposed` | Add action card (Proposed state) |
| `ActionExecuted` | Update action card state + result |
| `TaskCompleted` | Append completion text, mark bubble Complete. Session enters Hot Idle (follow-up available). |
| `SessionError` | Mark bubble Complete |
| `SupplementReceived` | Add user message for supplement |
| `ThoughtUpdate` | Update capsule thought text |
| `AskUser` | Capsule transitions to WaitingForInput/WaitingForAction |

---

## User Flows

### Send Task

```
User types in capsule input → taps Send
    │
    ├──► Agent message appears (Thinking indicator)
    │
    ├──► Capsule shows Running mode (blue dot + thought)
    │
    ├──► Edge Glow activates (when outside app, A11y mode)
    │
    ├──► Action cards appear as tools execute
    │         │
    │         └──► Click/swipe visualizations on screen
    │
    └──► Agent response streams in message bubble
                │
                └──► Task complete, capsule returns to idle
                     (session stays alive in Hot Idle for follow-up)
```

> See: [Session User Flows](session/user_flows.md) for full session lifecycle, follow-up tasks, reload, and shutdown flows.

### Takeover Flow

```
User taps Takeover button in capsule
    │
    ├──► CapsuleMode → TakeoverPending (amber dot, "Handing over...")
    │
    ├──► Agent finishes current action
    │
    ├──► CapsuleMode → Takeover (amber dot, shows Resume button)
    │
    ├──► User interacts with phone directly
    │
    └──► User taps Resume → CapsuleMode → Running (blue dot)
```

### ask_user Flow

```
Agent calls ask_user tool
    │
    ├──► QUESTION type:
    │    └── Capsule → WaitingForInput (shows question, input field focused)
    │        └── User types answer → taps Send → response delivered
    │
    └──► ACTION type:
         └── Capsule → WaitingForAction (shows instruction, Done button)
             └── User performs action → taps Done → agent resumes
```

### Supplement Flow

```
While agent is running (Running/Takeover mode):
    │
    └──► User types in capsule Row 3 input → taps "Supplement"
         │
         ├──► Op.Supplement(text) sent to session
         │
         ├──► Transient confirmation: "✓ Received" (between turns)
         │    or "✓ Received, will apply next step" (mid-turn, 2s display)
         │
         └──► Text injected into conversation history for next LLM call
```

### VD Handoff Flow

```
Task completes with GOAL_ACHIEVED in VirtualDisplay mode:
    │
    ├──► AgentService relaunches foreground app on default display
    │
    └──► VirtualDisplayViewerActivity opens (live VD preview)
         │
         ├──► Capsule overlay shown (SCREEN_VIEWING context)
         │
         └──► User views result, can send follow-up or dismiss
```

---

## Related Docs

- [State Machine](capsule/state_machine.md) - Formal state vector, transition rules, visibility decision machine
- [User Flows](capsule/user_flows.md) - Location x platform interaction matrix
- [Session State Machine](session/state_machine.md) - Session lifecycle state machine (Hot Idle, checkpoint)
- [Session User Flows](session/user_flows.md) - Session lifecycle user flows (follow-up, reload, shutdown)
- [Tech Design](tech_design.md) - Technical implementation
- [Style Guide](style.md) - Design system
- [Overlay](overlay.md) - Smart Capsule, Edge Glow, Action Visualizer
- [Protocol](../protocol/overview.md) - Event details
