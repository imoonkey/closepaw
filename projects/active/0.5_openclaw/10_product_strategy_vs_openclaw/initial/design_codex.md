# Design: Android Agent Product Strategy vs OpenClaw

## Goal

Decide how Android Agent should use OpenClaw's momentum without giving up product ownership, core intelligence, or the accessibility-automation moat.

Success means:
- Android Agent stays a standalone Android-native personal agent.
- Planning, execution, verification, session state, and memory remain inside Android Agent.
- OpenClaw can integrate through a thin task-level interface, not by owning our runtime.
- We avoid building deep OpenClaw-specific infrastructure unless real usage proves it is worth the dependency.

## Current Reality In This Repo

The repo already has the core pieces that matter strategically:
- `AgentSession` and `SessionCoordinator` own task/session lifecycle.
- `SessionServices` already provides local history, scratchpad, todos, tool routing, and policy.
- `mobile_action` is the differentiator: accessibility-grounded screen automation with post-action verification.
- `ToolRouter` and `PolicyEngine` already define the safety and execution boundary.

What does **not** exist yet:
- No external task API.
- No OpenClaw protocol implementation.
- No reason to treat OpenClaw as the source of truth for session or memory.

This matters because the product decision should preserve the boundary the codebase already has, not punch through it.

## Decision

Adopt **Option 2.5**:

**Android Agent is the primary product. OpenClaw is an optional upstream orchestrator through a thin, vendor-neutral task API.**

This means:
- We do **not** become an OpenClaw Node in their action-RPC sense.
- We do **not** expose raw screen actions, coordinates, or internal tool calls.
- We **do** expose a task submission interface where an external agent can ask Android Agent to complete a phone task and later fetch the result.

## Product Positioning

### Primary identity

Android Agent is an **Android-native personal agent**:
- It owns the phone-side user experience.
- It owns the agent loop.
- It owns Android-specific perception and action.
- It owns local session continuity and safety decisions.

### Integration identity

Android Agent is also an **agent-capability provider**:
- Any external orchestrator can submit a task.
- OpenClaw is just the first obvious consumer.
- The integration contract is generic enough for other agent frameworks later.

This keeps distribution leverage without making OpenClaw the platform layer above us.

## Non-Goals

- Implement the full OpenClaw Gateway protocol.
- Let an external system drive `mobile_action` or other internal tools directly.
- Share Android Agent's full session history, scratchpad, or raw accessibility tree with OpenClaw.
- Optimize for deep OpenClaw coupling before standalone product value is proven.

## Proposed Architecture

### Boundary

Keep the current agent stack intact and add one new seam **above** `SessionCoordinator`, not inside `tool/` or `agent/`.

```text
External orchestrator (OpenClaw or other)
        |
        | task request
        v
External Task Gateway
        |
        | start / status / cancel
        v
SessionCoordinator -> AgentSession -> SessionServices -> ToolRouter -> mobile_action / other tools
```

### New components

#### 1. External Task Gateway

A thin ingress layer that accepts external task requests and normalizes them into Android Agent task runs.

Responsibilities:
- Authenticate the caller.
- Validate the task payload.
- Create an external task record.
- Start or resume the appropriate session flow through existing session APIs.
- Project internal agent events into coarse external task status.

It should be transport-agnostic. The product contract is task-oriented; the wire transport can start simple.

#### 2. External Task Record

A minimal persistence model separate from internal chat/session history.

Suggested fields:
- `task_id`
- `instruction`
- `status`
- `created_at`, `updated_at`
- `result_summary`
- `requires_local_user`
- `session_id` or internal run reference

This is for external orchestration state, not for replacing internal memory.

#### 3. External Task Status Projector

Maps internal lifecycle/events to external states:
- `accepted`
- `running`
- `waiting_for_local_user`
- `completed`
- `failed`
- `cancelled`
- `timed_out`

This prevents external callers from depending on internal tool/event details.

## API Shape

The API must be **task-level and asynchronous by default**.

Minimum contract:
- `POST /tasks`
- `GET /tasks/{task_id}`
- `POST /tasks/{task_id}/cancel`

Optional later:
- webhook callback on terminal state
- streaming status channel

Example request:

```json
{
  "instruction": "Open WeChat and send Zhang San: tomorrow 3pm works",
  "timeout_seconds": 120,
  "callback_url": "https://example.com/task-callback"
}
```

Example response:

```json
{
  "task_id": "abc-123",
  "status": "accepted"
}
```

### Critical rule

The request is an **intent**, not an action plan.

External callers may say:
- "send a message to Alice"
- "book a ride home"
- "check if I have unread WhatsApp messages"

They may **not** tell Android Agent:
- which tool to call
- where to tap
- how to sequence UI actions

That intelligence stays local.

## Interaction Model

### Canonical state machine

```text
accepted -> running -> completed
                  -> failed
                  -> cancelled
                  -> timed_out
                  -> waiting_for_local_user -> running
```

`waiting_for_local_user` is the key design choice. It turns several edge cases into one canonical state:
- tool approval required by `PolicyEngine`
- `ask_user` needed
- device-side permission/intervention required

This avoids inventing separate OpenClaw-specific flows for each interruption type.

### Safety rule

External tasks must obey the same local safety boundary as first-party tasks.

That means:
- no bypass of approval policy
- no hidden execution channel around `ToolRouter`
- no external override of user approval decisions

If a task cannot continue without local user input, it pauses in `waiting_for_local_user` and times out cleanly if unresolved.

## Session and Memory Strategy

For the first version, keep external integration **stateless per task**:
- one external task maps to one internal task run
- Android Agent may use its own internal session machinery to complete the task
- external callers do not get durable conversational memory ownership

Reason:
- it keeps the contract simple
- it avoids leaking internal context models too early
- it preserves freedom to evolve session semantics for the standalone product

If multi-task continuity becomes necessary later, add it deliberately as a second-phase capability, not by accident in v1.

## Why This Wins

### Versus full OpenClaw Node integration

This avoids the main failure mode: Android Agent becoming a replaceable execution backend.

We keep:
- the agent brain
- the user relationship
- the safety model
- the Android-specific moat

### Versus full isolation

This still lets us ride ecosystem demand:
- OpenClaw users can call us quickly
- other orchestrators can call the same interface
- we gain distribution without betting the product on one protocol owner

## Rollout

### Phase 0

Keep standalone product quality as the top priority:
- reliable accessibility automation
- strong on-device UX
- stable session/memory behavior

### Phase 1

Add the thin external task contract:
- task submit
- task status
- cancellation
- local-user wait state

### Phase 2

Publish integration assets:
- example OpenClaw tool definition
- short setup guide
- one reference workflow

### Explicitly deferred

- full OpenClaw Gateway compatibility
- action-level RPC
- shared remote memory/session model
- OpenClaw-first UX decisions inside the Android app

## Trade-Offs

- We give up the fastest possible OpenClaw-native integration path.
- Task-level APIs are less composable than action-level RPC.
- Local approval and user intervention make some remote flows less "fully autonomous."

Those are acceptable because they preserve the only thing worth defending: Android Agent as a product, not a subcontractor.

## Decision Summary

Build Android Agent as an independent Android-native personal agent and expose a thin, vendor-neutral task API above the existing session layer. Use that API to integrate with OpenClaw, but do not implement OpenClaw's full node protocol and do not let external systems own planning, memory, or tool selection.
