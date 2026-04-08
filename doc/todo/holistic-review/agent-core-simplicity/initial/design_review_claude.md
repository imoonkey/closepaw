# Cross-Review of Codex's Agent Core Simplicity Design

**Reviewer:** CLAUDE
**Reviewed:** design_codex.md + improvement_plan_codex.md

---

## What Codex Gets Right

### 1. The one-screen-action invariant is the deepest finding

Codex's #1 finding (the runtime does not enforce the one-screen-action invariant) is the most architecturally significant insight in either review. The analysis is precise:

- Prompts describe "at most one screen-changing action per turn"
- Runtime allows multiple via TurnToolPolicy keeping all screen-changing tools
- TurnExecutionPhaseRunner chains post-action snapshots within one turn
- The precomputed `actionForNextTurn` creates a concrete correctness bug: if an earlier tool fails before a planned `mobile_action`, the next turn's NavigationState records the mobile_action as if it happened

This is not just a code smell; it is a design invariant violation with a real correctness consequence. Claude's review did NOT identify this issue at all. This is Codex's most valuable unique contribution.

### 2. Correct ordering principle

Codex's sequencing note is exactly right: "reduce runtime freedom first, then delete what becomes unnecessary." This top-down ordering means:

- P0: Enforce the invariant → simplifies TurnToolPolicy, TurnExecutionPhaseRunner, completion deferral, action signatures
- P1: Split ExecutorStepPolicy + unify definitions (structural, feeds cleanup)
- P2+: Trim what's left

This is more architecturally sound than Claude's bottom-up approach (dead code first). Dead code removal is satisfying but low-leverage compared to fixing the fundamental invariant.

### 3. Dual definition system analysis

Codex correctly identifies literal data-copying between AgentDef and AgentDefinition. The recommendation for one type that answers role, prompt, tools, delegatability, and execution role is well-scoped.

### 4. ExecutorStepPolicy decomposition

The split into "is this the final turn?" helper + standalone formatter is clean. Codex correctly identifies that `WarnApproaching` is effectively dead.

---

## Gaps in Codex's Review

### G-1: Missed dual cancellation signals (Agent.kt)

Agent.kt has both `CompletableDeferred<AgentStopReason>` and `AtomicBoolean(stopRequested)` for the same purpose. Both are checked in `shouldContinue()`. A single deferred suffices. Claude caught this; Codex did not.

### G-2: Missed Turn.kt text recovery complexity

130 lines of defensive parsing for LLM text-as-tool-call recovery (object-wrapped, inline markers, balanced JSON extraction, markdown fence stripping). This is significant complexity that may be vestigial with modern function-calling models. Claude flagged this for measurement; Codex missed it entirely.

### G-3: Missed hardcoded magic delays

`TurnExecutionPhaseRunner.kt:42,216` has `delay(200)` and `delay(500)` with no comments or configurability. The 500ms post-action delay should probably use `config.uiSettleDelayMs` for consistency. Codex didn't mention this.

### G-4: Missed narrativeSummaryOnLimit always-true

The `narrativeSummaryOnLimit` parameter is always `true` in all call sites. Codex identified ExecutorStepPolicy issues but missed this specific premature configuration.

### G-5: No positive pattern recognition

Codex's review is entirely issue-focused. Claude noted well-applied KISS patterns (minimal TurnRunnerState, clean TurnOutcome sealed class, well-scoped AgentStopReason, good LoopDetectionPolicy simplification comments). Acknowledging what works prevents future simplification from accidentally regressing good patterns.

### G-6: SubAgentRunner file density not flagged

SubAgentRunner.kt contains 7 distinct types in 288 lines. While Codex analyzed the dual definition system thoroughly, it didn't flag the file-level density as an independent concern.

---

## Where Codex's Analysis Could Be Sharper

### S-1: Improvement plan item 5 (flatten turn DTOs) is lower value than positioned

Codex puts "flatten turn orchestration data flow" at P2, suggesting inlining PreTurnContext, PreparedTurn, PlanningPhaseOutput, etc. But some of these DTOs serve documentation purposes even if only used once — they name the contract between phases. The dead field `appTier` should go, but wholesale DTO removal needs more nuance about which ones genuinely add confusion vs which clarify the flow.

### S-2: Observation representation unification (item 6) underspecified

Codex says "one canonical observation payload per turn" but doesn't address the practical tension: prompt rendering needs a specific format (with vision/token considerations), while history rendering may need a different shape. The solution isn't just "merge them" — it's "extract the screen state once, then have render functions project from it."

### S-3: Event emission consolidation direction not chosen

Item 7 says either centralize all in dispatcher OR reduce the dispatcher. But Codex's own analysis shows the dispatcher covers ~10 event types while only 2-3 bypass it. The answer is almost certainly "add the missing methods to the dispatcher," not "flatten everything." The non-decision weakens the recommendation.

---

## Comparison to Claude's Review

| Aspect | Codex | Claude |
|--------|-------|--------|
| Deepest architectural insight | One-screen-action invariant (unique, critical) | Definition system over-engineering |
| Ordering philosophy | Top-down: fix invariants first | Bottom-up: dead code first |
| Code-level coverage | 9 items | 12 B-items + 3 cross-cutting |
| Unique valuable findings | Action invariant, correctness bug | Dual cancellation, text recovery, magic delays |
| Improvement plan specificity | Good acceptance checks | Good risk/line-count estimates |
| Positive patterns noted | None | Yes (C-2 section) |

---

## Verdict

**CODEX is the better base for the first aligned draft.**

Reasons:

1. The one-screen-action invariant enforcement is the highest-leverage finding across both reviews and is unique to Codex.
2. The top-down ordering principle (fix invariant → cascading simplifications → cleanup) is architecturally sounder than bottom-up dead-code-first.
3. Codex's improvement plan has clear acceptance checks for each item.

However, the aligned draft MUST incorporate Claude's unique findings (dual cancellation, text recovery audit, magic delays, narrativeSummaryOnLimit, positive patterns). Codex's plan is the skeleton; Claude's granular findings fill the gaps.
