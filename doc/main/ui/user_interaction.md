# UI User Interaction

> Pages, components, and user interaction flows.
> Last updated: 2026-02-04 (commit: da83b53ba4e849e52b45158a3485261d7399facb)

## Overview

The Android Agent uses a **chat-first conversational interface** built with Jetpack Compose and Material 3. Designed around "Invisible Intelligence" — the interface disappears, leaving only the conversation.

| Goal | Implementation |
|------|----------------|
| **Chat-First** | Conversational UI with streaming |
| **Beautiful UI** | Material 3 with premium polish |
| **Edge-to-Edge** | Full screen with proper insets |
| **Reactive** | State-driven, real-time streaming |
| **Ubiquitous** | Overlay follows users across apps |
| **Session History** | Browse and resume past conversations |

---

## Page Layout

```
┌────────────────────────────────────────────────────────────────┐
│                        ChatScreen                               │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ ChatHeader     │ [≡] Android Agent [+]                  │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ TaskBanner    │ "Working on: ..." with status dot       │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ MessageList   │ User/Agent bubbles, Action cards        │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ InputDock     │ Text input + Send/Stop button           │   │
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
| **ChatHeader** | Menu (≡) left, title center, new chat (+) right |
| **TaskBanner** | Current task context with animated status dot |
| **MessageBubble** | User/Agent message bubbles |
| **StreamingText** | Text with blinking cursor during streaming |
| **ThinkingIndicator** | Animated dots while processing |
| **ActionCard** | Tool execution cards with status states |
| **InputDock** | Input field with Send/Stop toggle |
| **EmptyState** | First-launch experience with suggestions |

---

## Settings Sheet

Modal bottom sheet with custom header (no drag handle).

**Settings Items:**
- LLM backend (Cloud/OpenAI vs Local)
- Cloud model selection (GPT-5.2, GPT-5.2 Pro)
- Local model selection with download status
- Execution mode (`Basic` standalone or `Pro` planner+executor)
- Screenshot input toggle
- API key (cloud only)
- Max turns (10, 20, 50)
- Accessibility service status
- Overlay permission status
- Debug mode toggle
- About & version info

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
│  Recent                         │
│  ───────────────────────────────│
│  ┌───────────────────────────┐  │
│  │ "Check my email and..."   │  │
│  │  5 messages • 2 hours ago │🗑│
│  └───────────────────────────┘  │
│  ...                            │
├─────────────────────────────────┤
│  ⚙ Settings                     │
│    gpt-5.2 • v1.0               │
└─────────────────────────────────┘
```

### Relative Time Formatting

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
| `TaskStarted` | Add user message, show Task Banner, disable input |
| `TurnPhaseChanged` | Update Task Banner subtitle |
| `MessageDelta` | Append to agent bubble, show streaming cursor |
| `ActionExecuted` | Add action card to agent bubble |
| `TaskCompleted` | Mark bubble complete, enable input |
| `SessionError` | Show error in Task Banner |

### User Flow

```
User sends message
    │
    ├──► Task Banner shows "Working on: ..."
    │
    ├──► Edge Glow activates (when outside app)
    │
    ├──► Smart Capsule appears (when in other apps)
    │
    ├──► Action cards appear as tools execute
    │         │
    │         └──► Click/Swipe visualizations on screen
    │
    └──► Agent response streams in message bubble
                │
                └──► Task complete, input re-enabled
```

---

## Related Docs

- [Tech Design](tech_design.md) - Technical implementation
- [Style Guide](style.md) - Design system
- [Overlay](overlay.md) - Smart Capsule, Edge Glow, Action Visualizer
- [Protocol](../protocol/protocol.md) - Event details
