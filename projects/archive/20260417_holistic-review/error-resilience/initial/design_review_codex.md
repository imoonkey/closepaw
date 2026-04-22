# Cross-Review of Claude Error Resilience Design

Reviewer: Codex | Reviewing: `design_claude.md` and `improvement_plan_claude.md`

---

## Verdict: Better Base -> **CODEX**

Claude's review is useful and technically competent, especially on graceful degradation, operator-facing UX, and low-risk cleanup work. But as a base document for implementation, it is weaker than Codex's review because it misses the two highest-severity runtime correctness bugs:

1. the agent can report completion even when `complete_task` never executed
2. approval event dispatch can fail and be misreported as a user timeout

Those are not polish issues. They are truthfulness and control-flow bugs. A base review should anchor on correctness first, then on cleanup and degradation. On that standard, **CODEX is the better base**.

Claude's improvement plan is still valuable, but it is not sufficient as the primary plan because it leaves the most important correctness issues untouched.

---

## What Claude Got Right That Codex Missed

### B1. Recoverable retry budget is too aggressive

Claude correctly called out `MAX_RECOVERABLE_RETRIES = 1` in `agent/Agent.kt` as too conservative for mobile conditions. Codex did not mention this. This is a real resilience issue: two back-to-back transient failures can kill an otherwise healthy session.

**Assessment**: Real, medium impact. Should be added to the merged plan.

### B10. `runBlocking` in `AgentService.onDestroy()` is a concrete ANR risk

Codex discussed shutdown hardening inside `SessionServices.cleanup()`, but Claude identified a more immediate Android-specific failure mode: blocking the main thread in `AgentService.onDestroy()` for up to 5 seconds.

This is more specific and more actionable than Codex's broader cleanup note.

**Assessment**: Real, medium-to-high impact. Worth keeping near the top of the implementation plan.

### A2. Context-length errors have poor user messaging

Claude noticed that context-limit failures surface raw provider/API text to the user. Codex did not call this out. This is not a structural bug, but it is a legitimate UX issue and an easy fix.

**Assessment**: Real, small-to-medium impact.

### A4. Partial stream failure semantics were analyzed more carefully

Claude called out the case where text deltas are already emitted, a partial tool-call set exists in memory, and the stream then fails. Codex did not mention this path. Claude also made the right call on severity: the current behavior is probably safest, but it should be documented as an intentional tradeoff.

**Assessment**: Real, lower priority, but good analysis.

### B2. Session/bootstrap failure is not surfaced well to chat

Claude noticed that LLM bootstrap/session creation failures are surfaced through overlay status, while the chat path loses context and the user's original input is not preserved cleanly. Codex did not include this.

**Assessment**: Real, medium impact for startup UX.

### A5. Double-classification around `CloudLlmRetry`

Claude identified the `TransientException` unwrap/reclassify behavior as confusing, even if functionally correct. Codex did not mention it.

**Assessment**: Real but low priority.

### Improvement Plan Strength

Claude's plan is strongest where the fixes are local and low-risk:

- atomic session writes
- `onDestroy()` ANR removal
- retry-budget increase
- context-limit messaging
- logging the null-session `completeSession()` guard

Those items are concrete and mostly implementation-ready.

---

## What Codex Found That Claude Missed

### Finding 1 (Critical). Planned completion is treated as executed completion

Codex found the most important bug in either review: `AgentTurnRunner` decides completion from planned tool calls, not from what actually executed. If a cognitive tool fails before `complete_task`, the turn can still be marked complete.

Claude missed this entirely, and the improvement plan therefore has no item for it.

**Assessment**: Highest-priority fix in the whole review set.

### Finding 2 (Critical). Approval-notification failure is swallowed and re-labeled as user timeout

Codex found that `TurnExecutionPhaseRunner.emitApprovalRequired()` suppresses exceptions that `ToolRouter` explicitly expects to see. The result is a fake `"Approval timed out"` outcome when the actual problem is that the approval UI/event path broke.

Claude missed this entirely, and the improvement plan therefore has no item for it.

**Assessment**: Highest-priority fix alongside Finding 1.

### Finding 4. `TASK_IMPOSSIBLE` exists but the runtime never produces it

Claude did not call out that the protocol already contains `CompletionReason.TASK_IMPOSSIBLE`, yet failed `complete_task(status = failure)` still becomes generic `ERROR`.

This matters because it preserves a user-visible distinction between:

- internal/system fault
- understood-but-impossible task

**Assessment**: Real, medium impact.

### Finding 5. Action semantics are flattened end to end

Codex found that cancelled, denied, aborted, timed-out, and failed actions all collapse into the same boolean `success = false` path, and the runtime still posts a success-style `"✓ executed"` status line.

Claude did not identify this cross-layer semantic loss. This is more than UI polish; it makes history, chat cards, and overlays lie about what happened.

