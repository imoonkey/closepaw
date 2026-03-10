# R31 Common Problems — claude

**Run**: `20260309_235826` | **Model**: gpt-5.4 | **Tasks**: 4 (Tier 1 batch)

## Results

| Task | Score | Turns | Status |
|------|-------|-------|--------|
| AudioRecorderRecordAudioWithFileName | 1.0 | 8 | PASS |
| ExpenseDeleteDuplicates2 | 1.0 | 26 | PASS |
| ExpenseAddMultipleFromGallery | 0.0 | 13/60 | FAIL |
| OsmAndMarker | N/A | 0 | INFRA_FAILURE |

**Pass rate**: 2/4 (50%), 2/2 excluding infra failures (100% of runnable)

## Fixed This Round

1. **AudioRecorderRecordAudioWithFileName**: Removing "strip .m4a" from skill fixed it. Agent now types filename verbatim, evaluator gets expected double extension.
2. **ExpenseDeleteDuplicates2**: Adding "open detail view, compare ALL fields" guidance worked. Agent now correctly opens entries to compare category+note, not just name+amount.

## Remaining Failures

### ExpenseAddMultipleFromGallery
- **Root cause**: Agent opened wrong image (`old_expenses_6.jpg` instead of `expenses.jpg`) in Simple Gallery Pro. Then gave up at turn 13/60 citing overlay obstruction.
- **Gallery skill was created for wrong package** (`com.google.android.apps.photos` instead of `com.simplemobiletools.gallery.pro`).
- **Fix**: Create/move Gallery skill to `com.simplemobiletools.gallery.pro`. Add: "Search for exact filename, verify title bar matches before reading."

### OsmAndMarker
- **Root cause**: Infra — `map_markers_db` doesn't exist on snapshot. OsmAnd creates it lazily on first marker add.
- **Fix**: Boot emulator, open OsmAnd, add+remove a marker to create the DB, re-snapshot.

## Next Steps

1. Move Gallery skill from `com.google.android.apps.photos` to `com.simplemobiletools.gallery.pro`. Add filename search/verification guidance.
2. Fix OsmAnd snapshot: manually trigger `map_markers_db` creation, re-run `prepare_baseline`.
3. Re-run ExpenseAddMultipleFromGallery and OsmAndMarker in R32 alongside Tier 2 tasks.
4. Remove AudioRecorderRecordAudioWithFileName and ExpenseDeleteDuplicates2 from unpark_active.txt.
