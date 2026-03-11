# Design: Product Strategy vs OpenClaw

## 1. Problem

OpenClaw is gaining traction as a general personal agent with a desktop/cloud gateway model. Android Agent operates in the same space but with a fundamentally different capability: accessibility-service-level screen automation. We need a product strategy that rides OpenClaw's wave without becoming a disposable execution backend.

## 2. Strategic Position

**Verdict: Option 2.5 — Independent product with a thin, task-level integration seam.**

### Why not Option 1 (OpenClaw Node)

Becoming an OpenClaw Node means implementing their full Gateway protocol (WebSocket + custom RPC + dual-session + TLS fingerprint pinning + device auth). Worse, it reduces our entire agent — the ReAct loop, perception pipeline, planning state, multi-agent delegation — to a single `screen.action` tool call controlled by their Gateway. We become a replaceable remote finger.

Concrete risks grounded in our architecture:
- **Agent brain erasure.** Our `AgentTurnRunner` → `TurnPlanningPhaseRunner` → `TurnExecutionPhaseRunner` pipeline, the todo/scratchpad cross-turn memory, loop detection, app skills — all bypassed. OpenClaw's Gateway would send atomic commands, not tasks.
- **Session model conflict.** Our `AgentSession` lifecycle (Created → Running → Hot Idle → Shutdown) with `SessionServices` DI, checkpoint persistence, and `HistoryManager` compression is designed for autonomous multi-turn execution. A Node model reduces this to stateless request-response.
- **Platform investment wasted.** `AccessibilityPlatform` + `VirtualDisplayPlatform`, the `Perceptor` pipeline with two-pass tree traversal and text enrichment, `TargetResolver` with fallback chains — this is months of work that becomes invisible plumbing behind someone else's API.

### Why not Option 2 (Full independence)

No integration path means zero network effects from OpenClaw's user base. We'd need to build user acquisition from scratch while they already have distribution through Telegram, Discord, and browser channels.

### Why Option 2.5 works

Our moat is **a11y-tree-based screen understanding + autonomous execution**. OpenClaw's Android app is a sensor + chat client (camera, GPS, SMS, notification reading). Their A2UI is Canvas-based (agent pushes rendered HTML). They don't do "read a11y tree → understand UI → plan → execute → verify." This gap is structural, not a feature backlog item.

Option 2.5 preserves this moat while exposing it as a capability others can consume.

## 3. Architecture Design

### 3.1 Integration Layer: Task API

A lightweight HTTP server embedded in the Android Agent app, exposing task-level (not action-level) operations.

```
POST /v1/tasks
{
  "instruction": "Open WeChat, send '3pm tomorrow' to Zhang San",
  "timeout_seconds": 120,
  "callback_url": "https://..."        // optional, for async notification
}
→ 202 Accepted
{
  "task_id": "t_abc123",
  "status": "running"
}

GET /v1/tasks/{task_id}
→ 200
{
  "task_id": "t_abc123",
  "status": "completed",              // running | completed | failed | cancelled
  "result": "Message sent",
  "steps_taken": 7,
  "duration_ms": 14200
}

DELETE /v1/tasks/{task_id}
→ 200  (cancels a running task)
```

Optional WebSocket upgrade for streaming progress:
```
WS /v1/tasks/{task_id}/stream
← {"type": "step", "turn": 3, "action": "click", "target": "Send button"}
← {"type": "thought", "content": "Navigating to chat..."}
← {"type": "complete", "result": "Message sent", "success": true}
```

### 3.2 How It Maps to Existing Architecture

The Task API is a thin adapter over existing components. No core refactoring needed.

```
HTTP Request
  ↓
TaskApiServer (new, ~200 LOC)
  ↓
Op.UserInput(instruction)          ← existing protocol Op
  ↓
SessionCoordinator.submit(op)      ← existing session entry point
  ↓
AgentSession runs full ReAct loop  ← existing agent pipeline
  ↓
AgentEvent.TaskCompleted           ← existing event
  ↓
TaskApiServer maps to HTTP response
```

**Key mapping:**

