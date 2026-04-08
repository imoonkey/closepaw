# Cross-Review of Codex Protocol Design

Reviewer: CLAUDE
Date: 2026-04-08

---

## Agreement

Both reviews converge on the same core conclusion: the command/state layer (`Op`, `SessionState`) is solid; the event and config layers carry more surface than the runtime justifies. The following findings are shared:

1. **Domain marker interfaces are unused** -- both identify the 12 markers in `AgentEventDomains.kt` as dead abstraction.
2. **`AgentError` is dead code** -- neither found any construction or dispatch site.
3. **`SessionError` is never emitted** -- both flag this as a phantom contract.
4. **`TodosUpdated` / `ScratchpadUpdated` are emitted but never consumed** -- both recommend removal.
5. **`sanitizeThought()` is misplaced** -- both note it belongs outside `protocol/`.
6. **File granularity is higher than warranted** -- both see room for consolidation.

These overlapping findings have high confidence -- they should proceed without further debate.

---

## What Codex Found That Claude Missed

### 1. CompletionReason conflation (HIGH)

Codex correctly identifies that `CompletionReason` serves two distinct semantic roles: task outcome (`GOAL_ACHIEVED`, `MAX_TURNS`, `TASK_IMPOSSIBLE`, `ERROR`) and session end reason (`USER_STOPPED`, `IDLE_TIMEOUT`, `INTERRUPTED`). Claude's review treated `CompletionReason` as fine since "all fields are used" without noticing that `SessionCompleted` can never carry `GOAL_ACHIEVED` or `TASK_IMPOSSIBLE`, and `TaskCompleted` can never carry `IDLE_TIMEOUT`. This is a real type-safety gap. The split into `TaskOutcome` + `SessionEndReason` would eliminate impossible states at the type level.

**Verdict: Codex is right. This is the highest-value structural fix that Claude overlooked.**

### 2. SessionConfig shape and persistence inconsistency (HIGH)

Codex flags that `SessionConfig` mixes execution, model routing, observability, and eval knobs, and that `SessionCheckpointCoordinator` silently drops fields on reload. Claude noted "right granularity" based on each field having a consumer, but missed the persistence mismatch and the fact that `SessionLlmConfig` permits contradictory states (`backendType = OPENAI` with non-null `localConfig`).

**Verdict: Codex's analysis is deeper. The persistence gap is a real bug vector.**

### 3. Approval ID inconsistency -- `callId` vs `actionId` (MEDIUM)

Codex traces the full approval flow and finds that `Op.Approve` uses `actionId` while `ToolRouter`, `ApprovalDetails`, and `Op.UserResponse` use `callId` for the same underlying value. Claude did not surface this naming divergence.

**Verdict: Valid finding. Low-risk fix with high readability payoff.**

### 4. TurnStarted.phase redundancy (MEDIUM)

Codex notes that `TurnStarted.phase` is always `PERCEPTION` and is immediately followed by `TurnPhaseChanged(PERCEPTION)`, making one redundant. Claude mentioned `TurnPhase` but concluded "justified" without checking the emission pattern.

**Verdict: Codex is right. One emission should be removed.**

### 5. ApprovalResolved emitted but not consumed (MEDIUM)

Codex identifies that `ApprovalResolved` is emitted in `AgentSession` but has no event consumer. Claude did not flag this.

**Verdict: Valid. Same category as TodosUpdated/ScratchpadUpdated -- emit-without-consume.**

### 6. Protocol vs UI projection boundary (MEDIUM)

Codex raises the question of whether `protocol/` is a domain contract or a UI event stream, noting that `StatusUpdate` and `ThoughtUpdate` are display-shaped. Claude noted `StatusUpdate.emoji` as dead but didn't question the broader boundary.

**Verdict: Good architectural question. Codex's Option B (protocol = domain contract, move display events out) is the right direction.**

---

## What Claude Found That Codex Missed

### 1. Concrete deletion steps with line counts

Claude's improvement plan specifies exact files, exact steps, and line-count estimates for each change. Codex's plan is higher-level and defers execution details. For the shared findings (dead code removal), Claude's plan is more immediately actionable.

### 2. StatusUpdate.emoji dead field

Claude specifically identified the `emoji: String? = null` field as dead (never populated with non-null). Codex mentions emoji in passing but doesn't isolate this as a distinct cleanup item.

---

## Where They Disagree

### Improvement plan ordering

- **Claude** prioritizes deletions first (P1), then design decisions (P2), then hygiene (P3-P4).
- **Codex** prioritizes semantic fixes first (CompletionReason split, approval naming), then deletion, then config reshape.

Codex's ordering is arguably better for a structured improvement: fix the semantic model first so that deletions align with the corrected design. However, Claude's P1 deletions are zero-risk and could land immediately as a quick win before the harder semantic work.

**Recommendation: Hybrid. Land Claude's P1 deletions first (zero-risk, immediate cleanup), then proceed with Codex's Phase 1-2 semantic fixes.**

---

## Better Base

**CODEX** is the better base for the final improvement plan.

Rationale:
- Codex found 4 structural issues that Claude missed entirely (CompletionReason conflation, SessionConfig persistence gap, approval ID inconsistency, TurnStarted.phase redundancy)
- Codex's improvement plan addresses root causes (type-level impossible states, semantic clarity), not just symptoms (dead code)
- Claude's concrete deletion steps should be merged into Codex's framework as the execution detail for Phase 1

The final plan should be Codex's structure + Claude's P1 deletion specifics + the hybrid ordering (quick deletions first, then semantic fixes).
