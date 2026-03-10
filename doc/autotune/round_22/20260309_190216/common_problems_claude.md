# Round 22 Eval Analysis — Common Problems

**Run ID**: `20260309_190216` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 4 evaluated | **Pass Rate**: 1/4 = 25%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| BrowserDraw | 0.0 | 30 | Files app hamburger zone traps clicks |
| BrowserMaze | 1.0 | 27 | Success — Chrome skill + hybrid perception |
| MarkorDeleteNewestNote | 0.0 | 7 | Wrong note deleted — modification time ≠ creation time |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | 48 | Unidirectional scroll — missed kayaking tracks above fold |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 1 | BrowserMaze |
| NavigationTrap | 1 | BrowserDraw |
| PerceptionGap | 1 | MarkorDeleteNewestNote |
| ScrollCoverage | 1 | SportsTracker |

## Common Problems

### P1: Files app hamburger zone — HIGH — 1 task

**Affected**: BrowserDraw

**Root Cause**: Agent spent all 30 turns in Files app clicking the hamburger/nav button area (x<150 danger zone) instead of the task.html row. Never reached Chrome.

**Proposed Fix**:
- Add Files app skill warning: "NEVER click near x<150 on left edge — that's the hamburger menu zone. Use element_index targeting for file rows."
- Owner layer: app skill: `com.google.android.documentsui`

---

### P2: Markor modification time mismatch — HIGH — 1 task

**Affected**: MarkorDeleteNewestNote

**Root Cause**: Markor Date sort uses modification time. Some pre-existing notes were edited after creation, so modification time ≠ creation time. Agent deleted wrong note.

**Proposed Fix**:
- Update Markor skill: clarify ascending vs descending sort order, add "Reverse order" toggle guidance
- Owner layer: app skill: `net.gsantner.markor`

---

### P3: Unidirectional scrolling in OpenTracks — MED — 1 task

**Affected**: SportsTrackerTotalDistanceForCategoryOverInterval

**Root Cause**: Agent scrolled DOWN but never UP. Missed kayaking tracks above initial scroll position. Found 2 tracks (6518m) but correct answer requires more.

**Proposed Fix**:
- Update OpenTracks skill: "Scroll to TOP first, then scroll down through entire list" or simplify to downward-only with more scrolls
- Owner layer: app skill: `de.dennisguse.opentracks`

## Next Steps

### Must Fix
1. **Files app left-edge skill**: Add x<150 danger zone warning
   - Targets: BrowserDraw
   - Owner layer: app skill: `com.google.android.documentsui`

2. **Markor sort order clarification**: Add ascending/descending + Reverse Order guidance
   - Targets: MarkorDeleteNewestNote
   - Owner layer: app skill: `net.gsantner.markor`

3. **OpenTracks scroll strategy**: Simplify to downward-only with 5-6 scrolls
   - Targets: SportsTracker
   - Owner layer: app skill: `de.dennisguse.opentracks`

### Accept as Capability Gap
- None yet (all tasks have remaining fix ideas)