| Task API Concept | Existing Component | Notes |
|---|---|---|
| Task submission | `Op.UserInput` | 1:1 mapping |
| Task status | `SessionState` + `AgentEvent` | Observe via SharedFlow |
| Task cancellation | `Op.Interrupt` | Already supported |
| Step progress | `AgentEvent.ActionExecuted` | Already emitted per turn |
| Task result | `AgentEvent.TaskCompleted` | Contains success + message |
| Timeout | `SessionConfig.maxTurns` | Map seconds → estimated turns |

**What stays untouched:**
- `AgentTurnRunner`, `TurnPlanningPhaseRunner`, `TurnExecutionPhaseRunner` — full planning pipeline
- `Perceptor`, `ScreenSnapshot`, text enrichment — full perception
- `ToolRegistry`, `ToolRouter`, `PolicyEngine` — full tool system
- `TodoState`, `ScratchpadState` — cross-turn memory
- `HistoryManager` — context compression
- App skills — per-app guidance

### 3.3 New Components

**1. `TaskApiServer`** (~200 LOC)
- Embedded HTTP server (Ktor or NanoHTTPd — NanoHTTPd preferred for minimal footprint, zero extra dependencies)
- Binds to `localhost:8741` by default (configurable)
- Auth: shared secret token in header (`Authorization: Bearer <token>`)
- Maps HTTP requests to `Op` submissions via `SessionCoordinator`
- Maintains in-memory `Map<String, TaskRecord>` for status queries
- Observes `AgentEvent` SharedFlow to update task state

```kotlin
data class TaskRecord(
    val id: String,
    val instruction: String,
    val status: TaskStatus,          // Running, Completed, Failed, Cancelled
    val result: String? = null,
    val stepsCount: Int = 0,
    val createdAt: Long,
    val completedAt: Long? = null
)
```

**2. `TaskApiConfig`** (added to `SessionConfig`)
```kotlin
data class TaskApiConfig(
    val enabled: Boolean = false,
    val port: Int = 8741,
    val authToken: String? = null,   // null = no auth (local-only use)
    val maxConcurrentTasks: Int = 1  // start with serial execution
)
```

**3. UI toggle** — Settings screen checkbox to enable/disable Task API, show port + auth token.

### 3.4 Security Model

The Task API runs on-device. Threat model is local network access.

| Threat | Mitigation |
|---|---|
| Unauthorized task submission | Bearer token auth (generated on first enable, shown in Settings) |
| Remote network access | Bind to `localhost` only by default; opt-in for LAN binding |
| Task injection / prompt injection | Instruction is plain text passed as `Op.UserInput` — same trust boundary as manual input |
| Resource exhaustion | `maxConcurrentTasks=1`, per-task timeout, `maxTurns` cap |
| Sensitive screen data leakage | Task result is agent's completion message only; no raw screen data in API response |

For LAN binding (needed for OpenClaw desktop → phone), require explicit user opt-in + show warning about network exposure.

### 3.5 OpenClaw Integration Example

An OpenClaw user adds this tool definition to their Gateway:

```json
{
  "name": "android_agent",
  "description": "Execute tasks on Android phone via screen automation. Send natural language instructions — the agent plans and executes autonomously.",
  "parameters": {
    "instruction": {
      "type": "string",
      "description": "What to do on the phone, e.g. 'Open WhatsApp and send hello to Mom'"
    }
  },
  "endpoint": {
    "method": "POST",
    "url": "http://<phone-ip>:8741/v1/tasks",
    "headers": { "Authorization": "Bearer <token>" },
    "body_template": { "instruction": "{{instruction}}", "timeout_seconds": 120 }
  },
  "poll": {
    "url": "http://<phone-ip>:8741/v1/tasks/{{task_id}}",
    "interval_seconds": 3,
    "complete_when": "status in ['completed', 'failed']"
  }
}
```

5-minute setup. No SDK, no protocol implementation, no Node registration.

### 3.6 Bidirectional Integration (Future)

Our agent can also call external systems. This maps cleanly to a new tool:

```kotlin
// Future: registered in ToolRegistry
ToolSpec(
    name = "remote_agent",
    description = "Delegate a task to a remote agent (desktop, cloud, another phone)",
    parameters = mapOf("agent" to "string", "instruction" to "string")
)
```

Implementation calls the same Task API contract on a remote endpoint. Two agents become peers, not master-slave.

## 4. Phased Priorities

