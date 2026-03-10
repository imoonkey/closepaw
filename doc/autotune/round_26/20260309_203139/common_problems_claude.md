# Round 26 Eval Analysis — Common Problems

**Run ID**: `20260309_203139` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 4 evaluated | **Pass Rate**: 0/4 = 0%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| ExpenseDeleteDuplicates2 | 0.0 | 16 | Found 41/42 items — still missing duplicate's partner |
| OsmAndMarker | 0.0 | 17 | Infra failure (attempt 0), false success (attempt 1) |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 59 | False completion — near turn budget limit |
| RetroPlaylistDuration | 0.0 | 8 | Agent gave up — can't read durations from a11y or screenshot |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| ScrollGap | 1 | ExpenseDeleteDuplicates2 |
| InfraFailure + FalseSuccess | 1 | OsmAndMarker |
| TurnExhaustion | 1 | RecipeDeleteDuplicateRecipes3 |
| PerceptionGap | 1 | RetroPlaylistDuration |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Pro Expense scroll emphasis | Partial | 41/42 items found (up from 30), but still 1 missing |
| RetroMusic hybrid + duration skill | Ineffective | Agent gave up at turn 8 — can't read durations at all |

## Common Problems

### P1: RetroPlaylistDuration — all perception modes exhausted — HIGH — 1 task

**Affected**: RetroPlaylistDuration

**Root Cause**: 3rd consecutive failure (R24, R25, R26). Agent gave up at turn 8/50 — couldn't read durations from a11y tree OR screenshot. Hybrid perception didn't help.

**Proposed Fix**:
- **RE-PARK**: Song durations not accessible via any available perception mode. No remaining fix ideas.

---

### P2: ExpenseDeleteDuplicates2 scroll gap — MED — 1 task

**Affected**: ExpenseDeleteDuplicates2

**Root Cause**: Agent found 41 of 42 items (improved from 30 in R25). Still missing 1 item during scroll transitions — likely the duplicate's partner. Scroll mechanism can't guarantee 100% coverage.

**Proposed Fix**:
- Try different approach: use "All" expenses view, or sort by name if available.
- Owner layer: app skill: `com.arduia.expense`

---

### P3: OsmAnd infra instability — MED — 1 task

**Affected**: OsmAndMarker

**Root Cause**: Infra failure on first attempt (score=None, exception "no such table: map_markers"). Retry scored 0.0 — agent claimed success but evaluator disagreed.

**Proposed Fix**:
- Investigate OsmAnd map_markers table issue. May need different OsmAnd version or DB initialization.
- Owner layer: infra

## Next Steps

### Must Fix
1. **Pro Expense name-sort strategy**: Try Filter → sort by name instead of scrolling
   - Targets: ExpenseDeleteDuplicates2
   - Owner layer: app skill: `com.arduia.expense`

### Accept as Capability Gap
- **RetroPlaylistDuration**: Song durations inaccessible via any perception mode (re-parked)
