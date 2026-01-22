# Multi-Round Chat UI Design (Gemini)

> **Status**: Proposed
> **Author**: Gemini (AI Agent Architecture & Design)
> **Date**: 2026-01-21
> **Target**: "Well-funded Startup" Aesthetic, Jetpack Compose

## 1. Design Philosophy: "Invisible Intelligence"

The goal is to move away from a "developer tool" (logs, config fields) to a "consumer product" (conversational assistant). The interface should feel breathable, fluid, and trustworthy.

**Core Principles:**
*   **Content First**: The conversation *is* the UI. Everything else (settings, logs) recedes.
*   **Fluidity**: Streaming text, smooth state transitions, and organic motion.
*   **Trust**: Clear visibility of *what* the agent is doing (using tools) without cluttering the chat with raw logs.
*   **Focus**: The main screen asks one thing: "What can I do for you?"
*   **Ubiquity**: The agent follows you via the **Smart Overlay** when you leave the app.

## 2. Information Architecture

### 2.1 Main Screen (The "Chat" View)
The primary interface is a full-screen messaging experience.

*   **Top Bar (Minimal)**:
    *   Left: "Android Agent" (Subtle branding)
    *   Right: `IconButton` (Gear) -> Settings Sheet
*   **Center**: `LazyColumn` (Message List)
    *   User Bubbles (Right, Accent Color)
    *   Agent Bubbles (Left, Surface Color)
    *   *New*: **Action Cards** (Inline widgets for tool execution)
    *   *New*: **Thinking Indicator** (Subtle animation while processing)
*   **Bottom**: Input Area
    *   `TextField` (Rounded, "Ask me anything...")
    *   `SendButton` (Animated icon)

### 2.2 The Smart Overlay (The "Minimode")
When the agent operates on other apps, the main UI disappears, and the Overlay takes over. This is not just a control bar; it is the **active agent representation**.

*   **Form Factor**: "Dynamic Capsule" (pill-shaped floating window).
*   **Position**: Bottom center (hovering above navigation bar).
*   **States**:
    *   **Idle/Observing**: Small, translucent pill.
    *   **Thinking**: Pulsing glow or animated waveform.
    *   **Acting**: Expands slightly to show current action (e.g., "Opening Settings...").
    *   **Error**: Shakes or turns red.
*   **Interactions**:
    *   **Tap**: Open full Chat App (context switch).
    *   **Long Press**: Quick "Stop" or "Pause".
    *   **Swipe Down**: Dismiss/Hide (if allowed).

### 2.3 Settings (The "Engine Room" - Hidden)
Moved to a Modal Bottom Sheet or separate screen. Config should be "set and forget".

*   **API Key**: Secure input, masked by default.
*   **Service Status**:
    *   "Accessibility Service": Toggle/Status (Green/Red indicator).
    *   "Overlay Permission": Toggle/Status.
*   **About/Debug**: Version info.

## 3. Visual Design System

**Theme**: "Modern Minimalist" (Evolution of current Notion-inspired theme).

*   **Typography**:
    *   Headings: System Font (San Francisco/Roboto), Medium Weight.
    *   Body: High legibility, 16sp.
    *   Code/Logs: JetBrains Mono (for specific tool outputs if expanded).
*   **Colors**:
    *   **Background**: Pure White (`#FFFFFF`) or very light gray (`#F9F9F9`).
    *   **User Bubble**: Deep Blue/Indigo (`#2D55FF`) or Solid Black (`#000000`) for high contrast. Text: White.
    *   **Agent Bubble**: Light Gray (`#F2F2F2`) or Surface Variant. Text: Dark Gray (`#1A1A1A`).
    *   **Action Card**: White surface with subtle drop shadow (`elevation = 2.dp`), rounded borders.
    *   **Overlay**: High-blur backdrop (glassmorphism) or high-contrast solid (Deep Black) with White text.
*   **Shapes**:
    *   Bubbles: Super-ellipse or highly rounded (`RoundedCornerShape(20.dp)`).
    *   Input Field: Pill shape (`RoundedCornerShape(50.dp)`).
    *   Overlay: Fully rounded capsule.

## 4. Component Details

### 4.1 The Chat Bubble
Standardized container for conversation items.

```kotlin
@Composable
fun ChatBubble(
    isUser: Boolean,
    content: @Composable () -> Unit
) {
    // Alignment & Color logic
    // Smooth width animation for streaming text
}
```

### 4.2 Streaming Text
Instead of raw text, use a `TypewriterText` component that reveals characters smoothly, matching the `MessageDelta` events.

