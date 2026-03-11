# Cross-Review: design_codex.md

Reviewer: Claude

## Overall Assessment

The design is well-structured and strategically sound. It reaches the same Option 2.5 conclusion as my design, which increases confidence in the direction. The writing is clear, the boundary placement (above `SessionCoordinator`, not inside `tool/` or `agent/`) is correct, and the non-goals are well-chosen.

The main gaps are in implementation specifics, security, and a few under-explored edge cases.

## Strengths

1. **`waiting_for_local_user` state.** This is the strongest unique contribution. Collapsing PolicyEngine approval, `ask_user`, and device-side permission into one canonical external state is elegant and avoids leaking internal interruption types to callers. My design missed this — I flagged approval mode tension as an open question but didn't propose a unified state.

2. **Transport-agnostic framing.** Describing the gateway as transport-agnostic (task contract first, wire format second) is the right abstraction level. Avoids premature commitment to HTTP vs WebSocket vs anything else.

3. **Stateless-per-task session model.** Explicitly choosing one external task = one internal run, with no durable external memory ownership, is the correct v1 simplification. It prevents accidental context leakage and keeps the internal session model free to evolve.

4. **"Intent, not action plan" rule.** Clearly stated and critical. This is the core moat-preservation principle.

## Issues

### 1. No security model (High)

The design has no discussion of authentication, authorization, or network binding. Questions that need answers:

- Who can submit tasks? Any process on the device? Any device on the LAN?
- What prevents a malicious app or LAN neighbor from submitting "send all my photos to attacker@evil.com"?
- Is the API localhost-only by default? If LAN-accessible, what's the opt-in mechanism?

This is not a nice-to-have. An unauthenticated task API on a phone that can operate any app is a serious attack surface. The design should specify at minimum: bearer token auth, localhost-only default, explicit user opt-in for LAN exposure.

### 2. No implementation mapping (Medium)

The design says "External Task Gateway" but doesn't specify what this is in code. Key questions:

- Embedded HTTP server? Which library? (NanoHTTPd is ~50KB with zero transitive deps; Ktor adds ~2MB but gives WebSocket + coroutine integration for free.)
- Where does it live in the module structure? New module? Inside `app/`? Inside `platform/`?
- How does it start/stop relative to `AgentSession` lifecycle? Is it tied to the accessibility service? Does it survive app backgrounding?

Without these, the gap between "design" and "implementation" is large enough to hide significant scope surprises.

### 3. `POST /tasks/{task_id}/cancel` vs `DELETE /tasks/{task_id}` (Low)

The design uses `POST /tasks/{task_id}/cancel`. This works but is non-standard REST. `DELETE /tasks/{task_id}` is more conventional for cancellation of a running resource. Either is fine, but the choice should be deliberate — `DELETE` implies the task record is also cleaned up, `POST cancel` implies the record persists with `cancelled` status. Given that callers will want to `GET` the final status after cancellation, `POST cancel` is actually better. Worth a one-line rationale.

### 4. Timeout semantics are underspecified (Medium)

The API accepts `timeout_seconds` but the design doesn't explain how this maps internally. Options:

- Wall-clock timer that fires `Op.Interrupt` — simple but may kill mid-action
- Mapped to `SessionConfig.maxTurns` — imprecise (turn duration varies 2-30s)
- Deadline-aware loop that checks remaining time between turns — cleanest

The choice affects whether a timed-out task leaves the phone in a dirty state (mid-navigation, keyboard open, wrong app foregrounded).

### 5. Queue behavior for concurrent requests (Medium)

The design says nothing about what happens when a second task arrives while one is running. Options:

- Reject with 409/429 and `Retry-After` header
- Queue and return 202 with queue position
- Support concurrent sessions (requires multiple accessibility service instances — not possible on Android)

Since Android accessibility service is a singleton, concurrent execution is physically impossible. The design should state this constraint explicitly and specify the rejection/queuing behavior.

### 6. External Task Record persistence (Low)

The design mentions a "minimal persistence model" but doesn't say where it lives. In-memory is fine for v1 (tasks are short-lived), but if the process dies mid-task, the caller loses all state. Worth stating the v1 choice explicitly: in-memory, no persistence, caller should handle lost connections.

### 7. Missing `timed_out` transition detail (Low)

The state machine shows `timed_out` as a terminal state but doesn't show the transition edge from `running` or `waiting_for_local_user`. Both should be able to time out. The `waiting_for_local_user` → `timed_out` path is especially important — if a task needs device-side approval and the user is away, it should time out cleanly rather than hang.

## Simplification Opportunities

1. **Drop `callback_url` from v1.** Polling via `GET /tasks/{id}` is sufficient and avoids callback reliability issues (caller unreachable, retries, idempotency). Add webhooks only when real usage demands it.

2. **Drop streaming status channel from v1.** Same reasoning — polling is good enough for task-level granularity (tasks take 10-120s, not hours).

3. **Merge "External Task Gateway" and "External Task Status Projector" into one class.** The projector logic is ~20 lines of `when(event)` mapping. A separate component adds naming overhead without architectural benefit at this scale.

## Alignment with Source Brief

The design faithfully implements the brief's Option 2.5 recommendation. The phasing matches (standalone first, API second, OpenClaw bridge third). The "intent not action" principle aligns with the brief's "we are the brain, not the fingers."

One brief recommendation not addressed: **bidirectional integration** (our agent calling OpenClaw's capabilities). The design is purely inbound. Worth at least a one-line acknowledgment that outbound delegation is a future phase.

## Summary

Solid strategic design with the right boundary decisions. The `waiting_for_local_user` state is a genuine insight. Main gaps are security model (must be addressed before implementation), implementation specifics (library choice, lifecycle, module placement), and concurrent task handling. These are all tractable — the design needs detail, not rethinking.
