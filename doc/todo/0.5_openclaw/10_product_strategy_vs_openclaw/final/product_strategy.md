# Product Strategy: Android Agent vs OpenClaw

## 1. Strategic Decision

**Option 2.5: Android Agent is an independent Android-native personal agent. OpenClaw is an optional upstream consumer through a thin, vendor-neutral Task API.**

### What this means

- We do NOT become an OpenClaw Node (no Gateway protocol, no action-level RPC, no shared session/memory).
- We DO expose a task-level HTTP interface where any external agent can submit a phone task and poll for results.
- Planning, perception, execution, verification, session state, and memory all remain inside Android Agent.

### Why

**Against Option 1 (OpenClaw Node):**
The Node model reduces our entire agent — ReAct loop, perception pipeline, planning state (todos/scratchpad), multi-agent delegation, app skills — to a single remote `screen.action` call controlled by their Gateway. We become a replaceable execution backend. Their Android team is also iterating; once they add a11y automation, we have no position.

**Against Option 2 (Full independence):**
Zero integration path means zero network effects from OpenClaw's user base. We'd need to build distribution from scratch.

**For Option 2.5:**
Our moat is a11y-tree-based screen understanding + autonomous execution. OpenClaw's Android app is a sensor + chat client (camera, GPS, SMS, notifications). Their A2UI is Canvas-based (agent pushes rendered HTML to phone). They don't do "read a11y tree → understand UI → plan → execute → verify." This gap is structural, not a feature backlog item. Option 2.5 preserves this moat while making it available to their ecosystem.

## 2. Product Positioning

### Primary identity

Android Agent is an **Android-native personal agent**:
- Owns the phone-side user experience
- Owns the agent loop (perceive → think → act → observe)
- Owns Android-specific perception and action (a11y tree, screen automation)
- Owns local session continuity, memory, and safety decisions

### Integration identity

Android Agent is also an **agent-capability provider**:
- Any external orchestrator can submit a task via the Task API
- OpenClaw is the first obvious consumer, but the contract is generic
- The API is vendor-neutral — no OpenClaw-specific protocol dependency

## 3. Task API Design

### Contract

The API is **task-level and asynchronous**. External callers send natural language instructions. Android Agent decides how to execute them. Callers may NOT specify tools, coordinates, or action sequences.

```
POST /v1/tasks
{
  "instruction": "Open WeChat and send Zhang San: tomorrow 3pm works",
  "timeout_seconds": 120
}
→ 202 Accepted
{
  "task_id": "t_abc123",
  "status": "accepted"
}

GET /v1/tasks/{task_id}
→ 200
{
  "task_id": "t_abc123",
  "status": "completed",
  "result": "Message sent to Zhang San",
  "steps_taken": 7,
  "duration_ms": 14200
}

POST /v1/tasks/{task_id}/cancel
→ 200
{
  "task_id": "t_abc123",
  "status": "cancelled"
}
```

`POST cancel` (not `DELETE`) because the task record should persist with terminal status for the caller to query.

### Task State Machine

```
accepted → running → completed
                   → failed
                   → cancelled
                   → timed_out
                   → waiting_for_local_user → running
                                            → timed_out
```

**`waiting_for_local_user`** is a key design choice. It collapses multiple internal interruption types into one canonical external state:
- `PolicyEngine` requires tool approval
- `ask_user` tool needs device-side input
- Device permission or intervention required

This avoids inventing separate external flows for each internal interruption type. The external caller sees one state and one behavior: wait, or give up after timeout.

### Concurrency Model

**One task at a time.** Android's accessibility service is a singleton — concurrent screen automation is physically impossible. When a task is already running:
- New `POST /v1/tasks` returns `409 Conflict` with a `Retry-After` header
- No queuing in v1 (queuing adds complexity with unclear benefit — the caller can retry)

### Timeout Contract

External `timeout_seconds` maps to a **wall-clock deadline watchdog**, NOT to `SessionConfig.maxTurns`. The watchdog:
- Runs as a coroutine timer alongside the agent session
- On expiry, submits `Op.Interrupt` to the session
- Waits briefly for clean shutdown (current action completes)
- Transitions task to `timed_out`

This is necessary because turn duration varies widely (2-30s) and `maxTurns` is a turn budget, not a time limit.

### Session Mapping

One external task = one internal task run. The Task API gateway:
1. If there is no current session, creates one via `SessionCoordinator`
2. If there is an idle current session, reuses it for the new task
3. If a session is already running or paused, rejects the new task with `409 Conflict`
4. Submits the instruction as `Op.UserInput` (via `SessionCoordinator.submit(text)` — the existing string-based interface)
5. Observes `AgentEvent` emissions to update external task state
6. On completion/failure/timeout, records the terminal state

External tasks do NOT get durable conversational memory ownership. The agent may use its internal session machinery (Hot Idle, scratchpad, todos) but external callers see only task-level results.

### What the API Does NOT Expose

- Raw accessibility tree data
- Internal tool calls or action sequences (except coarse step count)
- Agent reasoning / thought content
- Session history or scratchpad contents
- Any way to specify tools, coordinates, or action plans

## 4. Security Model

### Authentication

**Bearer token auth is required, even for localhost.** On Android, localhost is not a safe trust boundary — other apps on the device can reach loopback ports.

- Token generated on first API enable (cryptographically random, 32 bytes, base64)
- Displayed in Settings UI for the user to copy
- Rotation: user can regenerate from Settings
- All requests without valid `Authorization: Bearer <token>` get `401 Unauthorized`

### Network Binding

- **Default: localhost only** (`127.0.0.1:8741`)
- **LAN binding: explicit opt-in** with prominent warning about network exposure
- No internet-facing binding (use a tunnel if needed)

