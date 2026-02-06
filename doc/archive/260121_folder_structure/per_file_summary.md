# Per-File Code Summary

This document provides a summary of each Kotlin source file in `app/src/main/kotlin/`.

---

## Entry Points & Service Layer

### `app/AgentService.kt`
**AccessibilityService Entry Point**

Main entry point for the Android Accessibility Service. Responsibilities:
- Manage `AgentSession` lifecycle
- Receive operation commands via `Op` sealed class (Start/Pause/Resume/Shutdown)
- Send state updates to UI layer via `AgentEvent` Flow
- Manage floating control bar `OverlayManager`
- Provide static `statusFlow` for MainActivity to collect state

### `app/MainActivity.kt`
**Compose UI Main Screen**

Main Activity of the application, built with Jetpack Compose UI. Responsibilities:
- Display API Key input, Goal input, status log
- Collect `AgentService.statusFlow` via `lifecycleScope` to update UI
- Handle Intent extras (support passing API key and goal from command line)
- Check permissions (Overlay, Accessibility Service)
- Call `AgentService.runAgent()` to start the agent

---

## Agent Core

### `agent/Agent.kt`
**ReAct Agent Main Loop**

Single ReAct agent executing the Perceive → Think → Act → Observe loop. Responsibilities:
- Manage turn count and pause/stop state
- Each turn: capture screen → call LLM → execute tool → record observation
- Emit various events via `eventEmitter` (TurnStarted, ActionExecuted, etc.)
- Handle error recovery (distinguish recoverable/non-recoverable errors)
- Support `complete_task` tool to mark task completion

### `agent/Turn.kt`
**Single LLM Turn**

Encapsulates a complete LLM call flow:
- Build `ResponseInputItem` list from `HistoryManager`
- Call `LLMClient.chatWithTools()` to get response
- Parse tool calls and text content
- Detect task completion (`complete_task` was called)

### `agent/AgentConfig.kt`
**Agent Configuration**

Configuration data class for agent execution, containing:
- `goal`: User goal
- `sessionId`: Session ID
- `maxTurns`: Maximum number of turns
- `uiSettleDelayMs`: Wait time after actions
- `debugMode`: Debug mode toggle

---

## Session Management

### `session/AgentSession.kt`
**Session Lifecycle Management**

Main controller for agent execution, implementing the Op/Event protocol:
- Receive `Op` operations (Start, Pause, Resume, Shutdown, Approve, etc.)
- Emit `AgentEvent` via `events` Flow
- Manage `SessionState` state machine (Created → Running → Paused → Completed/Shutdown)
- Create and run `Agent` instance
- Forward approval requests

### `session/SessionServices.kt`
**Dependency Injection Container**

Session-level service container (similar to a DI container):
- Create and hold all session services: `ToolRegistry`, `ToolRouter`, `HistoryManager`, `PolicyEngine`, `LLMClient`
- Register built-in tools (click, type, scroll, swipe, back, home, wait, complete_task)
- Provide `cleanup()` method for resource cleanup

---

## Protocol Layer

### `protocol/Op.kt`
**Operation Command Definitions**

Operation commands sent from UI layer to Agent:
- `Start(goal)`: Start agent
- `Pause` / `Resume`: Pause/Resume
- `Interrupt`: Interrupt current turn
- `Shutdown`: Close session
- `UserInput(text)`: User input (reserved)
- `Approve(actionId, decision)`: Approval response

Also includes `SessionConfig` configuration class and `ApprovalMode` enum.

### `protocol/AgentEvent.kt`
**Event Definitions**

Events sent from Agent to UI:
- Session lifecycle: `SessionStarted`, `SessionCompleted`, `SessionError`, `SessionPaused`, `SessionResumed`
- Turn events: `TurnStarted`, `TurnCompleted`, `TurnPhaseChanged`
- Action events: `ActionProposed`, `ActionExecuted`, `ActionSkipped`
- Others: `ScreenCaptured`, `ApprovalRequired`, `ApprovalResolved`, `StatusUpdate`

### `protocol/SessionState.kt`
**Session State Machine**

Session lifecycle states:
- `Created`: Created but not started
- `Running`: Running
- `Paused`: Paused
- `Completed`: Completed
- `Shutdown`: Shut down

### `protocol/SessionId.kt`
**Session ID**

Type-safe session identifier implemented using `@JvmInline value class`.

