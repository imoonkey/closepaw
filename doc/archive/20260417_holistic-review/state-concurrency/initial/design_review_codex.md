# Cross-Review: CLAUDE vs CODEX State-Concurrency Designs

## Summary

**Better base: CODEX.**

Reason:

- CODEX has materially better severity calibration.
- CODEX covers the highest-impact failure modes: data loss, invalid session transitions, lifecycle interleaving, and fake cancellation.
- CLAUDE is better at spotting a few low-cost local hardening fixes, but it substantially underestimates the dominant risks.

The right synthesis is:

- use **CODEX** as the base design and plan
- merge in a small set of **CLAUDE** low-cost hardening items
- trim one lower-priority phase from the CODEX plan until the core invariants are fixed

## Critical

### 1. CLAUDE under-calibrates the highest-risk issue: persistence ordering and data loss

This is the biggest difference between the two designs.

CLAUDE treats the recording stack as mostly sound and only calls out `completeSession()` not awaiting the final save as low risk. That misses the more dangerous issue: `SessionRecordingService` allows overlapping save/checkpoint work where an older snapshot can finish after a newer one, and `SessionStorage.writeSession()` is not atomic. That is a true state-corruption / stale-write class issue.

CODEX correctly puts this at the top. For a state/concurrency review, that is the right calibration.

Why this matters for choosing a base:

- A review that misses the main data-loss hazard is not a safe base, even if many of its smaller observations are correct.

### 2. CLAUDE incorrectly marks the session state machine as valid

CLAUDE says the `AgentSession` state machine is valid and all transitions are okay. That is too generous.

The takeover path publishes `Paused` before pause confirmation arrives, and `Resume` is accepted in that window. That means the implementation does not actually match the declared contract for takeover/pause. CODEX correctly identifies this as a correctness bug, not a minor nuance.

Why this matters for choosing a base:

- In a state-management review, invalid published states should outrank principle-only races.
- CODEX catches that; CLAUDE does not.

### 3. CLAUDE misses two major cross-component issues: lifecycle serialization and real tool cancellation

CLAUDE focuses on local races such as `ToolRegistry`, `onMutation` visibility, and `SessionAgentRunner` publication ordering. Those are real, but they are not the dominant hazards.

The larger issues are:

- `AgentSession` lifecycle work is not serialized across suspend points.
- `ToolRouter.cancel()` / `cancelAll()` mostly clean up bookkeeping and approval waiters, but do not actually signal executing tools.

CODEX catches both. CLAUDE does not.

Why this matters for choosing a base:

- These are the kinds of bugs that produce user-visible invalid states during shutdown, takeover, or cleanup.
- Missing them makes the CLAUDE plan too small to be trustworthy.

## High

### 1. CLAUDE’s improvement plan is too narrow relative to the real risk

CLAUDE’s plan is disciplined and small:

- `ConcurrentHashMap` for `ToolRegistry`
- `@Volatile` on listener fields
- `SessionAgentRunner` ordering fix
- some cleanup items

That is good local hygiene, but it does not materially reduce the main risks found in the code. It patches edges while leaving the core ownership model unchanged.

This is the key tradeoff:

- **CLAUDE is smaller, but too small.**
- **CODEX is broader, but it targets the right invariants.**

### 2. CODEX is broader, but still more aligned with KISS at the system level

At first glance, the CODEX plan is larger. But the direction is actually more KISS:

- one serialized lifecycle owner for session state
- one serialized writer for session/checkpoint persistence
- true cancellation state owned by `ToolRouter`
- explicit transient state for takeover/pause

That reduces the number of synchronization stories in the system. It replaces "many small local conventions" with "fewer strong owners". For this subsystem, that is simpler in the end-state even if the refactor is larger.

### 3. One part of the CODEX plan should be de-prioritized

The weakest part of the CODEX plan is Phase 5:

- bootstrap/event-path hardening
- possible `SharedFlow` / `MessageDelta` backpressure reconsideration

That is reasonable follow-up work, but it is not as load-bearing as:

1. persistence single-writer
2. lifecycle serialization
3. takeover state fix
4. real tool cancellation
5. explicit shutdown cause

So CODEX is the better base, but its later phase ordering should be tightened.

## Medium

### 1. CLAUDE found several useful low-cost fixes that CODEX should absorb

These are good additions and should be folded into the CODEX base where they do not conflict with the larger redesign:

- make `ToolRegistry` thread-safe if it remains mutable and shared
- add `@Volatile` to `TodoState.onMutation`
- add `@Volatile` to `HistoryManager.onMutation`
- fix `SessionAgentRunner.start()` publication ordering
- simplify `SessionHistoryManager` cache strategy by choosing either `Mutex` or concurrent map, not both

These are not the main story, but they are good cleanup.

### 2. CLAUDE spends attention on lower-leverage analysis sections

CLAUDE’s deadlock and leak sections are competent, but they do not move the decision much because:

- no deadlock evidence was found
- no major leak was found
- the real problems are state validity and coordination

This is another calibration difference: CLAUDE spends more space proving the absence of low-probability classes while underweighting the highest-impact ones.

### 3. CLAUDE drifts slightly from the requested hotspot list

CLAUDE spends notable attention on `ToolRegistry.kt`, which was not part of the user’s named scope. It is adjacent and worth mentioning, but it should not have become the lead actionable item in the plan.

CODEX stays closer to the requested hotspot set.

## Recommendation

Use **CODEX** as the base.

Why:

- better severity calibration
- better coverage of high-impact correctness failures
- improvement plan addresses ownership and invariants, not just local field hardening

Merge in these CLAUDE items immediately:

- `ToolRegistry` thread-safety hardening
- `@Volatile` listener fields in `TodoState` and `HistoryManager`
- `SessionAgentRunner.start()` state-publication ordering fix
- `SessionHistoryManager` cache simplification

Use this refined execution order:

1. CODEX Phase 1: persistence single-writer + atomic session writes
2. CODEX Phase 2: serialized `AgentSession` lifecycle
3. CODEX Phase 3: real takeover pending/paused semantics
4. CODEX Phase 4: real `ToolRouter` cancellation
5. CODEX Phase 6: explicit shutdown cause semantics
6. CODEX Phase 5 only after the above, and only if event-path evidence justifies it

## Bottom Line

If the choice is strictly **CLAUDE or CODEX as the better base**, the answer is **CODEX**.

CLAUDE is useful as a supplement for local hardening. It is not the better base because it is too optimistic about the current system and too narrow in the fixes it proposes.
