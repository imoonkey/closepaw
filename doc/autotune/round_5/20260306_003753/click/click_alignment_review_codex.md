# Click Execution Alignment Review

**Run**: `20260306_003753`  
**Date**: 2026-03-06  
**Reviewer**: Codex (cross-review)  
**Sources**:
- `browser_click_execution_codex.md`
- `click_execution_scan_codex.md`
- `click_execution_scan_claude.md`

## Core Read

The three documents are directionally aligned, but they are optimizing for slightly different questions:

- `browser_click_execution_codex.md` asks: "is there a real click regression here?"
- `click_execution_scan_codex.md` asks: "after sweeping 10 tasks, which suspicious clicks are strong enough to treat as execution bugs?"
- `click_execution_scan_claude.md` asks: "which actions reported success without observable effect, regardless of whether the cause is executor, overlay, app behavior, or unsupported semantics?"

That difference in framing explains most of the disagreement.

## Where The Analyses Clearly Agree

1. This is **not** a broad system-wide click regression.
2. The strongest repeated signal is the Files `task.html` row in `BrowserDraw` and `BrowserMultiply`.
3. BrowserDraw is partially contaminated by overlay/dev-environment issues and should not be over-weighted as pure executor evidence.
4. Some action results currently overclaim success relative to what the user-visible state actually did.
5. A clean isolated repro on Files is the right next debug step.

## Where The Analyses Differ

### 1. Claude counts a broader class of "false-success"

Claude includes:

- `a11y_scroll` that reports success on non-scrollable views
- `gesture_tap` into overlay-covered content areas
- app-specific no-op buttons like OsmAnd `INCREASE SEARCH RADIUS`

I agree these are real **action-success verification** problems. I do not think they all support the narrower claim of a generic **click transport regression**.

### 2. My earlier scan was too conservative on the generic executor gap

The narrower Codex scan correctly avoided overcalling app-specific or contaminated turns as "click regression," but it under-emphasized a more important commonality:

- the executor treats dispatch acknowledgment as success
- it does not strongly verify that the action produced a meaningful state change

That gap is broader than click, and Claude's categorization helps expose it.

### 3. Claude is too strong on "0 confirmed observation lag"

I would not state that as confidently.

There are still traces, especially around chooser / Chrome transitions, where the safest reading is:

- the click probably worked
- the immediate post-action observation was sparse or delayed

That does not change the action plan much, but it matters when deciding whether to hard-fail every unchanged post-action screen.

## Aligned Problem Definition

The right top-level framing is:

> The main issue is not a broad click regression. It is a broader **action-success verification gap** in the executor, with one high-confidence localized click bug on Files row opening.

That framing preserves the strongest consensus from all three docs:

- **Files row click false-success** is the clearest reproducible click bug.
- **Scroll false-success** and some overlay/app-specific no-ops are also real, but they belong under the same verification umbrella rather than under one generic "click is broken" claim.

## Priority Judgement

### P0: Generic verification and instrumentation

This should come first. It addresses the common root problem without prematurely encoding per-app heuristics.

- annotate action results with before/after change detection
- validate scroll by actual movement
- surface "success with no observable effect" distinctly from true success

### P1: Files-specific reproduction and fix

This is the highest-confidence executor bug and the cleanest place to debug the actual click path.

- compare `adb tap`, `node_action_click`, `gesture_tap`, `long_press`
- compare row center, icon, and filename subtargets
- only after repro decide whether to fix executor behavior or add a bounded fallback

### P2: Environment and overlay hygiene

This is operationally important, but it should not be confused with the executor bug itself.

- make the overlay non-intercepting during execution
- keep BrowserDraw-style contaminated runs out of root-cause accounting

### P3: App-specific workarounds

Only after P0/P1.

- Files app: prefer `long_press -> Open with` if single-click remains unreliable
- OsmAnd and Chrome first-run should mostly improve automatically once unchanged-success is surfaced back to the model

## Bottom Line

If we have to align on one sentence, it should be:

> Treat this as an **action-success verification problem with a sharp Files click symptom**, not as a broad click regression.

That gives us the right implementation order:

1. fix observability and success semantics
2. reproduce and fix Files row click
3. clean up overlay interference
4. add only minimal app-specific workarounds where still needed