### `protocol/AgentError.kt`
**Error Type Definitions**

Categorized error types with `isRecoverable` property:
- LLM errors: `LLMError`, `LLMParseError`
- Platform errors: `PlatformError`, `PermissionError`
- Validation errors: `ValidationError`, `UnknownToolError`
- State errors: `InvalidStateError`, `SessionClosedError`
- Approval errors: `ApprovalDeniedError`, `PolicyDeniedError`

### `protocol/ApprovalTypes.kt`
**Approval Related Types**

- `ApprovalDecision`: APPROVED / DENIED / ABORT
- `RiskLevel`: LOW / MEDIUM / HIGH
- `ApprovalRequirement`: None / Required / Forbidden
- `ApprovalDetails`: Detailed information for approval requests

---

## Infrastructure

### `history/HistoryManager.kt`
**Conversation History Management**

Manages LLM conversation history:
- Store `ResponseItem` (Message, FunctionCall, FunctionCallOutput)
- Support truncation strategies (NONE, CONSERVATIVE, AGGRESSIVE, MINIMAL)
- Token estimation and context window management
- History compression and normalization (ensure call/output pairing)
- `dropLastNUserTurns()` for rollback support

### `tool/ToolRegistry.kt`
**Tool Registry**

Manages tool registration and lookup:
- Register/unregister/find `ToolSpec`
- Generate tool definitions in OpenAI Responses API format
- JSON conversion helper methods

### `tool/ToolRouter.kt`
**Tool Execution Router**

State machine executor for tool calls:
- State flow: VALIDATING → POLICY CHECK → (AWAITING_APPROVAL) → EXECUTING → SUCCESS/ERROR
- Integrate `PolicyEngine` for approval decisions
- Support approval timeout (60 seconds)
- Track active tool call states

### `tool/ToolSpec.kt`
**Tool Specification Interface**

Defines tool interface and related types:
- `ToolSpec`: Tool specification (name, description, parameterSchema, validate, createInvocation)
- `ValidationResult`: Validation result
- `ToolInvocation`: Executable tool invocation
- `ToolExecutionContext`: Execution context
- `ToolExecutionResult`: Execution result (Success/Failure/Cancelled)
- `ToolObservation`: Post-execution observation (ScreenState/TextOutput)

### `tool/ToolCallResult.kt`
**Tool Call Final Result**

Result after complete ToolRouter lifecycle:
- `Success`: Success, contains output and optional observation
- `Error`: Failure, contains error message
- `Cancelled`: Cancelled

### `tool/ToolCallState.kt`
**Tool Call State Machine**

Tracks various tool call states:
- `Validating`: Validating
- `Scheduled`: Scheduled
- `AwaitingApproval`: Awaiting approval
- `Executing`: Executing
- `Success` / `Error` / `Cancelled`: Terminal states

### `tool/PolicyEngine.kt`
**Policy Engine**

Decides whether tool calls require approval:
- Support three modes: `ALWAYS_ASK`, `AUTO_APPROVE`, `SMART`
- Risk level-based decisions (LOW/MEDIUM/HIGH)
- Support allow/deny lists
- Configurable risk level per tool

---

## Platform Layer

### `platform/AndroidPlatform.kt`
**Platform Abstraction Interface**

Abstraction for Android platform operations:
- `captureScreen()`: Capture screen
- `performAction()`: Execute UI action
- `hasRequiredPermissions()`: Check permissions
- `getCurrentPackageName()`: Get foreground app package name
- `getDisplayInfo()`: Get display information

### `platform/AccessibilityPlatform.kt`
**AccessibilityService Implementation**

Real implementation of `AndroidPlatform`:
- Use `Perceptor` to capture screen
- Implement various UI actions: click, type, scroll, swipe, system buttons
- Gestures implemented via `GestureDescription` API
- Text input by re-querying accessibility tree to find target node

### `platform/UIAction.kt`
**UI Action Definitions**

Platform-agnostic UI actions:
- `Click(elementIndex)`: Click element
- `ClickAt(x, y)`: Click coordinates
- `Type(elementIndex, text)`: Type text
- `Scroll(direction)`: Scroll
- `Swipe(startX, startY, endX, endY, durationMs)`: Swipe
- `SystemButton(button)`: System button
- `Wait(durationMs)`: Wait

