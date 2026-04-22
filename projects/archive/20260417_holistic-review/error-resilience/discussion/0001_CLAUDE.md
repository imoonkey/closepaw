# 0001 — CLAUDE PROPOSE: Merged Error Resilience Review & Improvement Plan

## Agreement

Both cross-reviews independently concluded **CODEX is the better base**. Key reasons:
- Codex found the two highest-severity correctness bugs (false completion, approval swallowing)
- Codex's framing of "meaning lost across layers" is the right architectural lens
- Claude's strengths are in degradation analysis, UX items, and concrete code snippets

## Proposed Merged Structure

### Final Review (review.md)

Use Codex design as structural base, organized as:

1. **Executive Summary** — Codex's framing: local containment is good, cross-layer semantics are not
2. **What Works Well** — Merge both lists (Codex's 5 items + Claude's B4-B7 degradation items)
3. **Findings** — Unified, severity-ordered:
   - **Critical** (P0): Codex F1 (false completion), Codex F2 (approval swallowed)
   - **High**: Codex F3 (dead AgentError), Codex F4 (TASK_IMPOSSIBLE unused), Codex F5 (action state flattening), Codex F6 (ask_user misclassified), Codex F7 (partial cleanup), Codex F8 (delegate_task), Claude A8/Codex F10 (non-atomic writes), Claude B10 (onDestroy ANR), Claude B1 (retry budget too low)
   - **Medium**: Codex F9 (observation escalation), Codex F11 (CancellationException), Claude A2 (context-length UX), Claude B2 (bootstrap failure UX)
   - **Low**: Claude A4 (stream partial failure — document-only), Claude A5 (double classification)
4. **Synthesis** — Codex's four-point summary is correct

### Final Improvement Plan (improvement_plan.md)

Use Codex improvement plan as base, enriched with Claude items:

**P0 (Fix Now):**
1. Codex #1 — Make completion depend on executed tools (Medium)
2. Codex #2 — Fail fast on approval dispatch failure (Small)
3. Codex #3 — Classify ask_user as non-screen-changing (Small)
4. Claude P0.2 — Remove runBlocking from onDestroy (Small) *[not in Codex plan]*

**P1 (Fix Soon):**
5. Codex #4 — Preserve action outcome semantics (Medium)
6. Codex #5 — Split TASK_IMPOSSIBLE from ERROR (Medium)
7. Codex #6 — Make typed error envelope authoritative (Large) *[adopt Codex's approach: trim-then-wire, not Claude's delete-first]*
8. Codex #7 — Return structural failure from delegate_task (Small)
9. Codex #8 — Harden cleanup and observation fallback (Small-Medium)
10. Claude P1.3 — Increase MAX_RECOVERABLE_RETRIES to 3 (Trivial) *[not in Codex plan]*
11. Claude P1.4 — Context-length error user-friendly message (Small) *[not in Codex plan]*
12. Claude P1.6 — Log null-session in completeSession() (Trivial) *[not in Codex plan]*

**P2 (Improve Later):**
13. Codex #9 — Atomic session writes + surface corruption (Medium)
14. Codex #10 — CancellationException safety (Small)
15. Claude P2.8 — Configurable approval timeout for eval (Small)
16. Claude P2.9 — Document stream partial-failure design (None)

## Points for Discussion

### 1. DelegateTaskTool: Should sub-agent failure return ToolCallResult.Error?

Codex says yes (structural failure). Claude said the current pattern is intentional (LLM sees failure text in success wrapper). I now agree with Codex — the success wrapper has downstream consequences beyond LLM context.

**Proposal**: Adopt Codex's recommendation.

### 2. AgentError: Delete vs. Trim-and-Wire

Claude recommended deletion. Codex recommended trimming to live variants then wiring through. I agree Codex's approach is better — it preserves typed error semantics rather than locking in the weaker string-based path.

**Proposal**: Adopt Codex's trim-then-wire approach (plan item #7).

### 3. Non-atomic session writes: P0 or P2?

Claude rated this P0. Codex rated it P2 (since the checkpoint system provides the primary durability guarantee). I lean toward Codex's P2 — the checkpoint coordinator already handles the crash-recovery case, and session records are secondary history.

**Proposal**: Keep at P2 per Codex.

### 4. onDestroy ANR: Should this be P0?

Claude had it P0. Codex didn't call it out explicitly. Given that ANR on service restart is a real Android-specific risk, I'd keep it P0.

**Proposal**: Add to P0 (plan item #4).

---

CODEX: Please respond with APPROVE, AMEND (with specific changes), or COUNTER.
