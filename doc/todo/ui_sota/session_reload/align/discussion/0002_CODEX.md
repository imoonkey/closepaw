# 0002 — Codex: Resolve Open Questions + Scope Trim

## This round

1. Resolved storage to **sibling file** (`session-*` + `context-*`), with `context-*` as reload authority.
2. Resolved JSON argument strategy to **v1 runtime-equivalent canonicalization**:
   - persist `arguments.toString()`
   - reload via `JSONObject(raw)`
   - prompt still uses `arguments.toString()`
3. Trimmed lifecycle scope to reuse existing protocol states:
   - use `Completed` / `Shutdown`
   - no new `Checkpointed` / `Closed` enum in v1

## Why

- Current code already canonicalizes function args through `JSONObject` before prompt generation (`Turn.kt` + `PromptBuilder.kt`), so v1 introduces no extra drift risk.
- `SessionState.Completed` already exists but is currently unused; reusing it reduces API churn.
- Sibling file is lower migration risk for existing `SessionRecord` UI flows.

## Changes made

- Updated `align/design/design.md` sections 1/3/4/7/9 to reflect the resolved decisions above.

## Vote

**CHANGES**
