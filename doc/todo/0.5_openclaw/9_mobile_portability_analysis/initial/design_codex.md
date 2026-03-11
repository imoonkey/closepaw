# Mobile Portability Analysis

## Goal

Decide what from OpenClaw should be:

1. Ported directly into Android Agent
2. Reinterpreted as Android-native capabilities
3. Explicitly rejected as desktop-only

Success means we preserve the valuable agent architecture from OpenClaw without importing its desktop runtime assumptions. The result should stay Kotlin-first, Android-native, and compatible with this repo's existing `AgentSession` -> `Agent` -> `ToolRouter` -> `AndroidPlatform` stack.

## Current Baseline In This Repo

Android Agent already has most of the portable "agent core":

- Session/task/turn lifecycle, hot idle, checkpoint reload: `session/AgentSession.kt`, `session/SessionCheckpointCoordinator.kt`, `doc/main/infra/session.md`
- Planning state and working memory: `session/TodoState.kt`, `session/ScratchpadState.kt`, `doc/main/agent/planning.md`
- Context hygiene and compression: `history/HistoryManager.kt`, `doc/main/app/history.md`
- Tool registry, policy, approvals, and post-action observation: `session/SessionToolingBootstrapper.kt`, `tool/ToolRouter.kt`, `tool/PolicyEngine.kt`, `doc/main/infra/tools.md`
- Planner/executor multi-agent mode: `session/SessionAgentRunner.kt`, `tool/impl/DelegateTaskTool.kt`, `doc/main/agent/multiagent.md`
- Android-native actuation: `platform/AccessibilityPlatform.kt`, `platform/virtualdisplay/VirtualDisplayPlatform.kt`, `doc/main/infra/platform.md`

The important implication: this project is not about "making Android able to run OpenClaw." It is about choosing which OpenClaw design ideas belong inside the existing Android Agent architecture.

## Design Decision

Do not port OpenClaw as a runtime.

Port only its platform-neutral patterns, and re-express them in three Android-native layers:

1. **Agent core**: session, planning, memory, compaction, multi-agent orchestration
2. **Capability layer**: Kotlin tools backed by Android APIs and app automation
3. **Optional connectivity layer**: outbound-only relay/client integration for remote channels

This keeps one control plane. We do not add a parallel Node.js, Docker, browser, or npm-plugin runtime.

## Portability Model

### A. Port Directly

These concepts are already present or fit the current architecture with minimal change:

| OpenClaw idea | Android Agent mapping | Decision |
|---|---|---|
| Session-oriented agent runtime | `AgentSession`, `SessionCoordinator`, `SessionServices` | Keep |
| Working memory / task planning | `TodoState`, `ScratchpadState` | Keep |
| Prompt context hygiene / compaction | `HistoryManager`, prompt assembly pipeline | Keep |
| Tool risk / approval model | `PolicyEngine`, tool lifecycle | Keep |
| Multi-agent orchestration | `PRO` planner/executor mode | Keep |
| Prompt/app-skill ownership boundaries | system prompt + tool descriptions + `app_skills/.../SKILL.md` | Keep |

These are the core portable assets. They are already aligned with the repo's architecture and should remain the center of the design.

### B. Port With Android-Native Reinterpretation

These ideas are worth keeping, but their desktop implementation must be replaced:

| OpenClaw area | Desktop assumption | Android-native design |
|---|---|---|
| Memory system | File-heavy desktop storage | Add a dedicated SQLite-backed memory store with FTS; keep session history separate from long-term memory |
| Plugin/extensions | npm workspace packages | Replace with Kotlin capability modules plus asset-driven prompt/app-skill files |
| External bot channels | Long-lived inbound gateway/server | Use an outbound relay client or push-wake model; remote inputs become `Op.UserInput` / `Op.Supplement` |
| Browser/web automation | Playwright/Puppeteer | Use app automation on installed Android apps or browsers via accessibility/VD |
| Sandbox/security | Docker isolation | Use Android permissions + capability-scoped tools + approval policy |
| Voice | Desktop integrations | Use Android STT/TTS services as native tools if needed |
| Notification/sensor access | Desktop has limited device context | Model as native Android tool providers if needed |

### C. Do Not Port

These should be explicit non-goals:

- Embedded Node.js runtime
- Docker sandbox
- Puppeteer/Playwright execution
- WhatsApp Web automation
- Inbound WebSocket gateway running on the phone
- General unrestricted shell/code-execution environment
- npm-compatible extension runtime

The current repo already points in this direction: `ShellTool` is intentionally narrow and file-inspection-only, not a desktop automation shell.

## Target Architecture

```text
Intent Sources
- User chat
- Optional relay client
- Optional notification/voice triggers
          |
          v
Session Control Plane
- SessionCoordinator
- AgentSession
- SessionServices
          |
          v
Agent Core
- Agent / AgentTurnRunner
- PromptBuilder
- TodoState / ScratchpadState
- HistoryManager
- Planner/Executor delegation
          |
          v
Capability Layer
- Existing UI tools: mobile_action, open_app, system_button, wait
- Existing planning tools: write_todos, scratchpad, complete_task
- Future native tools: memory_search, memory_write, notifications, voice, sensors, channel adapters
          |
          v
Execution Substrate
- AccessibilityPlatform
- VirtualDisplayPlatform
- Android APIs / app automation / local storage
```