### 4.3 Action Cards (The "Wow" Factor)
When the agent performs an action (e.g., "Open Settings"), don't just show text. Show a card.

**State: Proposed**
> **Thinking...**
> I'm checking your calendar.

**State: Executing**
> **Opening Calendar** [Spinner]
> *Action: `open_app("com.google.android.calendar")`*

**State: Done**
> **✓ Calendar Opened**
> Found one event at 3 PM.

### 4.4 The Input Bar
Fixed at the bottom.
*   **Idle**: Clean text field.
*   **Running**: Field disabled (or specialized "Stop" button replaces Send).
*   **Icon**: Transitions from "Arrow Up" (Send) to "Square" (Stop) with animation.

### 4.5 The Smart Overlay Implementation
This must remain a `View`-based implementation (not Composable) for `WindowManager` compatibility, or use `ComposeView` inside the added View.

*   **Structure**:
    ```kotlin
    // Using Compose inside the Overlay View for better animation support
    class OverlayComposeView(context: Context) : AbstractComposeView(context) {
        @Composable
        override fun Content() {
            OverlayTheme {
                Capsule {
                    StatusIndicator() // Dot/Icon
                    StatusText()      // Marquee or Truncated
                    ControlButtons()  // Pause/Stop (Icon only)
                }
            }
        }
    }
    ```
*   **Animations**: The View width/height should animate when state changes (e.g., expanding to show a long status message).

## 5. Interaction Flow (Happy Path)

1.  **Launch**: User sees empty state or previous history. "How can I help?" prompt.
2.  **Input**: User types "Turn on Do Not Disturb".
3.  **Think**:
    *   User message slides up.
    *   "Agent is thinking..." bubble appears (pulsing).
4.  **Act**:
    *   Thinking bubble transforms into an Action Card: "Opening Settings...".
    *   **Crucial Step**: If the agent needs to leave the app, the Main UI minimizes.
    *   **Overlay Appears**: The "Smart Capsule" slides up from the bottom.
    *   Capsule shows: "⚙️ Opening Settings..."
5.  **Response**:
    *   Agent returns to app (or overlay shows notification).
    *   Chat appends: "I've turned on Do Not Disturb."
    *   Overlay fades out or returns to "Idle" state.

## 6. Implementation Strategy (Compose)

### 6.1 `AgentScreen.kt` Refactor
*   **Deprecate**: `StatusLog`, `ConfigSection`.
*   **Introduce**: `ChatList`, `MessageBubble`, `InputBar`.
*   **State Management**:
    *   `AgentUiState` needs to hold a `List<ChatMessage>` instead of just `statusLines`.
    *   `ChatMessage` needs types: `User`, `Agent`, `Action`.

### 6.2 Data Models
```kotlin
sealed interface ChatMessage {
    val id: String
    val timestamp: Long

    data class User(
        override val id: String,
        override val timestamp: Long,
        val text: String
    ) : ChatMessage

    data class Agent(
        override val id: String,
        override val timestamp: Long,
        val text: String, // Mutable/State for streaming
        val isStreaming: Boolean
    ) : ChatMessage
    
    data class Action(
        override val id: String,
        override val timestamp: Long,
        val toolName: String,
        val status: ActionStatus // Proposed, Running, Done, Failed
    ) : ChatMessage
}
```

### 6.3 Settings Sheet
Use `ModalBottomSheet` for the settings.
*   Triggered by TopBar Icon.
*   Contains the API Key and Permissions check from the old `ConfigSection`.

### 6.4 Overlay Evolution
Migrate `OverlayManager` to use `ComposeView`.
*   Allows sharing the Theme and Design System.
*   Enables complex animations (pulsing, size changes) which are hard with standard Views.

## 7. Mockup (ASCII)

```
+-----------------------------+
|  Android Agent           [⚙] |  <- Header with Settings Icon
+-----------------------------+
|                             |
|         [  Hi there!  ]     |  <- Agent Bubble
|                             |
| [ Turn on Wi-Fi      ]      |  <- User Bubble
|                             |
| +-------------------------+ |
| |  Settings               | |
| |  Turning on Wi-Fi...    | |  <- Action Card (replacing log)
| |  [====      ]           | |
| +-------------------------+ |
|                             |
|         [  Done!      ]     |
|                             |
|                             |
|                             |
+-----------------------------+
| ( Ask anything...      ) [↑]|  <- Input Pill + Send Fab
+-----------------------------+

[  ⚙️ Opening Settings...  ]    <- Overlay (When outside app)
```