### Phase 1: Standalone product (current focus)
- Core automation quality (autotune loop, app skills)
- Session stability (Hot Idle, checkpoint recovery)
- Voice input/output
- **No external API work.** Ship a product users love on its own.

### Phase 2: Task API (~3-5 days)
- `TaskApiServer` with NanoHTTPd
- Bearer token auth
- `POST /v1/tasks`, `GET /v1/tasks/{id}`, `DELETE /v1/tasks/{id}`
- Settings UI toggle
- Integration test: `curl` → task → completion

### Phase 3: OpenClaw bridge (~1-2 days)
- OpenClaw tool definition template (JSON file in repo)
- LAN binding opt-in with security warning
- Documentation: "Connect to OpenClaw in 5 minutes"

### Phase 4: Bidirectional (future)
- `remote_agent` tool for outbound delegation
- Agent-to-agent protocol spec (our Task API as the shared contract)

## 5. What We Explicitly Do NOT Build

- OpenClaw Gateway protocol (WebSocket + RPC + device auth)
- OpenClaw Node registration / heartbeat / capability negotiation
- OpenClaw session sync or shared memory
- Any dependency on OpenClaw's SDK, schema, or release cycle

## 6. Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Integration granularity | Task-level, not action-level | Preserves our agent brain as the moat |
| HTTP server | NanoHTTPd embedded | Zero new dependencies, ~50KB, battle-tested on Android |
| Auth model | Bearer token, localhost-default | Simple, secure enough for local use, upgradeable |
| Concurrency | Serial (1 task at a time) | Matches single-screen reality; queue later if needed |
| Protocol | Plain HTTP + optional WS | Any system can integrate; not OpenClaw-specific |
| Result exposure | Completion message only | No raw a11y data leakage through API |

## 7. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| OpenClaw builds native a11y automation | Our integration value drops | Our standalone product must be compelling on its own — the API is upside, not the core bet |
| Task API becomes the primary interface | Product identity shifts to "backend" | Phase 1 priority: standalone UX first. API is Phase 2 |
| Security incident via LAN API | User trust damage | Localhost-only default, explicit opt-in, prominent warnings |
| OpenClaw protocol changes break integrations | User frustration | We don't implement their protocol — we expose ours. Their tool definitions are user-maintained |

## 8. Success Criteria

- **Phase 1 gate:** Standalone app handles 80%+ of autotune eval tasks successfully
- **Phase 2 gate:** `curl POST /v1/tasks` → task runs → `GET` returns completed result
- **Phase 3 gate:** OpenClaw user can invoke Android Agent via tool definition, zero code changes on our side
- **North star:** Users choose Android Agent because it's the best phone agent, not because it plugs into OpenClaw

## Self-Review

### Strengths
1. **Zero core architecture changes.** Task API is an adapter layer over existing `Op`/`AgentEvent` protocol. The agent pipeline, perception, tools, session — all untouched.
2. **Moat preservation.** Task-level API keeps planning intelligence on our side. No action-level remote control.
3. **Protocol independence.** Plain HTTP means any orchestrator can integrate, not just OpenClaw. Future-proof against OpenClaw's relevance fluctuations.
4. **Phased execution.** Standalone product first, API second, OpenClaw bridge third. Each phase is independently valuable.

### Weaknesses / Open Questions
1. **Single-task serial execution.** If multiple OpenClaw tool calls arrive concurrently, they queue. This is correct for single-screen, but the UX for the caller (waiting in queue) needs thought — should we return 429 or 202-with-queue-position?
2. **Callback reliability.** `callback_url` for async notification assumes the caller is reachable. Polling is the safe default, but adds latency. WebSocket streaming is better but adds complexity.
3. **No discovery protocol.** OpenClaw users must manually configure phone IP + token. mDNS/Bonjour auto-discovery would improve UX but adds scope.
4. **Approval mode tension.** If `PolicyEngine` is set to `ALWAYS_ASK`, API-submitted tasks will block waiting for on-device user approval. Need a policy override for API-submitted tasks (e.g., `AUTO_APPROVE` for trusted tokens) or an approval forwarding mechanism.
5. **NanoHTTPd maturity.** It's simple but limited — no built-in WebSocket, no HTTP/2. If WebSocket streaming becomes important, may need to swap to Ktor (which is already in the Kotlin ecosystem but adds ~2MB).
