# UI Integration Design - Chat Experience (Codex)
 
> Date: 2026-01-21  
> Scope: UI integration for multi-round chat and streaming (Phase 5)  
> Inputs: `final_design.md`, `current_impl_status.md`
 
## 1. Product Vision
Build a conversation-first agent UI that feels calm, premium, and highly capable. The user should feel the agent is always present, attentive, and moving work forward without noise. The interface should resemble a modern startup product: minimal chrome, strong typography, refined motion, and a confident visual system.
 
## 2. Design Principles
- Conversation is the product. The chat timeline is the primary surface.
- Streaming is a first-class interaction. The UI should feel alive but never jittery.
- Progress must be legible. Every action by the agent is visible and understandable.
- Low friction by default. Input is always reachable; interruptions are simple.
- Settings are hidden from the main screen. No gear icon on the primary chat view.
 
## 3. Information Architecture
Primary screens and surfaces:
- Agent Chat (default, always open)
- Task History (secondary, list of past tasks)
- Control Center (Settings, debug, app info) accessed only from Task History
- Floating Overlay (悬浮窗): system-level control bar during execution
 
Access pattern:
- Main screen has no visible Settings entry.
- Settings is reachable from Task History via the overflow menu or a dedicated "Control Center" list item.
 
## 4. Main Screen Layout (Agent Chat)
High-level layout:
 
```
Top App Bar (brand + status, history icon)
Task Banner (current task state)
Chat Timeline (messages + action cards + approvals)
Input Dock (text field + send + stop)
```
 
### 4.1 Top App Bar
- Left: brand wordmark and subtle session indicator (online, idle).
- Right: Task History icon (clock) and optional overflow for "New Task" only.
- No Settings icon or entry here.
 
### 4.2 Task Banner (Context Strip)
Purpose: keep the user oriented without taking space.
- Default idle: "Ready" with a muted status dot.
- On TaskStarted: show "Working on: <user input>" with a progress pulse.
- On TurnPhaseChanged: show short phase labels (Perceiving, Planning, Executing).
- On TaskCompleted: collapse into a one-line completion status for 3 seconds.
 
### 4.3 Chat Timeline
The timeline contains:
- User message bubbles
- Assistant streaming bubbles
- Tool action cards
- Approval cards
- Optional system status rows (thin text rows)
 
Behavior:
- Auto-scroll only if the user is already at the bottom.
- When new content arrives while scrolled up, show a "Jump to latest" chip.
- Each Task is visually grouped with a subtle divider and timestamp.
 
### 4.4 Input Dock
- Anchored at bottom, floating card with subtle elevation.
- Multi-line input with dynamic height up to 5 lines.
- Send button (enabled when text present and session idle).
- Stop button appears when a task is running (maps to `Op.Interrupt`).
- Microcopy placeholder: "Tell me what you want to do..."
 
## 5. Core Components
 
### 5.1 Message Bubbles
User bubble:
- Right aligned, primary accent background, white text.
- Consistent corner radius, tail-less for modern look.
 
Assistant bubble:
- Left aligned, neutral background, dark text.
- Streaming state shows a subtle caret and shimmering underline.
 
### 5.2 Tool Action Card
Shows tool use in the conversation, tied to `ActionProposed` and `ActionExecuted`.
Fields:
- Tool name and brief description.
- Status chip: Proposed, Executing, Success, Failed, Skipped.
- Expandable area for result text (collapsed by default).
 
### 5.3 Approval Card
Triggered by `ApprovalRequired`.
- Clear summary of what will happen.
- Primary actions: Approve, Deny.
- Optional "View details" expansion showing `details`.
 
### 5.4 Status Row
A minimal single-line update for `StatusUpdate` and `AgentThinking`.
- Uses smaller typography, muted color.
- Only one active at a time to prevent noise.
 
## 6. Streaming UX Rules
- Append deltas to the active assistant bubble on `MessageDelta`.
- Throttle recompose to 30-60ms when deltas are tiny.
- If no delta arrives for 500ms while task is running, show "typing" pulse.
- When `TurnCompleted` arrives, finalize the assistant bubble state.
 
## 7. Event to UI Mapping
 
