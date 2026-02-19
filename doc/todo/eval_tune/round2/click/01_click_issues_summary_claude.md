# Click Execution Issues Summary

Source: `eval/results/20260218_203057` (aw_subset_core, default.yaml, qwen3.5)

## Quantitative Impact

- 15.7% overall tool failure rate across 14 tasks
- 3 of 6 failed tasks have click execution as primary blocker
- 22 tool failures in click-related operations out of 140 total tool calls

## Issue Catalog

### I1: Occluded Element False Rejection

**Task**: SimpleSmsSend (9 failures), ExpenseAddSingle (1 failure)
**Error**: `"Element X is likely occluded by overlapping clickable elements."`

TargetResolver generates 6 candidate tap points (center, upper-mid, upper-third, upper-left, upper-right, near-top). `isPointBlockedBySmallerClickable()` checks if a smaller clickable element contains each candidate. When a child element overlaps the right portion of a parent, ALL 6 candidates land in the overlapping zone because the algorithm uses center/quartile positions only.

**Concrete example (SimpleSmsSend):**
```
[3] ImageView  bounds=[954,296,1080,422]  area=15876  clickable  <- TARGET
[4] LinearLayout  bounds=[996,296,1080,422]  area=10584  clickable  <- BLOCKER (smaller)
```
Free zone: x=[954,995] (42px / 33% of width). But all 6 candidates have x >= 1000.

**Root**: Candidate point generation uses center/quartile math that doesn't search non-occluded edges.

---

### I2: Click Dispatched But "No UI Change"

**Tasks**: SimpleSmsSend (T8,T13,T16-T18), FilesMoveFile (T12-T14,T16), BrowserMultiply (T6)

After dispatching `ACTION_CLICK` or gesture tap, `UiChangeDetector` sees identical a11y tree hash. The action is considered failed. The ClickExecutor then retries with jitter (4 offsets at +/-12px), re-resolution, etc. All attempts exhausted.

**Possible causes:**
1. The action succeeded but didn't change the a11y tree (e.g., focus change not reflected)
2. The gesture coordinates are valid but the target view doesn't respond to a11y click
3. Elements near screen edges (y > 2170) don't receive touch events reliably

---

### I3: Buttons at Screen Edge

**Task**: FilesMoveFile (T14, T16)
**Error**: `"Click at (742,2191) failed after all attempts"` and `"Click at (943,2191) failed after all attempts"`

```
[8] Button text='Just once'  bounds=[631,2174,853,2209]  center=[742,2191]
[9] Button text='Always'     bounds=[853,2174,1033,2209]  center=[943,2191]
```

Screen height = 2209. Buttons extend to y=2209 (very edge). Both `ACTION_CLICK` and gesture tap fail at these coordinates. All 4 jitter attempts also fail.

**Root**: Access gestures at the extreme bottom of the screen are unreliable on Android. The navigation bar area (y > ~2150) may intercept touch events.

---

### I4: Long-Press Failure on Non-Long-Clickable Elements

**Task**: FilesMoveFile (T8, T18)
**Error**: `"ACTION_LONG_CLICK: No long-clickable node at (296,852); gesture_long_press: dispatched, no UI change"`

```
[11] CardView text='holiday_photos.jpg'  click=True  long_click=False
```

Documents UI supports long-press for file selection, but a11y tree reports `long_clickable=false`. `NodeActionPerformer` checks for long-clickable node and finds none. Gesture long-press fallback dispatches but `UiChangeDetector` sees no change.

**Root**: Two issues:
1. A11y tree doesn't expose long-click capability for this view
2. Gesture long-press (moveTo + hold path) may be too short or not trigger the view's long-click listener

---

### I5: Invalid Action Type Confusion

**Task**: FilesMoveFile (T11)
**Error**: `"Validation failed: Unknown action: 'system_button'. Valid: click, long_press, type, swipe"`

Agent sent `{"action": "system_button", "button": "back"}` as a `mobile_action` tool call. Should have used the separate `system_button` tool.

**Root**: LLM reasoning confusion. The `mobile_action` tool schema doesn't clearly separate from the `system_button` tool schema in the prompt.

---

### I6: Jitter Strategy Insufficient

When the base click and gesture tap both fail, ClickExecutor tries 4 jitter offsets at +/-12px. This fixed offset doesn't account for:
- The element being near screen edge (jitter may go out of bounds)
- The free zone being to one side only (all jitter tries the wrong side)
- 12px being too small/large for the specific UI

**Example**: SimpleSmsSend element 4 at center [1038,359]. Jitter tries [1026,359], [1050,359], [1038,347], [1038,371]. All still in the same non-responsive zone.

---

## Failure Mode Distribution

| Failure Mode | Occurrences | Tasks Affected |
|-------------|-------------|----------------|
| I1: Occluded rejection | 10 | SimpleSmsSend, ExpenseAddSingle |
| I2: No UI change | 8 | SimpleSmsSend, FilesMoveFile, BrowserMultiply |
| I3: Screen edge | 2 | FilesMoveFile |
| I4: Long-press | 2 | FilesMoveFile |
| I5: Action confusion | 1 | FilesMoveFile |
| I6: Jitter insufficient | ~8 | (compounds I2 and I3) |

## Key Insight

The retry/fallback chain is complex (up to 12 attempts: ACTION_CLICK + gesture + 4 jitter + re-resolve + 4 more jitter) but fundamentally misses the actual problem in most failures. The chain retries at slightly different coordinates, but the real issue is:
- Wrong coordinates entirely (I1: occlusion rejection leaves valid zone untried)
- Correct coordinates but wrong dispatch method (I4: needs time-based hold, not a11y action)
- Coordinates outside reliable touch zone (I3: screen edges)
