# Cross-Review of Codex's Tool System Design Review

**Reviewer:** Claude
**Reviewed:** Codex's `design_codex.md` and `improvement_plan_codex.md`

---

## Agreement

### Codex's Critical Finding #1 (Blocked-App Boundary) -- STRONG AGREE

This is Codex's strongest contribution. My review flagged the `maskIfBlocked` path narrowness (A.3.3) and the TOCTOU after approval (A.3.2) but did not connect the dots into the end-to-end security gap:

> From a NORMAL app, the agent can navigate into a BLOCKED app via `open_app`, and post-action captures return unmasked content.

Codex correctly identified that the boundary exists only at turn-start and one approval-refresh path, not at every observation boundary. This is the most important finding across both reviews. My review understated it.

### Codex's High Finding #2 (ToolName Drift) -- AGREE

Both reviews independently identified this. My review flagged it as A.4.1 (Medium) with specific missing tools (AskUser, Shell) and the `isScreenChanging` default. Codex went further by noting that the metadata should live on `ToolSpec` itself, not in a parallel enum. This is architecturally correct -- the enum-drift problem is a design smell, not just a missing-entry bug.

### Codex's High Finding #3 (Shell Escape Hatch) -- PARTIALLY AGREE

My review flagged the blocklist bypass (B.9.1, Medium) but accepted the Android sandbox as sufficient mitigation. Codex frames it as a design principle violation: one tool bypasses the entire declarative tool model. This framing is correct. However, Codex's improvement plan proposes removing `shell` from default registration or replacing it with `read_file`/`list_dir`/`stat_path` -- this is too aggressive for the agent's current needs. The shell tool is actively used for file inspection in eval tasks and app-skill-driven workflows. The right fix is hardening, not removal.

### Codex's Medium Finding #5 (Cancellation Semantics) -- AGREE

I did not cover this. Codex correctly found that `SwipeExecutor` maps cancellation to `Failed` while `PointActionExecutorCore` and `ScrollExecutor` preserve `Cancelled`. This inconsistency undermines the router's clean lifecycle model. The router's own lack of per-call cancellation tokens for executing invocations is also a valid gap.

### Codex's Medium Finding #6 (ToolSpec Standardization) -- PARTIALLY AGREE

Codex notes that `ToolSpec` standardizes inputs but not capabilities, outputs, or observation policy. The `ToolMetadata` proposal in the improvement plan is directionally right but may over-engineer the solution. Currently only two booleans matter (`isScreenChanging`, `capturesScreen`). A full `ToolMetadata` data class with `category`, `loopSignatureKind`, `mayLaunchApp`, `mayAskUser` is speculative -- these fields would have one consumer each and risk YAGNI.

Simpler alternative: add `val isScreenChanging: Boolean` and `val capturesScreen: Boolean` directly to `ToolSpec`. This eliminates `ToolName` drift without introducing a new data class.

### Codex's Medium Finding #7 (Point-Action Retargeting) -- DISAGREE ON SEVERITY

Codex calls `refinePointActionTarget()` "too magical." My review (B.13) found it sophisticated but justified -- it handles real Android UI patterns where the LLM targets a text label inside a clickable row. The ambiguity guard prevents misrouting. Making retargeting opt-in would require every caller to understand Android view hierarchies, which defeats the purpose of the action layer.

The ask to "surface in the result when retargeting happened" is reasonable and low-cost. The ask to make it "explicit policy" is over-engineering.

### Codex's High Finding #4 (Scroll Target Fallback) -- AGREE

I missed this. `ScrollExecutor.resolveScrollArea()` silently falling back to full-screen bounds when an explicit target fails is genuinely wrong behavior. If the caller specified a target, resolution failure should be reported, not silently degraded.

---

## What Codex Missed

1. **MobileActionName vestigial hierarchy (A.6.1).** My review found that `MobileActionName` entries for Back/Home/Wait/SystemButton are only used in `PolicyEngine.isEscape()` for a branch that can never match (MobileActionTool only accepts click/long_press/scroll/swipe/type). Codex didn't examine this.

2. **Dead scroll boundary detection (B.10.1).** `UIActionInvocation.detectScrollBoundary()` checks for `UIAction.Swipe`, but UIActionInvocation is only used by SystemButton and Wait tools -- neither produces Swipe. This is dead code. Codex flagged `DataQueryInvocation` as dead but missed this.

3. **Duplicated `UiChangeDetector.detectScrollBoundary()`.** Both implementations exist; neither is called in production. This came from my review (A.10.1) and was not in Codex's.

4. **Duplicate constants in OpenAppTool (B.2.1, B.2.2).** `UI_SETTLE_DELAY_MS` and `SUGGESTION_LIMIT` are declared in both `OpenAppTool` and `OpenAppInvocation` companions; only the invocation ones are used.

5. **WriteTodosTool double-parse (B.5.1).** Minor but Codex's review doesn't mention tool-level implementation details at this granularity.

6. **`Scheduled` ephemeral state (A.3.1).** The state exists only as a UI notification with no query consumer. Low-severity but worth documenting.

---

## Improvement Plan Comparison

| Aspect | Claude's Plan | Codex's Plan |
|--------|--------------|--------------|
| Security | ToolName fix (P0) | Centralized ObservationCapture + destination-aware policy (Phase 0) |
| Metadata | Add missing ToolName entries | ToolMetadata on ToolSpec, eliminate ToolName for behavior (Phase 1) |
| Action Runtime | Executor reuse, dead code removal (P1-P2) | Unified action runtime contract (Phase 2) |
| Shell | Blocklist hardening (P3) | Remove or replace (Phase 3) |
| Scale | Tactical, ~55 min total | Architectural, multi-sprint |

Codex's plan is more ambitious but also more disruptive. The Phase 0 (centralized capture) and Phase 1 (metadata on spec) are the right architectural direction. Phases 2-4 are valuable but can be deferred -- they improve consistency without fixing correctness.

---

## Better Base

**CODEX** is the better base for the improvement plan's architectural direction.

Rationale:
- Codex identified the critical security gap (blocked-app boundary) that should drive priority
- The metadata-on-spec approach (Phase 1) is structurally better than patching ToolName entries
- The centralized observation capture (Phase 0) is the right fix, not just masking in the router

However, Codex's plan needs:
1. Claude's granular dead-code findings folded in (items 1-6 above)
2. Shell hardening instead of removal (Phase 3 needs revision)
3. ToolMetadata simplified to 2-3 booleans on ToolSpec, not a full data class (Phase 1 scoping)
4. Effort estimates and file-level specificity from Claude's plan
5. Retargeting left as-is with logging, not made opt-in (Phase 2 scoping)
