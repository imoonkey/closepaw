# Click Execution Alignment Review

**Run**: `20260306_003753`
**Date**: 2026-03-06
**Reviewer**: Claude (cross-review)
**Sources**:
- `browser_click_execution_codex.md` — Codex, Browser-only scope (2 tasks)
- `click_execution_scan_codex.md` — Codex, full 10-task sweep
- `click_execution_scan_claude.md` — Claude, full 10-task sweep (independent)

---

## 1. Where All Three Agree

These conclusions appear in all three documents with high confidence:

| # | Consensus Point |
|---|----------------|
| 1 | **Files row `task.html` click is the clearest false-success.** All three identify BrowserDraw turn 2 and BrowserMultiply turn 2 as the strongest evidence: `node_action_click` reports Success, screen unchanged, `long_press` on the same target works. |
| 2 | **This is NOT a broad click regression.** No document claims the click executor is broken system-wide. All explicitly state the evidence is localized. |
| 3 | **`double_click` failure is not a regression signal.** It's an unsupported action emitted by the model. (Browser-Codex and Claude both note this; Scan-Codex implicitly excludes it from findings.) |
| 4 | **Chooser/Chrome transitions have observation-layer weakness.** Post-action a11y trees collapse to sparse status-bar nodes during app transitions. All three classify these as observation issues, not execution failures. |
| 5 | **Chrome first-run "Use without an account" is unreliable** on first attempt, but recoverable on retry. |
| 6 | **AudioRecorder and ClockTimerEntry have zero false-successes.** Clean execution in both. |
| 7 | **BrowserDraw overlay contamination is a confound.** All three flag the debug/agent overlay as an interfering factor in BrowserDraw. |
| 8 | **Recommended next step: isolated Files row repro** comparing adb tap, node_action_click, gesture_tap, and long_press. Both Codex documents and Claude converge on the same reproduction protocol. |

---

## 2. Conflicts and Confidence Divergences

### 2.1 Scale of false-successes: 20 vs. 25 vs. "a few"

| Document | High-confidence false-successes counted |
|----------|-----------------------------------------|
| Browser-Codex | ~2-4 (Files row only, narrow scope) |
| Scan-Codex | ~5-6 strong, ~14 weak/mixed (raw 20 flagged) |
| Scan-Claude | **25** across 7 tasks, categorized into 8 root-cause buckets |

**Divergence**: Claude's independent scan counts significantly more false-successes because it classifies `a11y_scroll on non-scrollable views` (5 instances in Expense tasks) and `gesture_tap on overlay-covered areas` (6 instances) as confirmed false-successes. Codex's scan treats many of these as "likely false positives from the raw heuristic" or "weak evidence."

**Assessment**: Claude's broader count is defensible — if the tool says "Success: Scrolled" but nothing scrolled, that is by definition a false-success regardless of root cause. But Codex is right that not all false-successes have the same severity or root cause. The distinction matters for prioritizing fixes.

### 2.2 Observation lag: real factor or non-factor?

| Document | Position |
|----------|----------|
| Browser-Codex | Observation collapse is a real confound; BrowserMultiply turn 12 is likely a working click with poor post-action observation |
| Scan-Codex | Same: several flagged turns are "observation-lag suspects" |
| Scan-Claude | "0 confirmed" observation lag cases; all false-successes confirmed by both screenshot AND a11y tree |

**Divergence**: Both Codex documents treat observation lag as a meaningful alternative explanation for some suspicious turns. Claude's scan explicitly checked a11y trees alongside screenshots and found no pure observation-lag cases.

**Assessment**: This is partly a definitional gap. BrowserMultiply turn 12 is the key test case — Codex says the click probably worked and observation missed it; Claude classified it as "Genuine" (screen changed by next screenshot). They actually agree on the outcome (the click worked) but frame it differently. The real question is whether we need instrumentation to disambiguate in real-time. Answer: yes, regardless of which framing is correct.

### 2.3 OsmAnd "INCREASE SEARCH RADIUS" — execution bug or app behavior?

| Document | Position |
|----------|----------|
| Browser-Codex | Out of scope (Browser only) |
| Scan-Codex | Low-confidence; "OsmAnd is already a noisy map/search surface" |
| Scan-Claude | **Medium severity**, 3 instances across 2 tasks; classified as confirmed false-success |

**Divergence**: Codex downweights OsmAnd signals as noise. Claude treats them as a real, repeated false-success pattern.

**Assessment**: The button click fires (spinner flashes), results don't change, and the action reports success. This is a genuine false-success from the agent's perspective — it can't distinguish "I clicked and something happened" from "nothing changed." Whether the root cause is the app's behavior or the action layer, the agent needs to know the click had no semantic effect.

### 2.4 a11y_scroll false-success — in scope or separate issue?

