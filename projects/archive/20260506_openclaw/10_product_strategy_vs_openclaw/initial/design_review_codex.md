# Review: `design_claude.md`

## Summary

The strategic verdict is correct: Android Agent should stay a standalone Android-native agent and expose, at most, a thin task-level integration seam to OpenClaw. The main issues are in the concrete adapter design, where the doc overstates how directly the current runtime maps to an external task API and understates the security boundary.

## High

1. **Timeout semantics are incorrect and should not be based on `maxTurns`.**  
   `design_claude.md:93-100` maps external task timeout to `SessionConfig.maxTurns`, but `SessionConfig.maxTurns` is a turn budget, not a wall-clock deadline ([SessionConfig.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionConfig.kt#L12)). A 120-second API timeout and a 10-turn limit are not equivalent. This would produce misleading API behavior and make cancellation nondeterministic. The design should require a real elapsed-time watchdog per external task and a clear interrupt path when that deadline expires.

2. **The task model conflicts with the current single-session coordinator and is underspecified for cancellation/status.**  
   `design_claude.md:71-118` presents a clean `task_id -> status -> cancel` model, but the current runtime is centered on one `SessionCoordinator.currentSession` that accepts plain text input, queues more text while busy, and does not manage multiple typed external tasks ([SessionCoordinator.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/SessionCoordinator.kt#L25), [SessionCoordinator.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/SessionCoordinator.kt#L51), [SessionCoordinator.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/SessionCoordinator.kt#L94)). `AgentSession` also tracks only one current task internally ([AgentSession.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt#L222)). As written, `DELETE /v1/tasks/{task_id}` can only be correct if the design first chooses one of these models explicitly:
   - one external task at a time, rejecting new ones while busy
   - a serialized external queue with no per-task isolation
   - one dedicated session per external task

   Without that choice, `task_id`, status polling, and cancellation are only superficial labels over a different runtime model.

3. **`localhost` without auth is not a safe trust boundary on Android.**  
   `design_claude.md:132-139` allows `authToken = null` for local-only use, and `design_claude.md:146-153` treats external instructions as the same trust boundary as manual input. That assumption is too weak. A loopback HTTP server on the device is not equivalent to first-party UI input; other apps on the device may be able to reach it. The design should require authentication even for localhost and define token generation, rotation, revocation, and UI disclosure more explicitly. At minimum, the doc should remove the idea that "local-only" means "safe enough for no auth."

## Medium

1. **The adapter is presented as thinner than it really is.**  
   `design_claude.md:73-89` says "No core refactoring needed" and shows `SessionCoordinator.submit(op)`, but `SessionCoordinator.submit` currently accepts `text: String`, not an `Op`, and all its public methods rely on main-thread confinement ([SessionCoordinator.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/SessionCoordinator.kt#L21), [SessionCoordinator.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/SessionCoordinator.kt#L51)). That does not kill the idea, but it means the bridge is not just a 1:1 transport wrapper. The design should acknowledge a small orchestration layer that marshals onto the session owner thread and arbitrates with local user-driven input.

2. **`TaskApiConfig` does not belong in `SessionConfig`.**  
   `design_claude.md:132-140` puts API server settings into `SessionConfig`, but `SessionConfig` is an immutable per-session runtime config for agent behavior, models, platform mode, and policy ([SessionConfig.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionConfig.kt#L12)). Port binding, auth token, and server enablement are app/service-level concerns, not task/session concerns. This should be simplified by introducing app-level integration settings instead of threading network-server config through every agent session.

3. **The optional WebSocket design leaks more internal cognition than the product position needs.**  
   `design_claude.md:63-69` streams `thought` content, while the stated strategy is to keep Android Agent's brain local and expose only a task-level capability. That is the wrong default. If streaming exists at all, it should be limited to coarse progress states or audited action summaries, not internal reasoning text.

4. **In-memory task records are too weak for an async API contract.**  
   `design_claude.md:116-129` uses an in-memory `Map<String, TaskRecord>` for status queries. That is easy, but it means process death, service restart, or app eviction destroys externally visible task state, even though the app already has checkpoint/reload machinery for session continuity. The design should either say the API is explicitly ephemeral in v1 or persist a minimal external task ledger. Right now it implies stronger durability than it actually provides.

5. **The doc gets too implementation-specific too early.**  
   `design_claude.md:112-118` and `design_claude.md:238-243` lock in NanoHTTPd, concrete LOC estimates, and a Ktor-vs-NanoHTTPd decision inside what is mainly a product/architecture decision. That detail is premature. The design becomes tighter if it defines the task contract and lifecycle first, then leaves transport/library choice to implementation.

## Low

1. **Phase 1 scope is a bit noisy.**  
   `design_claude.md:205-209` includes voice I/O in the priority list, but the rest of the design is really about strategic positioning and the external task seam. Mentioning voice here dilutes the document slightly without changing the decision.

## Recommendation

**CHANGES_REQUESTED**

Keep the strategic choice. Tighten the design around three points before using it as the project guide:
- define the exact external task-to-session model
- define a real timeout/cancellation contract
- harden the trust model so localhost is not treated as equivalent to first-party input
