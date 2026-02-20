# Claude — Initial Position

## Summary

I agree with Codex on the core goal (gesture-first for all dual-path executors, unified TargetResolver). I disagree on scope: Codex proposes 4 new abstractions and refactoring all 5 executors; I think only ScrollExecutor needs changes.

## Position on Open Questions

### Q1: Minimal vs. structural refactor → Minimal

Codex identifies real duplication (post-capture, bounds check, attempt trail formatting duplicated across Click/LongPress). But the executors are each 100–140 lines, self-contained, and easy to understand. The duplication is ~20 lines per file.

Introducing `ActionPriorityPolicy`, `PostActionCapture`, `AttemptTrailBuilder`, `ResolvedTarget` adds 4 new files and an indirection layer. When someone wants to understand "what happens when I click?", they now need to trace through the policy table → executor → shared capture helper instead of reading one 140-line file top-to-bottom.

Per `sop/system_work.md` KISS principle: the minimum change to achieve the goal is ScrollExecutor alignment. Click/LongPress are already correctly gesture-first.

**Concession**: I'd accept extracting `buildSuccessOutcome` (delay + capture + observation) into a shared top-level function in a common file, since that pattern IS identical across all executors. But not a class with its own state or configuration.

### Q2: Scroll cascade → gesture-first

Codex's Qi Note "scroll只做a11y scroll" contradicts the master's Phase 2 instruction which says ALL dual-path executors should be gesture-first after dispatchGesture fix. The verification eval (20260220_145635) showed gesture swipes working for scroll on the brightness Settings page.

I recommend: gesture-first for scroll, matching click/long_press. The a11y fallback is the safety net.

### Q3: TargetResolver output → add bounds field

Codex's `ResolvedTarget` adds `isSemantic` and `source` fields. But:
- `isSemantic` is already a one-line extension `Target.isSemantic()` in ClickExecutor/LongPressExecutor. It's a property of the *input* Target, not the *resolved* result.
- `source` (ELEMENT_INDEX / TEXT_MATCH / RAW_COORDINATE) duplicates information already in the `Target` sealed class itself.

Adding `bounds: Bounds? = null` to existing `ResolveResult.Resolved` is sufficient. No new types needed.

## Vote: CHANGES