Also defines `ScrollDirection` and `SystemButtonType` enums.

### `platform/ActionResult.kt`
**Action Execution Result**

UI action execution results:
- `Success`: Success
- `Failure`: Failure
- `ElementNotFound`: Element not found
- `Cancelled`: Cancelled

---

## Data Layer

### `perception/Perceptor.kt`
**Screen Perception Engine**

Converts AccessibilityNodeInfo tree to semantic `ScreenSnapshot`:
- Traverse accessibility tree, extract meaningful elements
- Limit maximum elements (80) and string length (60)
- Properly recycle AccessibilityNodeInfo nodes
- Generate JSON format for LLM use

### `llm/LLMClient.kt`
**LLM Client**

OpenAI Responses API wrapper:
- Support tool/function calling
- Automatic retry (exponential backoff, up to 5 times)
- Distinguish retryable errors (rate limit, 5xx) from non-retryable errors
- Instance-based design (not singleton), supports different API keys

---

## Domain Models

### `model/Models.kt`
**Core Data Models**

- `Bounds`: Rectangle bounds
- `Point`: 2D coordinate point
- `ScreenSnapshot`: Screen snapshot (timestamp + element list)
- `PerceptionElement`: UI element (index, text, resourceId, className, interaction properties, position, etc.)

---

## Tools (Tool Implementations)

### `tool/BaseTool.kt`
**Tool Base Class**

Abstract base class for UI tools:
- Provide parameter validation helper methods
- Provide JSON Schema building helper methods
- Implement `BaseToolInvocation`: execute UIAction and capture post-execution screen observation

### `tool/impl/ClickTool.kt`
**Click Tool**

Click UI element at specified index. Parameters: `element_index` (required)

### `tool/impl/TypeTool.kt`
**Type Tool**

Type text into specified element. Parameters: `element_index` (required), `text` (required)

### `tool/impl/ScrollTool.kt`
**Scroll Tool**

Scroll screen in specified direction. Parameters: `direction` (required, up/down/left/right)

### `tool/impl/SwipeTool.kt`
**Swipe Tool**

Swipe from one point to another. Parameters: `start_x`, `start_y`, `end_x`, `end_y` (required), `duration_ms` (optional)

### `tool/impl/NavigationTools.kt`
**Navigation Tools**

Contains `BackTool` (press system back button) and `HomeTool` (press system Home button). No parameters.

### `tool/impl/WaitTool.kt`
**Wait Tool**

Wait for specified time. Parameters: `duration_ms` (optional, default 1000, max 30000)

### `tool/impl/CompleteTaskTool.kt`
**Complete Task Tool**

Mark task as completed. Parameters: `summary` (required, completion summary)

---

## UI Layer

### `ui/screen/AgentScreen.kt`
**Main Screen Compose UI**

Compose implementation of the main app screen:
- Header: Title and subtitle
- ConfigSection: API Key input (with show/hide), Goal input
- ActionButtons: Start Agent button, Accessibility Settings button
- StatusLog: Status log display area
- Use `StatusUtils` for unified status type and color handling

### `ui/theme/Theme.kt`
**Theme Definition**

Compose Material3 theme:
- Uses Notion-style light color palette
- Configure system bar colors
- Combine colorScheme and typography

### `ui/theme/Color.kt`
**Color Definitions**

Notion-style elegant light theme colors:
- Background/surface colors: Warm white tones
- Primary: Blue-gray
- Accent: Coral/terracotta
- Secondary: Soft teal
- Status colors: Success/Warning/Error/Info

### `ui/theme/Type.kt`
**Typography Definitions**

Material3 Typography configuration: Display, Headline, Title, Body, Label font styles at various levels.

---

## Utilities

### `util/StatusUtils.kt`
**Status Processing Utilities**

Centralized status message processing:
- `cleanStatusText()`: Remove emoji
- `getStatusType()`: Detect status type (SUCCESS/ERROR/WARNING/THINKING/TOOL/RUNNING/NEUTRAL)
- `isTerminalStatus()`: Determine if it's a terminal state

---

## Service Layer

### `ui/overlay/OverlayManager.kt`
**Floating Control Bar**

Floating UI control bar during agent execution:
- Displayed at screen bottom
- Contains status indicator dot, status text, pause/resume button, stop button
- Uses WindowManager as overlay display
- Update indicator dot color based on status type
