# Mobile Portability Analysis

## Goal

Decide what from OpenClaw should be adopted into Android Agent, what should be reinterpreted natively for Android, and what should be rejected as desktop-only.

The governing rule is simple:

- do not port OpenClaw as a runtime
- absorb its platform-neutral design patterns
- keep Android Agent's existing Kotlin-first session/tool/platform architecture as the control plane

## Current Baseline In This Repo

Android Agent already contains most of the portable core:

- session/task/turn lifecycle, Hot Idle, checkpoint reload
- planning state via `TodoState` and `ScratchpadState`
- context hygiene and compression via `HistoryManager`
- tool registry, routing, and approval policy
- planner/executor multi-agent mode
- Android-native execution via accessibility and virtual display platforms
- app-scoped skill injection via `app_skills/<package>/SKILL.md`

That means this project is not about feature-parity porting. It is about choosing the right ownership boundaries for Android.

## Portability Verdict

### Port Directly (Already Built)

These are already present or map cleanly into the current design:

| OpenClaw Concept | Android Agent Implementation |
|---|---|
| Session-oriented runtime | `AgentSession`, `SessionCoordinator`, `SessionServices` |
| Working memory / planning | `TodoState` (session-scoped), `ScratchpadState` (session-scoped) |
| Prompt compaction / history hygiene | `HistoryManager` — 3-phase compression pipeline (proactive screen downgrade, group-aware eviction, hard guard) with `COMPRESSION_DIGEST` breadcrumbs |
| Tool approval / risk classification | `PolicyEngine` (ALWAYS_ASK / AUTO_APPROVE / SMART) + `ToolRouter` lifecycle |
| Multi-agent orchestration | Planner/Executor mode via `AgentDefRegistry` + `SubAgentRunner` |
| Prompt ownership boundaries | System prompt + tool descriptions + `app_skills/<package>/SKILL.md` |
| Session persistence + resume | `SessionRecordingService`, `SessionStorage`, checkpoint reload, Hot Idle |

**Note on compaction:** Android Agent's compression is already more sophisticated than OpenClaw's. It features proactive per-turn screen downgrade (keeps ~275 tokens/turn growth), KV cache-friendly deep compression (to 50% of budget, not 95%), and call/output atomic pair eviction. No compaction patterns need to be absorbed from OpenClaw.

### Reinterpret Natively

These are valuable ideas, but the desktop implementation should be replaced:

- long-term memory
- external channels / relay ingress
- plugin / extension model
- capability / security model
- voice, notifications, sensors, and other device-native tools

### Explicit Non-Goals

Do not port:

| Desktop Capability | Why Not | Android Alternative |
|---|---|---|
| Embedded Node.js runtime | Not available natively | Kotlin-first architecture |
| Docker sandbox | No container runtime on Android | Android per-app sandbox + `PolicyEngine` |
| Puppeteer / Playwright | No headless browser on Android | AccessibilityService controls browser apps |
| WhatsApp Web automation | Requires headless Chrome (300-500MB) | AccessibilityService operates WhatsApp App directly |
| Inbound gateway server | NAT blocks inbound; background process killed; battery | Outbound relay client model |
| npm-compatible plugin runtime | No npm on Android | Kotlin modules + asset-based `app_skills/` |
| Unrestricted shell / code execution | No root; minimal CLI | Scoped `ShellTool` (file inspection only) |

## Core Architectural Decision

Keep one control plane:

```text
Intent Sources
- Chat UI
- Optional relay client
- Optional native triggers (voice, notifications, sensors)
          |
          v
Session Control Plane
- SessionCoordinator
- AgentSession
- SessionServices
          |
          v
Agent Core
- Agent / Turn runners
- Prompt assembly
- HistoryManager
- TodoState / ScratchpadState
- Planner / Executor delegation
          |
          v
Capability Layer
- UI tools
- planning tools
- future native tools
          |
          v
Execution Substrate
- AccessibilityPlatform
- VirtualDisplayPlatform
- Android storage / Android APIs
```

All incoming work, regardless of source, must enter the system through the existing session-op model. Channel transport must not become a parallel runtime owner.

## Resolved Design Rules

### 1. Session Continuity Stays With Existing Owners

The repo already has:

- follow-up continuity through Hot Idle
- process-death recovery through checkpoints
- persisted session history through `SessionRecordingService` and `SessionStorage`

So:

- do not redesign `SessionId`
- do not move persistence into `HistoryManager`
- do not introduce a second session storage model for this project

If session continuity needs improvement later, extend the existing recording/checkpoint stack rather than replacing it.

### 2. Long-Term Memory Is A New Subsystem, Separate From Session History

Three different layers must stay separate:

- `HistoryManager`: runtime prompt history and compression
- `ScratchpadState` / `TodoState`: session-scoped working memory
- new durable memory store: cross-session retrieval and writes

Recommended shape:

- storage: SQLite-backed store, optionally with human-readable exported Markdown views
- retrieval: bounded FTS query
- writes: explicit memory write / reflect flow, not hidden implicit persistence

Prompt ownership rule:

