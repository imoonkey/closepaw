# Cross-Review of Codex Error Resilience Design

Reviewer: Claude | Reviewing: design_codex.md

---

## Verdict: Better Base → **CODEX**

Codex found two critical correctness bugs that Claude's review missed entirely, plus several high-value behavioral findings. Claude's review is stronger on graceful degradation analysis (the B-section), but correctness bugs outweigh degradation reporting.

---

## Findings Codex Got That Claude Missed

### Finding 1 (Critical) — `complete_task` never executes if prior tool fails

This is the most important finding in either review. `AgentTurnRunner.decideCompletion()` looks at the planned tool calls, not at which ones actually executed. If `remember_experience` fails before `complete_task` runs, the turn still reports completion. This is a correctness bug with real user impact — the agent can report success when it never actually called `complete_task`.

**Assessment**: Real, high impact. Must be in the final plan as P0.

### Finding 2 (Critical) — Approval notification failure swallowed, becomes "timed out"

`TurnExecutionPhaseRunner.emitApprovalRequired()` catches exceptions from the event emitter, so `ToolRouter` never learns the approval UI failed to display. It waits 60 seconds, then reports "Approval timed out" — blaming the user for not responding to a prompt they never saw.

**Assessment**: Real, high impact. Root cause is lost and user is incorrectly blamed. Must be P0.

### Finding 4 — `TASK_IMPOSSIBLE` never produced by runtime

The distinction between "system fault" and "agent concluded task is impossible" is thrown away. Both map to `CompletionReason.ERROR`. The protocol defines `TASK_IMPOSSIBLE` but nothing produces it.

**Assessment**: Real, medium impact. The UI and history layers are already wired for it. Worth fixing.

### Finding 5 — Action states flattened (denied/cancelled/timed-out/failed all → "failed")

`ActionState.Skipped` exists in the UI model but is never used. The `✓ executed` status line is emitted even for failed actions. This is a UX correctness issue.

**Assessment**: Real, medium impact. Good eye on the dormant `ActionState.Skipped`.

### Finding 6 — `ask_user` classified as screen-changing tool

Because `ask_user` isn't in the `ToolName` enum, it defaults to `isScreenChanging = true`. That means it requires approval before asking the user a question, and is denied entirely in blocked apps. This is backwards for its purpose (login, CAPTCHA, disambiguation).

**Assessment**: Real, high behavioral impact. The tool exists precisely for situations where the agent is blocked, but the policy blocks the tool too.

### Finding 9 — Post-action observation capture can escalate local failure

A failed tool triggers `captureObservationWithSnapshot()` without shielding. If the screen capture also throws, a local tool failure becomes a turn-level failure. The recovery loop should be more resilient than the primary path.

**Assessment**: Real, medium impact. Good catch — error handling should not itself be fragile.

### Finding 11 — CancellationException caught as generic failure

Broad `catch (Exception)` blocks risk converting cooperative cancellation into error completion. This is a known Kotlin coroutine pitfall. Some paths handle it but the design isn't consistently cancellation-safe.

**Assessment**: Real, medium impact. Worth auditing systematically.

---

## Findings Claude Got That Codex Missed

### A4 — Stream failure after partial output loses tool calls

When a stream error occurs after emitting text + partial tool calls, the tool calls are discarded. Claude correctly analyzed this and concluded the current behavior (fail the turn, let agent-level retry re-run) is the safest option — just needs documentation.

### A5 — CloudLlmRetry throws cause, losing TransientException wrapper

The unwrapping of `TransientException` means the same exception gets classified twice in the non-streaming path. Correct but confusing. Low priority.

### B1 — Agent recoverable retry budget is too low

`MAX_RECOVERABLE_RETRIES = 1` means two back-to-back transient errors kill the session. Counter resets on success, but on flaky mobile networks, 1 is too aggressive.

**Assessment**: Codex didn't mention this. Real, medium impact. Should be in final plan.

### B3 — Platform start failure on follow-up tasks

Re-arms idle timeout but leaves session in `Idle` state. User can retry before timeout. Correct behavior but worth noting.

### B10 — `runBlocking` in `onDestroy()` risks ANR

5-second timeout on main thread. Claude identified this as P0. Codex's finding 7 covers shutdown cleanup hardening but doesn't call out the ANR risk specifically.

---

## Overlap (Both Reviews Found)

| Topic | Claude | Codex |
|-------|--------|-------|
| AgentError/SessionError dead code | A1, A3 | Finding 3 |
| Session file write not atomic | A8 | Finding 10 |
| DelegateTaskTool returns success on failure | B8 | Finding 8 |
| Context-length error UX | A2 | (not found) |
| Shutdown cleanup fragility | B10 | Finding 7 |

---

## Where Codex Is Stronger

1. **Correctness bugs**: Findings 1 and 2 are the highest-impact items across both reviews. Claude missed them.
2. **Cross-layer semantic analysis**: Codex's framing of "meaning is lost across layers" (errors → strings → booleans → generic reasons) is the right architectural lens.
3. **Behavioral classification bugs**: The `ask_user` misclassification (finding 6) is a real operational problem.

## Where Claude Is Stronger

1. **Degradation analysis**: The B-section systematically catalogs what works (B4-B7 are all "correct, well done").
2. **Prioritized action plan**: Claude's improvement plan has concrete code snippets and effort estimates.
3. **Retry budget analysis**: B1 is a real mobile-network issue that Codex didn't identify.
4. **ANR risk specificity**: Claude called out `runBlocking` on main thread explicitly.

---

## Recommendation for Final Merged Review

Use **Codex** as the structural base (findings-driven, cross-layer analysis), enriched with:
- Claude's B-section degradation analysis (what works well)
- Claude's retry budget finding (B1)
- Claude's ANR-specific analysis of `onDestroy`
- Claude's concrete code snippets from the improvement plan
