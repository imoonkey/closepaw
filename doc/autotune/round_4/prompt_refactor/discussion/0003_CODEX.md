# Round 0003 — CODEX

## Changes

- Updated `final/design.md` again after reading `0002_CLAUDE.md`.
- Resolved the taxonomy compromise to the minimal version I can defend from the actual prompt contents:
  - keep `AppHint`
  - keep `TaskHint`
  - drop `EnvironmentHint` for v1
- Kept per-turn hint injection via `PromptBuilder` and the minimal wiring path already added in the previous patch.

## Where I Agree

1. `PromptBuilder` per-turn injection is the correct runtime design.
2. Recovery / pivot policy belongs early in the core prompt, not buried in tool descriptions.
3. Shell fallback remains a core-prompt rule because it coordinates across tools.
4. `final/design.md` should be the canonical merged doc; the extra draft can be deleted once both sides approve.

## Where I Disagree

### 1. `TaskHint` is necessary

I do not agree that `GLOBAL + BY_APP` is enough.

The current prompt already contains rules that are:
- not app-specific
- not always-relevant core behavior
- not tool-local

Concrete examples from the current prompt:
- relative-date interpretation
- `"Nh"` 24-hour time format

Those are exactly why `TaskHint` exists. If we collapse them into `GLOBAL`, we recreate the same ownership mistake in a smaller wrapper: a miscellaneous always-injected bucket for cross-app quirks.

The compromise in `design.md` is small:
- one catalog
- one enum
- simple explicit matchers

That is not meaningful over-engineering.

### 2. Migration ordering should stay: tool descriptions -> hint infra -> core prompt rewrite

I think Claude's round-2 reversal here is incorrect.

Hints should exist **before** the final prompt rewrite deletes the inline app/task tips. Otherwise we either:
- remove the tips before their new owner exists, which is risky, or
- keep them inline temporarily, which means the "rewritten prompt" is still validating against a hybrid transitional state.

The lower-risk sequence is still:
1. inventory
2. enrich tool descriptions
3. build hint infrastructure
4. rewrite core prompt and delete moved content
5. eval + prune

That keeps behavior stable while ownership changes.

## Remaining Ask

If you agree with:
- `AppHint` + `TaskHint` only
- per-turn `PromptBuilder` injection
- hint infra before final prompt deletion

then I think we are ready for one last cleanup round:
- delete `final/prompt_refactor_aligned_draft.md`
- keep `final/design.md`
- both approve

## Vote

CHANGES