- retrieved long-term memory must be injected as its own prompt block
- do not merge it into the app-skill block
- app skills remain authored package guidance; memory remains retrieved mutable context

This preserves debuggability and keeps ownership clear.

### 3. Capability Metadata Replaces Desktop Sandbox Thinking

On Android, the right boundary is not Docker. It is capability ownership.

Each tool-capability should declare:

- required permissions
- foreground-only vs background-safe
- risk level
- data scope

But this must not remain a documentation-only concept. It needs a concrete integration point, for example metadata attached to `ToolSpec` or an adjacent capability descriptor consumed by policy and availability filtering.

### 4. Tool Availability Is Snapshotted Per Task Or Session, Not Hot-Swapped Mid-Turn

Dynamic capability discovery is useful, but hot-mutation of the registry during execution is the wrong model for the current architecture.

Rule:

- determine active tool availability at task start or session start
- expose only the resulting snapshot to the LLM for that run
- if permissions or runtime capabilities change materially, refresh on the next task or restart the agent/session

This avoids nondeterministic tool exposure while still preventing obviously unavailable tools from being advertised.

### 5. The Current Shell Boundary Stands

The existing shell tool is intentionally file-inspection-only.

So:

- do not grow it toward desktop-style package-manager / intent control
- if package inspection, app management, or other device actions are needed, add dedicated native tools instead

This keeps security and tool semantics readable.

### 6. External Channels Are Intent Sources, Not Runtime Owners

If remote channels are added later:

- the phone remains the execution host
- the relay is outbound-oriented or push-wake oriented
- remote input is translated into the same session ops used by local UI

A minimal transport state machine is enough:

```text
DISABLED -> CONNECTING -> READY -> DELIVERING -> BACKOFF -> READY
```

This state belongs to the relay client, not to `AgentSession`.

### 7. Prompt Externalization Must Respect Prompt Lifecycle

Agent identity / rules externalization is a valid direction, but it must respect the current prompt lifecycle:

- system prompt is resolved once at agent start
- per-turn context belongs in `PromptBuilder` / planning-phase assembly

So:

- identity/rules/tool-guidance assets belong in startup-time prompt construction
- retrieved memory and app/package guidance belong in per-turn context injection

Do not blur those two lifecycles.

## Priority Matrix

| Area | Status In Repo | Priority | Related Project | Notes |
|---|---|---|---|---|
| Portable-core framing | Largely present | Done | — | Main conclusion of this analysis |
| Long-term memory subsystem | Missing | P0 | Project 1 | Biggest real OpenClaw pattern gap |
| Voice-first interaction | Partial | P0 | Project 2 | Android native STT/TTS APIs available |
| Capability metadata + context-aware policy | Partial | P1 | Project 5 | Extend current approval/risk model |
| Tool availability snapshotting | Partial | P1 | — | Filter unavailable tools at task/session start |
| Agent identity externalization | Partial | P2 | Project 4 | Useful, but lower leverage than memory |
| Device capability advertising | Missing | P2 | Project 6 | Advertise available tools/sensors to LLM |
| Relay-based remote ingress | Missing | P3 | Project 3 | Future-facing; not core for initial product |
| Dynamic runtime plugin system | Reject | N/A | — | Keep Kotlin modules + asset files instead |
| Session persistence redesign | Reject | N/A | — | Existing ownership is sufficient |

## Trade-Offs

### Why This Wins

- reuses the strongest existing Android Agent subsystems
- keeps runtime boundaries simple
- avoids importing desktop constraints that do not belong on Android
- keeps prompt ownership, persistence ownership, and capability ownership separate

### What We Give Up

- direct portability of desktop integrations
- server-style long-lived channel architecture on the handset alone
- npm-style extension distribution
- desktop-like shell and browser automation parity

That trade-off is correct. Android Agent's advantage is native phone control, not desktop emulation.

## Open Questions

These are real follow-up questions, but they do not block the portability conclusion:

1. Should long-term memory be SQLite-only, or SQLite plus exported Markdown mirrors for inspection?
2. How much retrieved memory can be injected per turn without materially hurting token budget and prompt stability?
3. What should offline / disconnected behavior be when the cloud LLM is unreachable on mobile?
4. If runtime capabilities change while a session is idle, should the next task reuse the old session with a refreshed tool snapshot, or force a new session?

## Final Recommendation

OpenClaw's architecture reflects a desktop-centric worldview: the computer is the brain, the phone is a sensor. Android Agent inverts this — the phone is the brain _and_ the body, with direct access to apps, sensors, notifications, and the user's physical context.

The things OpenClaw does better are all platform-agnostic algorithms: memory retrieval, identity templates, security classification. These port trivially as pure logic. The things Android Agent does better are all platform-native capabilities that OpenClaw simulates poorly: app interaction via accessibility, sensor access, always-with-the-user presence.

The right design is not "OpenClaw on Android." The right design is:

- keep Android Agent's current session/tool/platform architecture
- adopt OpenClaw's portable design patterns where they still fit
- add missing Android-native subsystems like long-term memory and capability metadata
- reject the desktop runtime pieces as deliberate non-goals

That yields a cleaner system than a port, and it is aligned with the codebase that already exists.
