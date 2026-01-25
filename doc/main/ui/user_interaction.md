# UI User Interaction

> This document describes the UI pages, components, user behaviors, and interaction flows.

## Table of Contents

1. [Overview](#overview)
2. [Key Components](#key-components)
3. [Session History UI](#session-history-ui)
4. [Smart Capsule](#smart-capsule)
5. [Edge Glow](#edge-glow)
6. [Action Visualizer](#action-visualizer)
7. [Event → UI Mapping](#event--ui-mapping)

---

## Overview

The Android Agent uses a **chat-first conversational interface** built with Jetpack Compose and Material 3. The UI is designed around the principle of "Invisible Intelligence" — the interface disappears, leaving only the conversation.

| Goal | Implementation |
|------|----------------|
| **Chat-First** | Conversational UI with streaming responses |
| **Modern DX** | Declarative UI with Compose |
| **Beautiful UI** | Material 3 with premium polish |
| **Edge-to-Edge** | Full screen utilization with proper insets |
| **Reactive** | State-driven with real-time streaming |
| **Ubiquitous** | Smart Capsule overlay follows users across apps |
| **Session History** | Browse and resume past conversations |

---

## Key Components

### Page Layout

```
┌────────────────────────────────────────────────────────────────┐
│                        MainActivity                             │
│  (Compose entry point, ChatViewModel, event collection)         │
├────────────────────────────────────────────────────────────────┤
│                        ChatScreen                               │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ ChatHeader     │ [≡] Android Agent [+] (menu & new chat) │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ TaskBanner    │ "Working on: ..." with status dot        │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ MessageList   │ User/Agent bubbles, Action cards         │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ InputDock     │ Text input + Send/Stop button            │   │
│  └─────────────────────────────────────────────────────────┘   │
├────────────────────────────────────────────────────────────────┤
│                   NavigationDrawer                              │
│  (Side drawer with session history + settings entry)            │
├────────────────────────────────────────────────────────────────┤
│                     SettingsSheet                               │
│  (Modal bottom sheet for model/config)                          │
├────────────────────────────────────────────────────────────────┤
│                    Overlay System                               │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ EdgeGlowManager     │ Ambient glow around screen edges   │   │
│  ├─────────────────────┼───────────────────────────────────┤   │
│  │ ActionVisualizer    │ Ripple/trail for touch actions     │   │
│  ├─────────────────────┼───────────────────────────────────┤   │
│  │ SmartCapsuleManager │ Floating capsule during execution  │   │
│  └─────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────┘
```

### Screen Components

| Component | Purpose |
|-----------|---------|
| **ChatHeader** | Header with menu (≡) left, title center, new chat (+) right |
| **TaskBanner** | Shows current task context with animated status dot |
| **MessageBubble** | User/Agent message bubbles with proper styling |
| **StreamingText** | Text with blinking cursor during streaming |
| **ThinkingIndicator** | Animated dots while agent is processing |
| **ActionCard** | Tool execution cards with status states |
| **InputDock** | Input field with Send/Stop toggle |
| **EmptyState** | First-launch experience with suggestions |

### Settings Sheet

Modal bottom sheet for configuration. Features a header with title and close button (no drag handle).

**Settings Items:**
- Model selection (GPT-4o, GPT-4o-mini, GPT-4-turbo)
- Max turns (10, 20, 50)
- Accessibility service status
- Overlay permission status
- About & Debug

**Layout Features:**
- Custom header with "Settings" title and close (X) button
- Display cutout padding for notched devices
- Status bar padding for Dynamic Island compatibility

---

## Session History UI

The session history UI enables users to browse, resume, and manage past chat sessions via a **navigation drawer**.

### Navigation Drawer

Side drawer containing session history and settings access. Opens via the menu button (≡) in the header.

**Features:**
- Header with "Sessions" title and close button
- "New Conversation" outlined button (light, unobtrusive style)
- Scrollable list of past sessions (sorted by last updated)
- Delete action on each session
- Settings entry at bottom with model/version info
- Proper system bar inset handling via `AppWindowInsets`

**Visual Layout:**
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
│  ┌───────────────────────────┐  │
│  │ "Open Settings app"       │  │
│  │  3 messages • Yesterday   │🗑│
│  └───────────────────────────┘  │
│  ...                            │
├─────────────────────────────────┤
│  ⚙ Settings                     │
│    gpt-4o • v1.0                │
└─────────────────────────────────┘
```

### DrawerSessionItem

Individual session card in the drawer list.

**Displays:**
- Display title (summary or first user message, truncated)
- Message count and relative timestamp
- Delete button (trailing icon)

### Relative Time Formatting

| Time Difference | Output |
|----------------|--------|
| < 1 minute | "Just now" |
| 1-59 minutes | "5 min ago" |
| 1-23 hours | "2 hours ago" |
| 1 day | "Yesterday" |
| 2-6 days | "3 days ago" |
| 7+ days (same year) | "Jan 15" |
| Different year | "Jan 15, 2025" |

The drawer opens automatically when the user taps the menu (≡) button in the header. Sessions are loaded when the drawer opens via the `onLoadSessions` callback.

---

## Smart Capsule

The Smart Capsule is a floating overlay that follows users across all apps during agent execution.

### Features

- **Streaming text**: Shows live agent response
- **Status dot**: Color-coded with pulsing animation
- **Control buttons**: Pause, Stop, Open App
- **Morphing states**: Visual feedback through color and animation

### States

| State | Visual | Behavior |
|-------|--------|----------|
| **Thinking** | Pulsing glow, "Thinking..." | Agent processing |
| **Acting** | Status text | Shows current tool |
| **Streaming** | Live text | Agent response streaming |
| **Success** | Green flash | Task complete |
| **Error** | Red tint, shake | Something went wrong |
| **Paused** | Amber tint | User paused execution |

---

## Edge Glow

The Edge Glow provides ambient visual feedback showing the agent is actively controlling the device. It displays a glowing border around the screen edges that changes color based on the agent's state.

### Features

- **Full-screen edge glow** with gradient fade from edges
- **State-based colors** matching agent execution phases
- **Pulse animation** when active or executing
- **Touch pass-through** (doesn't block interaction)
- **Display cutout handling** for notched devices
- **Auto-hide** after success state (2 seconds)

### Glow States

| State | Color | Behavior |
|-------|-------|----------|
| **Active** | Primary Blue | Pulsing animation |
| **Executing** | Light Blue | Pulsing animation |
| **Success** | Teal | Static, auto-hides after 2s |
| **Error** | Red | Static |
| **Paused** | Amber | Static |

### Visibility Control

The edge glow is only visible when the main app is **not** in the foreground. This prevents visual clutter when the user is viewing the chat interface.

---

## Action Visualizer

The Action Visualizer provides visual feedback when the agent performs touch actions (clicks, swipes, scrolls). It helps users understand where and how the agent interacts with the screen.

### Features

- **Ripple effect** for tap/click actions
- **Trail animation** for swipe/scroll actions
- **Non-intrusive** - passes all touch events through
- **Automatic cleanup** after animation completes
- **Color-coded** actions (different colors for different action types)

### Visualization Types

#### Click Ripple

Expanding circle animation for tap/click visualization.

| Action | Animation |
|--------|-----------|
| Click | Expanding blue circle |
| Long press | Expanding purple circle |

#### Swipe Trail

Line drawing animation for swipe/scroll visualization.

| Action | Animation |
|--------|-----------|
| Swipe | Light blue line with dots |
| Scroll | Indigo line with dots |

---

## Event → UI Mapping

| AgentEvent | UI Update |
|------------|-----------|
| `TaskStarted` | Add user message, show Task Banner, disable input |
| `TurnPhaseChanged` | Update Task Banner subtitle |
| `MessageDelta` | Append to agent bubble, show streaming cursor |
| `ActionExecuted` | Add action card to agent bubble |
| `TaskCompleted` | Mark bubble complete, enable input, show "Done" |
| `SessionError` | Show error in Task Banner, enable input |

### Status Flow (User Perspective)

```
User sends message
        │
        ├──► Task Banner shows "Working on: ..."
        │
        ├──► Edge Glow activates (when app not in foreground)
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

- [UI Tech Design](tech_design.md) - Technical implementation details
- [UI Style Guide](style.md) - Design system and visual specifications