| Document | Position |
|----------|----------|
| Browser-Codex | Not covered (Browser scope) |
| Scan-Codex | Mentioned under Markor/Expense as "not strong execution bugs" |
| Scan-Claude | 5 instances, **Medium severity**, distinct root cause (action layer doesn't validate scroll outcome) |

**Divergence**: Codex Scan doesn't prominently flag scroll false-successes. Claude treats them as the third-largest category.

**Assessment**: Scroll false-success is a real, separate issue from click false-success. It shares the same underlying gap (action layer reports success without verifying effect) but has a different fix path. It should be tracked as a related but distinct item.

### 2.5 Severity of debug overlay as root cause

| Document | Position |
|----------|----------|
| Browser-Codex | Mentions overlay as a confound; not the primary diagnosis |
| Scan-Codex | Lists overlay as one of several alternative explanations |
| Scan-Claude | **Critical severity, 9 instances**, largest single source of false-successes |

**Divergence**: Claude elevates debug overlay interception to the top severity issue. Codex treats it as environmental contamination to be factored out of the analysis.

**Assessment**: Both are right in their framing. For "is the click executor broken?" — Codex is correct to factor out overlay contamination. For "why do tasks fail?" — Claude is correct that the overlay is the single largest contributor to wasted turns and blocking failures. The question is what we're optimizing for: understanding the executor vs. improving task success rate.

---

## 3. Core Problem Definition

The three documents point at overlapping but different framings:

| Framing | Supported by |
|---------|-------------|
| **A) Localized Files bug** — node_action_click on Files row doesn't open files | All three (strongest consensus) |
| **B) Broad action-success verification gap** — the action layer reports Success without verifying semantic effect | Claude (primary framing), Codex (acknowledged but downweighted) |
| **C) Broad click regression** | None (all three reject this) |

**My alignment recommendation**: The core problem is **(B) with (A) as the sharpest symptom.**

Rationale:
- (A) alone is too narrow. It would lead to an app-specific workaround (use long_press for Files) that doesn't fix the underlying verification gap.
- (B) is the root cause that manifests as (A) for clicks, as scroll false-success for Gallery/Markor, and as wasted turns on OsmAnd's no-op buttons. All three share the same structural deficiency: the action layer has no feedback loop.
- (C) is correctly rejected by all three analyses. The executor dispatches actions fine; it just doesn't know when they had no effect.

The aligned problem statement:

> **The action execution layer reports success based on dispatch acknowledgment, not semantic outcome. This causes false-success across multiple action types (click, scroll, gesture_tap) and multiple apps, wasting turns and sometimes blocking task completion entirely.**

---

## 4. Action Plan (Priority Order)

### P0 — Instrumentation (unblocks everything else)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 1 | **Post-action screen-diff gate**: After every action that reports Success, compare before/after a11y tree root hash (or top-N node fingerprints). If unchanged, annotate the result as `Success (no screen change detected)` instead of bare `Success`. Do NOT change the success/failure contract yet — just surface the signal. | M | Catches all 25 false-successes identified by Claude; gives the LLM the information to self-correct. |
| 2 | **a11y_scroll validation**: After scroll dispatch, check whether visible node positions shifted. If not, return `Failure: scroll had no effect` (this one CAN be a hard failure since a no-op scroll is never useful). | S | Eliminates 5 false-success instances; prevents agent from looping on unscrollable views. |

### P1 — Executor Fix (Files row click)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 3 | **Reproduce and root-cause Files row click**: Run the isolated repro protocol (adb tap vs. node_action_click vs. gesture_tap vs. long_press on Files row). Determine whether node_action_click is fundamentally broken for this widget type or if the overlay intercepts it. | S | Directly answers the highest-confidence finding from all three analyses. |
| 4 | **Fix or escalate**: If node_action_click is broken for CardView/LinearLayout file rows, either fix the dispatch path or add a click-method fallback (try gesture_tap at node center when node_action_click produces no screen change). | M | Unblocks the two Browser tasks at the execution layer. |

### P2 — App-Specific Workarounds (short-term task success)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 5 | **Files open strategy**: Until P1 is resolved, add app-skill guidance: prefer `long_press → Open with → [app]` for opening files in Files app. BrowserMultiply already discovered this recovery path. | S | Immediate improvement for Browser tasks. |
| 6 | **Debug overlay non-intercepting mode**: Set the debug overlay to `FLAG_NOT_TOUCHABLE` during task execution, or exclude its bounds from gesture_tap coordinate targeting. | S | Eliminates the 9 overlay-interception false-successes in BrowserDraw and OsmAnd. |
| 7 | **OsmAnd "INCREASE SEARCH RADIUS" awareness**: This button often produces no visible result change. Not an agent bug, but the agent should recognize unchanged results and try a different strategy instead of clicking it repeatedly. Addressed generically by P0.1. | — | No code change needed if P0.1 lands. |

### P3 — Deferred / Monitor

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 8 | **Chrome first-run button**: Low frequency (2 instances), recoverable on retry, and only affects first-time emulator setup. Monitor after P0.1 lands; if the screen-diff annotation helps the agent retry faster, no dedicated fix needed. | — | — |
| 9 | **Chooser/transition observation collapse**: The a11y tree going sparse during app transitions is a known platform behavior. Consider adding a short post-action delay + re-read for transitions that trigger new activities. Lower priority since the clicks themselves work. | M | Reduces agent confusion during app switches. |

---

## 5. Summary

All three analyses agree: this is **not a broad click regression**. The strongest shared finding is **Files row click false-success**. The key divergence is scope: Codex frames the problem narrowly (Files bug + observation weakness), while Claude frames it broadly (action-success verification gap across click, scroll, and gesture_tap).

The aligned view: the **verification gap is the real systemic issue** (P0), the **Files row click is the sharpest symptom to debug** (P1), and **overlay/app-specific workarounds** provide immediate relief (P2). Fixing P0 (post-action screen diff) would have caught all 25 false-successes identified across the three analyses and given the LLM enough information to self-correct in most cases.
