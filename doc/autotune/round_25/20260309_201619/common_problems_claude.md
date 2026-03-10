# Round 25 Eval Analysis — Common Problems

**Run ID**: `20260309_201619` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 4 evaluated | **Pass Rate**: 1/4 = 25%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| AudioRecorderRecordAudioWithFileName | 0.0 | 9 | UI rename ≠ filesystem rename (scoped storage) |
| ExpenseDeleteDuplicates2 | 0.0 | 14 | Insufficient scrolling — scanned ~30/42, missed duplicate |
| RetroPlaylistDuration | 0.0 | 19 | Durations not in a11y tree or screenshot, agent gave up |
| SportsTrackerTotalDistanceForCategoryOverInterval | 1.0 | 45 | Success — downward-only scroll guidance worked |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 1 | SportsTracker |
| StructuralLimitation | 1 | AudioRecorder |
| ScrollCoverage | 1 | ExpenseDeleteDuplicates2 |
| PerceptionGap | 1 | RetroPlaylistDuration |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| OpenTracks downward-only scroll | **Working** | SportsTracker PASSED (0.0→1.0) |
| RetroMusic hybrid perception | Ineffective | Agent still can't read durations |
| AudioRecorder skill (new) | Tested | Agent completed UI flow but filesystem unchanged |

## Common Problems

### P1: AudioRecorder scoped storage — HIGH — 1 task

**Affected**: AudioRecorderRecordAudioWithFileName

**Root Cause**: Agent correctly recorded, stopped, renamed via UI (9 turns). File appeared as "presentation_fGwr" in list. But evaluator checks filesystem at `/storage/emulated/0/Android/data/com.dimowner.audiorecorder/files/Music/records/`. UI rename only changes display name, not filesystem filename. Agent process can't access another app's private `Android/data/` directory.

**Proposed Fix**:
- **RE-PARK**: Structural limitation — no way to rename filesystem file without root or code changes.

---

### P2: ExpenseDeleteDuplicates2 scroll coverage — HIGH — 1 task

**Affected**: ExpenseDeleteDuplicates2

**Root Cause**: Agent scanned ~30 of 42 expenses, stopped too early. Task has 2 identical entries + 3 perturbations (same name, different amounts) among 40 noise rows. Agent found no duplicates because it didn't scroll far enough.

**Proposed Fix**:
- Strengthen Pro Expense skill: "Scroll the ENTIRE list (40+ items, at least 10 scrolls). Record EVERY expense name and EXACT amount."
- Owner layer: app skill: `com.arduia.expense`

---

### P3: RetroMusic durations inaccessible — HIGH — 1 task

**Affected**: RetroPlaylistDuration

**Root Cause**: Hybrid perception enabled but agent still couldn't read durations from screenshot. Individual song lookup via details dialog showed 00:49 — agent concluded songs are too short for 45-50 min target. Agent misunderstands that many short songs can combine.

**Proposed Fix**:
- Skill: "Song durations are NOT in a11y tree. Use visual perception or open details. Short songs can still sum to target duration."
- Owner layer: app skill: `code.name.monkey.retromusic`

## Next Steps

### Must Fix
1. **Pro Expense full-list scroll**: At least 10 scrolls, record every item
   - Targets: ExpenseDeleteDuplicates2
   - Owner layer: app skill: `com.arduia.expense`

2. **RetroMusic duration strategy update**: Clarify short songs are valid
   - Targets: RetroPlaylistDuration
   - Owner layer: app skill: `code.name.monkey.retromusic`

### Accept as Capability Gap
- **AudioRecorderRecordAudioWithFileName**: UI rename ≠ filesystem rename (re-parked)
- **BrowserDraw**: Color perception limitation (re-parked R24)