**Assessment**: Real, medium impact.

### Finding 6. `ask_user` is misclassified as a screen-changing tool

Codex found that `ask_user` falls through `ToolName.Unknown`, becomes `isScreenChanging = true`, and can therefore require approval before asking for help or be denied entirely in blocked apps.

Claude missed this. It is a particularly important miss because `ask_user` exists for exactly the cases where the agent is blocked and needs user handoff.

**Assessment**: Real, high behavioral impact.

### Finding 7. Cleanup is only partially hardened

Claude focused on `onDestroy()` blocking. Codex found that `SessionServices.cleanup()` only guards `platform.stop()`, while `llmClient.cleanup()`, `llmClientFactory.cleanupAll()`, and `traceRecorder.close()` can still abort teardown midway.

Claude did not call out this deeper teardown fragility.

**Assessment**: Real, medium impact.

### Finding 9. Post-failure observation capture can escalate a local failure

Codex found that when a tool fails and has no embedded observation, `TurnExecutionPhaseRunner` falls back to screen capture without shielding. If that capture throws, a localized tool failure becomes a broader turn failure.

Claude missed this.

**Assessment**: Real, medium impact.

### Finding 10. Corrupted history silently disappears from the UI

Claude found the non-atomic session-write bug. Codex went one step further and traced the UX consequence: unreadable session files disappear from `SessionHistoryManager` and the user is given almost no explanation.

That downstream consequence is important because it changes the fix from "write atomically" to "also surface corruption explicitly."

**Assessment**: Real, medium impact.

### Finding 11. Cancellation safety is inconsistent

Codex identified the `CancellationException` hazard in broad `catch (Exception)` blocks across turn and session wrappers. Claude did not mention it.

**Assessment**: Real, medium impact.

---

## Where I Disagree With Claude's Review or Plan

### 1. `delegate_task` failure-as-success is not "the right pattern"

Claude noticed that `DelegateTaskTool` returns success even when the child agent fails, but then treated that as acceptable because the LLM still sees the failure text.

I disagree. In this codebase, `ToolCallResult.Success` does not only affect the LLM context:

- it drives action cards and history state
- it affects parent turn control flow
- it determines whether downstream code sees a failed or successful tool

So this is not merely a prompt-shaping choice. It is a structural semantic bug, and Codex was right to classify it as one.

### 2. Deleting `AgentError` outright is too weak as the default recommendation

Claude's plan recommends deleting `AgentError` / `SessionError` unless there is a strong reason to integrate them. I think that is the wrong default.

Codex's review correctly frames the core problem as **loss of meaning across layers**. Simply deleting typed errors would simplify around the current degraded path instead of fixing it.

The right sequence is:

1. decide what structured error semantics the runtime actually needs
2. make one typed envelope authoritative across agent/session/UI
3. only then remove anything still redundant

Deleting first would lock in the weaker architecture.

### 3. Claude's P0 set is too narrow

Claude's plan puts atomic writes and `onDestroy()` ANR prevention in P0, but omits:

- false completion before `complete_task`
- approval dispatch failure masked as timeout
- `ask_user` misclassification

That priority order is not strong enough. If we execute Claude's plan as written, the system will still be able to report the wrong outcome for live tasks.

---

## Overlap Between the Reviews

Both reviews independently identified important parts of the same problem space:

- `AgentError` / `SessionError` are disconnected from the live runtime
- session file durability is weaker than snapshot durability
- shutdown/error handling needs hardening

But the overlap is shallower than it first appears. Claude mainly framed these as cleanup and simplification issues. Codex framed them as part of a larger semantic-loss problem across agent, protocol, session, and UI layers. That broader framing is the better architectural base.

---

## Assessment of Claude's Improvement Plan

Claude's plan is coherent relative to Claude's own review. It is also useful as a supplement because several items are low-risk and clearly scoped.

The problem is coverage. It does **not** address:

- false positive completion before `complete_task`
- approval event dispatch masking
- `TASK_IMPOSSIBLE` never being produced
- action-state flattening
- `ask_user` classification
- `delegate_task` structural failure
- partial cleanup hardening inside `SessionServices`
- observation fallback brittleness
- cancellation exception safety
- silent disappearance of corrupted history entries

So the plan is good as a **secondary fix list**, not as the primary execution plan.

---

## Recommendation for the Merged Review / Plan

Use **CODEX** as the base review and primary plan source.

Then add the best pieces from Claude:

- B1: increase recoverable retry budget
- B10: remove `runBlocking` from `AgentService.onDestroy()`
- A2: improve context-length user messaging
- A4: document partial-stream-failure behavior as intentional
- B2: improve session/bootstrap failure surfacing
- A7: add the missing log on `completeSession()` null-session guard

That merge gives the right balance:

- Codex supplies the critical correctness and cross-layer semantic fixes
- Claude supplies the strongest degradation, UX, and operational cleanup items