### Key Rule

All inputs, regardless of origin, must enter the system as session ops or tool invocations. The core agent should not know whether a task came from chat UI, a relay message, or a notification trigger.

This turns channel-specific edge cases into one canonical path.

## Component Design

### 1. Keep The Current Session-Centric Control Plane

`AgentSession` remains the owner of runtime lifecycle, idle behavior, checkpointing, and event emission.

That means:

- No second "gateway session" abstraction
- No always-on server process inside the app
- No channel-specific task loop

If remote ingress is added later, it should translate incoming events into the same ops the UI already uses.

### 2. Introduce Capability-Based Portability Boundaries

OpenClaw uses runtime boundaries like Docker and desktop processes. On Android, the right boundary is capability ownership.

Each capability should declare:

- backing Android permission(s)
- whether it is foreground-only or background-safe
- risk level for `PolicyEngine`
- data scope it can read/write

Examples:

- `mobile_action`: high-risk, foreground-only, UI mutation
- `memory_search`: low-risk, local structured read
- `notification_read`: medium/high-risk, background-readable
- `voice_transcribe`: medium-risk, foreground capture

This is simpler than container sandboxing and matches Android's real security model.

### 3. Separate Session History From Long-Term Memory

This repo already has strong session history and checkpointing, but not a dedicated cross-session semantic memory layer.

Design addition:

- Keep `HistoryManager` focused on prompt history and compression
- Keep `ScratchpadState` focused on task-scoped working memory
- Add a future `MemoryStore` for durable facts, retrieval, and search

Recommended storage:

- SQLite as the source of truth
- FTS for lexical retrieval
- explicit memory write/read tools instead of implicit hidden persistence

This preserves current context hygiene while making OpenClaw-style memory portable in a mobile-native form.

### 4. Replace Plugin Runtime With Two Simple Extension Surfaces

Do not recreate npm plugins.

Use only:

1. **Kotlin capability modules**
   - New `ToolSpec` implementations, repositories, or platform adapters
2. **Asset/config extensions**
   - system prompts
   - tool descriptions
   - `app_skills/<package>/SKILL.md`

This matches how the repo already separates prompt ownership from tool ownership. It also keeps extension loading predictable and testable.

### 5. Treat Remote Channels As Intent Sources, Not As First-Class Runtime Owners

If Telegram/Slack/Discord-style connectivity is ever added, the relay should not own agent execution.

Instead:

- relay receives remote message
- phone is notified via outbound websocket, polling, or push wake
- relay payload is converted into `Op.UserInput` or `Op.Supplement`
- the normal session lifecycle handles the task

This preserves one runtime model and avoids inventing a parallel "bot session" architecture.

## Interaction State Machine

The only new state machine worth adding is for optional remote ingress:

```text
DISABLED
  -> CONNECTING   (user enabled relay integration)
  -> READY        (outbound channel established or push token registered)
  -> DELIVERING   (incoming remote task mapped to session op)
  -> BACKOFF      (network/app standby failure)
  -> READY        (retry succeeds)
  -> DISABLED     (user turns integration off)
```

Important constraints:

- `READY` must tolerate Android background limits
- no assumption of permanent socket availability
- reconnect/backoff belongs to the relay client, not `AgentSession`

This keeps session execution and channel transport decoupled.

## Recommended Scope For This Project

This analysis project should produce a clear portability contract:

### In Scope

- Define what OpenClaw concepts map cleanly to Android Agent
- Define which desktop subsystems are replaced, not ported
- Define the future extension points for memory, capabilities, and relay ingress

### Out Of Scope

- Rebuilding OpenClaw's Node runtime
- Achieving feature parity with desktop channels
- Solving background delivery guarantees beyond a relay-client contract
- Designing a full browser automation stack

## Trade-Offs

### Why This Wins

- Reuses the strongest parts of the current repo instead of competing with them
- Aligns with Android's real execution model and background restrictions
- Keeps security understandable through capability boundaries
- Avoids a large, fragile compatibility layer for Node/browser/docker features

### What We Give Up

- Direct portability of desktop integrations
- npm-style extension distribution
- Rich desktop shell/code execution workflows
- Server-like always-on connectivity from the handset alone

That is acceptable because Android Agent's strategic advantage is native phone control, not desktop parity.

## Phased Follow-Up

1. **Codify the portability boundary**
   Document portable vs non-portable subsystems in main architecture docs.
2. **Add durable memory as a first-class subsystem**
   SQLite + FTS + explicit memory tools.
3. **Expand native capability packs**
   Notifications, voice, sensors, and other Android-only tools.
4. **Add optional relay connectivity**
   Outbound-only, transport-decoupled, mapped into existing session ops.

## Final Recommendation

The right design is not "OpenClaw on Android."

The right design is:

- keep Android Agent's current session/tool/platform architecture
- absorb OpenClaw's portable ideas into that architecture
- replace desktop-specific mechanisms with Android-native capability modules
- reject the rest as deliberate non-goals

That gives us a portable core without importing the wrong runtime model.
