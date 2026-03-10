# Round 24 Eval Analysis — Common Problems

**Run ID**: `20260309_194958` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 4 evaluated | **Pass Rate**: 0/4 = 0%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| BrowserDraw | 0.0 | 29 | Color perception limitation — wrong colors drawn |
| MarkorDeleteNewestNote | 0.0 | 7 | Timestamps not visible for one note, still picked wrong |
| OsmAndMarker | 0.0 | 23 | Marker button not in a11y tree + infra exception |
| RetroPlaylistDuration | 0.0 | 24 | Song durations not accessible, Error at turn 24 |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| PerceptionGap | 2 | BrowserDraw, RetroPlaylistDuration |
| StructuralLimitation | 1 | MarkorDeleteNewestNote |
| A11yGap | 1 | OsmAndMarker |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Files app skill | Working | BrowserDraw reliably reaches Chrome now |
| Markor sort reverse | Ineffective | Agent still picks wrong note (3rd consecutive failure) |

## Common Problems

### P1: Color perception limitation — HIGH — 1 task

**Affected**: BrowserDraw

**Root Cause**: Agent reached Chrome, drew on canvas, submitted. But gpt-5.4 vision can't reliably match palette colors to target colors in screenshot. 3rd consecutive failure (R22: nav, R23: overlay, R24: color perception).

**Proposed Fix**:
- **RE-PARK**: 3 failures with different root causes each round. Color perception is a fundamental model vision limitation. No skill fix available.

---

### P2: MarkorDeleteNewestNote — structural mismatch — HIGH — 1 task

**Affected**: MarkorDeleteNewestNote

**Root Cause**: 3rd consecutive failure. Evaluator uses `ls -ll -au` change_time (access time) which may diverge from modification time shown in Markor UI. Shell access to Markor storage fails. No remaining fix ideas.

**Proposed Fix**:
- **RE-PARK**: Fundamental mismatch between UI-visible time and evaluator's access time. No fix without code changes.

---

### P3: OsmAnd marker button not in a11y tree — MED — 1 task

**Affected**: OsmAndMarker

**Root Cause**: Agent found Planken on map, but "Marker" button not present in a11y tree (only 0-14 elements, agent tried index 15). Also infra exception: "no such table: map_markers."

**Proposed Fix**:
- Investigate alternative: long-press on map, context menu, or different navigation path to marker creation.
- Owner layer: app skill: `net.osmand.plus`

---

### P4: RetroMusic duration inaccessible — MED — 1 task

**Affected**: RetroPlaylistDuration

**Root Cause**: Song durations not in a11y tree. Agent opened 2 songs via details but hit Error at turn 24. Turn-expensive individual lookup doesn't scale.

**Proposed Fix**:
- Add hybrid perception so agent can read durations from screenshot. Add skill guidance for batch strategy.
- Owner layer: app skill: `code.name.monkey.retromusic` + config override

## Next Steps

### Must Fix
1. **RetroMusic hybrid + duration strategy**: Enable hybrid perception_mode; skill to read durations visually
   - Targets: RetroPlaylistDuration
   - Owner layer: app skill + default.yaml override

2. **OsmAnd marker alternative path**: Investigate long-press / context menu
   - Targets: OsmAndMarker
   - Owner layer: app skill: `net.osmand.plus`

### Accept as Capability Gap
- **BrowserDraw**: Color perception limitation in hybrid mode (3 failures, re-parked)
- **MarkorDeleteNewestNote**: modification time vs access time mismatch (3 failures, re-parked)