### Safety

External tasks obey the same local safety boundary as first-party tasks:
- No bypass of `PolicyEngine` approval policy
- No hidden execution channel around `ToolRouter`
- No external override of user approval decisions
- If a task cannot continue without local user input, it pauses in `waiting_for_local_user` and times out if unresolved

## 5. Architecture

### Boundary

The Task API is a new layer **above** `SessionCoordinator`, not inside `tool/` or `agent/`. The core agent stack is untouched.

```
External orchestrator (OpenClaw, other)
        |
        | HTTP request
        v
TaskApiGateway (new)
        |
        | submit(text) / interrupt / observe events
        v
SessionCoordinator → AgentSession → SessionServices → ToolRouter → mobile_action / tools
```

### New Components

**1. `TaskApiGateway`**
- Embedded HTTP server (library choice deferred to implementation — NanoHTTPd or Ktor both viable)
- Binds to configured address/port
- Authenticates requests via bearer token
- Maps HTTP requests to `SessionCoordinator` calls
- Maintains in-memory `Map<String, TaskRecord>` for status queries
- Observes `AgentEvent` SharedFlow to update task records
- Manages per-task wall-clock deadline watchdog
- Lives at the app/service layer, not inside `AgentSession`; it starts with the long-lived service process and is independent of any single task run

**2. `TaskApiConfig`** (app-level settings, NOT inside `SessionConfig`)
```kotlin
data class TaskApiConfig(
    val enabled: Boolean = false,
    val port: Int = 8741,
    val authToken: String,
    val bindLan: Boolean = false      // false = localhost only
)
```

This is app/service-level configuration, separate from the immutable per-session `SessionConfig`.

**3. `TaskRecord`** (in-memory, explicitly ephemeral in v1)
```kotlin
data class TaskRecord(
    val id: String,
    val instruction: String,
    val status: TaskStatus,
    val result: String? = null,
    val stepsCount: Int = 0,
    val createdAt: Long,
    val completedAt: Long? = null
)
```

Process death destroys task records. This is acceptable for v1 — tasks are short-lived (seconds to minutes). The caller should handle lost connections gracefully.

**4. Settings UI** — Toggle to enable/disable Task API, display port + auth token, LAN binding opt-in with warning.

### Adapter Reality

The gateway is not a trivial 1:1 transport wrapper. It must:
- Marshal onto the main thread for `SessionCoordinator` access (main-thread confined)
- Arbitrate between API-submitted and UI-submitted inputs (API input is rejected while a UI task is running, and vice versa)
- Keep server lifecycle separate from session lifecycle (server survives task completion; individual sessions do not)
- Map `AgentEvent` flow to external `TaskStatus` (the "Status Projector" logic — ~30 lines of `when(event)` mapping, not a separate class)
- Manage the deadline watchdog coroutine

This is a real orchestration layer, estimated at 300-500 LOC, not a thin pass-through.

## 6. OpenClaw Integration

An OpenClaw user adds this tool definition to their Gateway:

```json
{
  "name": "android_agent",
  "description": "Execute tasks on Android phone via screen automation. Send natural language instructions.",
  "parameters": {
    "instruction": { "type": "string" }
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
    "complete_when": "status in ['completed', 'failed', 'timed_out', 'cancelled']"
  }
}
```

5-minute setup. No SDK, no protocol implementation, no Node registration.

## 7. Rollout Phases

### Phase 0: Standalone product (current priority)
- Core automation quality (autotune, app skills)
- Session stability (Hot Idle, checkpoint recovery)
- On-device UX
- **No external API work.**

### Phase 1: Task API
- `TaskApiGateway` with HTTP server
- Bearer token auth, localhost-only default
- `POST /v1/tasks`, `GET /v1/tasks/{id}`, `POST /v1/tasks/{id}/cancel`
- Wall-clock deadline watchdog
- Settings UI toggle
- Integration test: curl → task → completion

### Phase 2: OpenClaw bridge
- OpenClaw tool definition template (JSON in repo)
- LAN binding opt-in with security warning
- Setup documentation

### Phase 3: Bidirectional (future)
- `remote_agent` tool for outbound delegation to desktop/cloud agents
- Agent-to-agent protocol using the same Task API contract as shared interface
- Two agents as peers, not master-slave

### Explicitly deferred
- Full OpenClaw Gateway protocol
- Action-level RPC
- Shared remote memory/session model
- Streaming progress channel (polling is sufficient for task-level granularity)
- Webhook callbacks (add when real usage demands it)
- Persistent task records (add if process-death recovery becomes important)
- Auto-discovery (mDNS/Bonjour — nice-to-have, not v1)

## 8. Non-Goals

- Implement any part of the OpenClaw Gateway protocol
- Let external systems drive `mobile_action` or other internal tools directly
- Share session history, scratchpad, or raw accessibility tree with external callers
- Optimize for deep OpenClaw coupling before standalone product value is proven
- Stream agent reasoning/thoughts to external callers

## 9. Trade-Offs

| Choice | What we gain | What we give up |
|---|---|---|
| Task-level API (not action-level) | Moat preservation — our brain stays local | Less composability for advanced orchestrators |
| Localhost-only default | Security | Requires LAN opt-in for cross-device use |
| No OpenClaw Node | Product independence | Fastest possible OpenClaw-native integration |
| Serial execution (one task) | Simplicity, matches hardware reality | External callers must retry when busy |
| `waiting_for_local_user` state | Honest contract, no silent hangs | Some remote flows aren't fully autonomous |
| In-memory task records | Simplicity | Process death loses external task state |

All acceptable because they preserve the only thing worth defending: Android Agent as a product, not a subcontractor.
