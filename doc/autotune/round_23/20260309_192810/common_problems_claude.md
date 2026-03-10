# Round 23 Eval Analysis — Common Problems

**Run ID**: `20260309_192810` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 4 evaluated | **Pass Rate**: 0/4 = 0%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| BrowserDraw | 0.0 | 28 | Agent UI overlay occludes canvas + target colors |
| MarkorDeleteNewestNote | 0.0 | 7 | Ascending sort confusion — deleted oldest instead of newest |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 53 | False completion — wrong recipe deleted or true duplicate missed |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | 46 | Scroll-up failed, answered 0 kayaking tracks |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| InfraOcclusion | 1 | BrowserDraw |
| PerceptionGap | 1 | MarkorDeleteNewestNote |
| FalseCompletion | 1 | RecipeDeleteDuplicateRecipes3 |
| ScrollFailure | 1 | SportsTracker |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Files app skill (hamburger zone) | Working | BrowserDraw now reaches Chrome (nav fixed) |
| Markor sort guidance | Ineffective | Agent still deletes wrong note — ascending vs descending |
| OpenTracks scroll-to-top | Ineffective | Scroll-up produces "no observable effect" |

## Common Problems

### P1: Agent UI overlay occlusion — HIGH — 1 task

**Affected**: BrowserDraw

**Root Cause**: Files nav FIXED — agent reached Chrome and opened task.html. But Android Agent UI overlay covered the canvas and target colors at bottom of screen. Agent couldn't see what colors to draw.

**Proposed Fix**:
- Infra/config issue. May need auto-minimize agent overlay during hybrid perception tasks.
- Owner layer: infra

---

### P2: Markor ascending sort misunderstanding — HIGH — 1 task

**Affected**: MarkorDeleteNewestNote

**Root Cause**: Markor Date sort is ASCENDING (oldest first). Agent deleted the first item (15:34) thinking it was newest, but first = oldest. Should have deleted LAST item or enabled "Reverse order."

**Proposed Fix**:
- Skill must explicitly state: "Date sort is ASCENDING by default. Enable 'Reverse order' to get newest first, OR pick the LAST item."
- Owner layer: app skill: `net.gsantner.markor`

---

### P3: Scroll-up infrastructure limitation — MED — 1 task

**Affected**: SportsTrackerTotalDistanceForCategoryOverInterval

**Root Cause**: Upward scroll produces "no observable effect." OpenTracks may not support upward scrolling via a11y actions. Need downward-only strategy.

**Proposed Fix**:
- Remove scroll-to-top instruction. Use downward-only scroll with 5-6 full scrolls from initial position.
- Owner layer: app skill: `de.dennisguse.opentracks`

## Next Steps

### Must Fix
1. **Markor Reverse Order toggle**: Tell agent to enable reverse order after date sort
   - Targets: MarkorDeleteNewestNote
   - Owner layer: app skill: `net.gsantner.markor`

2. **OpenTracks downward-only scroll**: Remove scroll-up, use 5-6 downward scrolls
   - Targets: SportsTracker
   - Owner layer: app skill: `de.dennisguse.opentracks`

### Should Fix
3. **Agent overlay minimization**: Auto-minimize during hybrid tasks
   - Targets: BrowserDraw
   - Owner layer: infra