Use these events as the primary UI driver:
- `TaskStarted` -> create task group, show Task Banner, disable send.
- `MessageDelta` -> append to the active assistant bubble.
- `TurnPhaseChanged` -> update Task Banner phase label.
- `ActionProposed` -> insert tool card (Proposed).
- `ApprovalRequired` -> insert approval card; pause execution UI.
- `ActionExecuted` -> update card with result and status.
- `ActionSkipped` -> update card with reason.
- `TaskCompleted` -> mark task as complete, re-enable send.
- `SessionError` -> show error toast and offer retry.
 
## 8. Overlay Integration (Floating Control Bar / 悬浮窗)
The overlay is a system-level, view-based control bar that appears while the agent runs in other apps. It must stay minimal and consistent with the chat UI, without duplicating full conversation details.

### 8.1 Behavior
- Visible only when a task is running or paused; hidden when idle.
- Status text is short and stable (avoid streaming deltas to prevent jitter).
- Status dot color matches the same semantic mapping as the main UI.
- Tap on the status area opens the main chat screen (deep link to `MainActivity`).
- When the chat app is foreground, the overlay should hide to avoid covering input.

### 8.2 Content Layout
- Left: status dot + concise status text.
- Right: pause/resume + stop buttons (40dp hit targets).
- Optional: a subtle "Open" chevron (future) if needed for discoverability.
- No settings access here.

### 8.3 Event Mapping (Overlay)
- `TaskStarted` -> "Working on: <input>" (truncate).
- `TurnPhaseChanged` -> "Perceiving / Planning / Executing".
- `ActionProposed` -> "Action: <tool>".
- `ApprovalRequired` -> "Needs approval".
- `ActionExecuted` -> "Done / Failed".
- `TaskCompleted` -> "Done" then auto-hide after a short delay.

### 8.4 Visual Alignment
- Card surface: white background, subtle border, 14dp radius (matches current overlay).
- Typography: 14sp medium for status.
- Status colors: success / warning / error align with the theme palette.

## 9. Visual System
 
### 8.1 Color
- Background: near-white or near-black depending on theme.
- Primary accent: single bold color used for user bubbles and CTAs.
- Success, warning, error: limited palette for action cards.
 
### 8.2 Typography
- Display: large, confident title in the top bar.
- Body: 16sp for chat text, 14sp for status rows.
- Monospace for tool results if they are structured or code-like.
 
### 8.3 Shape and Spacing
- 8dp base grid.
- Bubble radius: 16dp, cards: 20dp.
- Input dock padding: 16dp, with 12dp internal spacing.
 
## 10. Motion and Micro-Interaction
- Message arrival: subtle fade-in + 4dp slide.
- Streaming caret: soft blink at 700ms interval.
- Action cards: expand and collapse with 200ms easing.
- Task Banner: gentle pulse while running, resolves on completion.
 
## 11. Accessibility
- Minimum contrast ratio 4.5:1 for all text.
- Respect system font scaling; bubbles expand with content.
- Provide TalkBack labels for action and approval cards.
 
## 12. Settings Placement (Hidden)
- Main screen has no settings icon or menu.
- Task History screen includes a "Control Center" entry at the bottom.
- Control Center contains Settings, Debug, and About.
- Optional: long-press on the brand mark opens Control Center (power users).
 
## 13. UI Data Model (Compose)
 
Suggested UI model for Compose state:
```
data class UiTask(
  val taskId: String,
  val title: String,
  val state: TaskState,
  val items: List<UiTimelineItem>
)
 
sealed interface UiTimelineItem {
  data class UserMessage(...)
  data class AssistantMessage(...)
  data class ToolCard(...)
  data class ApprovalCard(...)
  data class StatusRow(...)
}
```
 
## 14. Task History Screen
- List of past tasks with title, outcome, and timestamp.
- Tap to open a read-only view of the task timeline.
- Bottom entry to open Control Center (Settings).
 
## 15. Implementation Notes (Phase 5)
- Update `AgentScreen` to own a task timeline state and bind to `AgentEvent` flow.
- Add streaming bubble support tied to `MessageDelta` and `TurnCompleted`.
- Integrate action and approval cards from Action and Approval events.
- Feed `OverlayManager` with concise, non-streaming status derived from task/phase events.
- Hide overlay when chat is foreground; show it when the agent runs in other apps.
- Keep settings off the main screen; place in Task History > Control Center.
 
