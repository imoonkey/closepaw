# Click Execution False-Success Scan (Claude Independent Analysis)

**Run**: `eval/results/20260306_003753` (10-task smoke, gpt-5.4, basic agent)
**Date**: 2026-03-06
**Analyst**: Claude (independent — no prior analysis consulted)

## 1. Scope & Method

**Tasks** (from `eval/config/gpt54_never_succeeded_10.txt`):
AudioRecorderRecordAudioWithFileName, BrowserDraw, BrowserMultiply, ClockTimerEntry,
ExpenseAddMultipleFromGallery, ExpenseAddMultipleFromMarkor, ExpenseDeleteDuplicates2,
MarkorMergeNotes, OsmAndMarker, OsmAndTrack

**Method**: For every mobile_action turn that includes a click/tap/long_press/swipe:
1. Read the tool_call JSON (target, coordinates, method)
2. Read the tool_result text (success/failure, execution path)
3. Visually compare before-screenshot and after-screenshot (consecutive seq numbers)
4. Cross-check a11y tree observations where available
5. Classify: **genuine success**, **false-success** (tool says success, screen unchanged), or **ambiguous**

**Definition of false-success**: tool_result reports "Success" but the before/after screenshots and a11y trees show no meaningful UI state change — the intended target was not activated.

---

## 2. Per-Task Findings

### 2.1 BrowserDraw (FAILED, 28 turns) — 9 false-successes / 21 click actions

The agent spent all 28 turns trying to open task.html in Chrome and never reached the drawing page.

| Turn | Target | Method | Result | Screen Changed? | Verdict |
|------|--------|--------|--------|-----------------|---------|
| 2 | task.html tile (elem 17) | node_action_click | Success | No | **FALSE-SUCCESS** |
| 4 | file tile (150,401) | gesture_tap | Success | Yes (wrong target — hit Images filter) | Genuine (misdirected) |
| 5 | "Documents" filter | node_action_click | Success | Yes | Genuine |
| 6 | task.html tile (elem 17) | node_action_click | Success | No | **FALSE-SUCCESS** |
| 7 | task.html tile (elem 17) | node_action_long_click | Success | Yes (selection mode) | Genuine |
| 8 | "More options" | node_action_click | Success | Yes (menu opened) | Genuine |
| 9 | "Open with" | node_action_click | Success | Yes (app chooser) | Genuine |
| 10 | "Chrome" | node_action_click | Success | Yes (Chrome first-run) | Genuine |
| 11 | "Use without an account" | node_action_click | Success | No | **FALSE-SUCCESS** |
| 13 | (548,894) Chrome body | gesture_tap | Success | No | **FALSE-SUCCESS** |
| 14 | (534,2090) off-screen | gesture_tap | Success | Yes (unintended back nav) | Ambiguous |
| 15 | X close selection (elem 4) | node_action_click | Success | Yes | Genuine |
| 16 | (88,386) file icon area | gesture_tap | Success | Minimal filter change | Ambiguous |
| 17 | list view toggle (elem 16) | node_action_click | Success | Yes | Genuine |
| 18 | (115,291) HTML icon in list | gesture_tap | Success | No | **FALSE-SUCCESS** |
| 19 | "More options" | node_action_click | Success | Yes | Genuine |
| 21 | (230,297) filename text | gesture_tap | Success | No | **FALSE-SUCCESS** |
| 22 | "Search" | node_action_click | Success | Yes | Genuine |
| 25 | (530,640) micro-swipe as tap | swipe gesture | Success | No | **FALSE-SUCCESS** |
| 26 | (39,78) overlay back | gesture_tap | Success | No | **FALSE-SUCCESS** |
| 27 | (424,78) overlay close | gesture_tap | Success | No | **FALSE-SUCCESS** |

**Key pattern**: node_action_click on the file tile in Files app fails silently (turns 2, 6). gesture_tap on file content areas universally fails (turns 18, 21, 25, 26, 27). The automation overlay intercepts touch events in the content zone. Toolbar/chrome elements (filters, overflow, search, list-toggle) work fine.

**Root cause**: The debug overlay covers the main content area. The agent correctly diagnosed this in its final error message.

---

### 2.2 BrowserMultiply (SUCCESS 1.0, 29 turns) — 3 false-successes / 18 click actions

Despite false-successes, the agent eventually completed the task.

| Turn | Target | Method | Result | Screen Changed? | Verdict |
|------|--------|--------|--------|-----------------|---------|
| 2 | task.html in Files (elem 17) | node_action_click | Success | No | **FALSE-SUCCESS** |
| 4 | task.html (long_press) | node_action_long_click | Success | Yes (selection mode) | Genuine |
| 5 | "More options" | node_action_click | Success | Yes | Genuine |
| 6 | "Open with" | node_action_click | Success | Yes (Chrome opened) | Genuine |
| 7 | Chrome chooser (elem 5) | node_action_click | Success | Negligible | Ambiguous |
| 8 | "Use without an account" | node_action_click | Success | No | **FALSE-SUCCESS** |
| 10 | "Use without an account" | node_action_click | Success | Yes (navigated away) | Genuine |
| 11 | "Cancel" selection | node_action_click | Success | Yes | Genuine |
| 12 | task.html (elem 17) | node_action_click | Success | Yes (Chrome opened page!) | Genuine |
| 14 | "Click Me" button (1st) | node_action_click | Success | Yes (number changed) | Genuine |
| 17 | "Click Me" button (2nd) | node_action_click | Success | Yes (number changed) | Genuine |
| 19 | "Click Me" button (3rd) | node_action_click | Success | Number stayed "5" | Ambiguous (could be same random number) |
| 21 | "Click Me" button (4th) | node_action_click | Success | Yes (form appeared) | Genuine |
| 22 | "Click Me" (5th, stale target) | node_action_click | Success | No (form already showing) | **FALSE-SUCCESS** |
| 27 | "Submit" button | node_action_click | Success | Yes ("Success!" shown) | Genuine |

**Key pattern**: Same file-tile click failure as BrowserDraw (turn 2). Same Chrome first-run button failure (turn 8). Turn 22 clicked at stale coordinates after the page had transitioned to the form — the element_index now pointed at an EditText, not the button.

**Notable**: The agent recovered from every false-success by retrying with different strategies (long_press → Open with, retry Chrome button, etc.). Task succeeded despite 3 false-successes.

---

### 2.3 AudioRecorderRecordAudioWithFileName (FAILED, 9 turns) — 0 false-successes

All 5 click actions (dismiss welcome, apply setup, start recording, stop recording, save) produced genuine, visible screen changes. No false-success issues.

Failure was a cognition issue (saved file with wrong name), not an execution issue.

---

### 2.4 ClockTimerEntry (SUCCESS 1.0, 7 turns) — 0 false-successes

All 5 clicks (Timer tab, digits 1/6/3/5) produced correct, visible changes. Clean execution.

---

### 2.5 ExpenseAddMultipleFromGallery (FAILED, 17 turns) — 4 false-successes

| Turn | Target | Method | Result | Screen Changed? | Verdict |
|------|--------|--------|--------|-----------------|---------|
| 3 | DCIM folder | node_action_click | Success | Yes | Genuine |
| 4 | Search icon | node_action_click | Success | Yes | Genuine |
| 5 | Image thumbnail | node_action_click | Success | Yes | Genuine |
| 7 | Scroll image (a11y_scroll) | Success | No — image viewer doesn't scroll | **FALSE-SUCCESS** |
| 8 | Scroll image (a11y_scroll) | Success | No | **FALSE-SUCCESS** |
| 9 | Swipe up on image | swipe gesture | Success | No | **FALSE-SUCCESS** |
| 10 | Edit button | node_action_click | Success | Yes | Genuine |
| 12 | Scroll image (a11y_scroll) | Success | No | **FALSE-SUCCESS** |
| 15 | Navigation drawer | node_action_click | Success | Yes | Genuine |
| 16 | "Expense Logs" | node_action_click | Success | Yes | Genuine |

**Key pattern**: Scroll/swipe on non-scrollable image view is the dominant false-success here. The Gallery image viewer doesn't support a11y scroll actions, but the action layer reports "Success: Scrolled down via a11y_scroll". The agent wasted 4 turns trying to scroll, then gave up on reading the full image and falsely claimed expenses were "already present."

---

### 2.6 ExpenseAddMultipleFromMarkor (FAILED, 14 turns) — 1 false-success

| Turn | Target | Method | Result | Screen Changed? | Verdict |
|------|--------|--------|--------|-----------------|---------|
| 3 | my_expenses.txt file | node_action_click | Success | Yes | Genuine |
| 6 | Search icon | node_action_click | Success | Yes | Genuine |
| 8 | OK on search dialog | node_action_click | Success | Yes | Genuine |
| 10 | Scroll file list (a11y_scroll) | Success | No — already inside a file, not in list | **FALSE-SUCCESS** |
| 12 | Save button | node_action_click | Success | Ambiguous (minor UI shift) | Ambiguous |

The false-success here is the same a11y_scroll pattern: scrolling reported success on a view that can't scroll further. Agent confusion led it to write expenses into Markor's To-Do note instead of the Pro Expense app.

---

### 2.7 ExpenseDeleteDuplicates2 (FAILED, 26 turns) — 1 false-success

| Turn | Target | Method | Result | Screen Changed? | Verdict |
|------|--------|--------|--------|-----------------|---------|
| 5 | Expense list item | node_action_click | Success | Yes | Genuine |
| 8 | Filter button | node_action_click | Success | Yes | Genuine |
| 9 | Ascending date option | node_action_click | Success | No — filter dialog unchanged | **FALSE-SUCCESS** |
| 10 | Ascending order apply | node_action_click | Success | Yes | Genuine |
| 11 | Delete button | gesture_tap (fallback) | Success | Yes (but wrong element) | Ambiguous |
| 12 | Action icon in detail | node_action_click | Success | Yes | Genuine |
| 15-25 | Various nav/backup actions | node_action_click | Success | Yes | Genuine |

Low false-success count. Turn 9 is a single filter option click that didn't register. Turn 11 is a fallback gesture_tap that hit a different element than intended (node identity mismatch). Task failure was primarily a cognition issue (agent couldn't figure out how to identify and delete duplicates safely).

---

### 2.8 MarkorMergeNotes (FAILED, 20 turns) — 0 false-successes

All 8 click/type actions produced genuine screen changes. Zero false-success issues.

Task failure was a cognition/content issue (merged content didn't match expected output), not execution.

---

### 2.9 OsmAndMarker (FAILED, 21 turns) — 3 false-successes

| Turn | Target | Method | Result | Screen Changed? | Verdict |
|------|--------|--------|--------|-----------------|---------|
| 2 | Search button | node_action_click | Success | Yes | Genuine |
| 4 | Liechtenstein result | node_action_click | Success | Yes | Genuine |
| 5 | Map area (long_press) | node_action_long_click | Success | Unclear | Ambiguous |
| 7 | "INCREASE SEARCH RADIUS" | node_action_click | Success | No — loading spinner flash, same results | **FALSE-SUCCESS** |
| 8 | "SHOW ON MAP" | node_action_click | Success | Yes (map view, wrong location) | Genuine |
| 11 | X/clear on map search | gesture_tap | Success | No | **FALSE-SUCCESS** |
| 12 | Search field text on map | gesture_tap | Success | No | **FALSE-SUCCESS** |
| 14 | Clear query | node_action_click | Success | Yes | Genuine |
| 15 | Address tab | node_action_click | Success | Yes | Genuine |
| 16 | COORDINATES SEARCH | gesture_tap (fallback) | Success | Yes | Genuine |
| 19 | Coordinate result | node_action_click | Success | Yes | Genuine |
| 20 | Marker action button | node_action_click | Success | Yes (marker added) | Genuine |

**Key pattern**: "INCREASE SEARCH RADIUS" silently fails (turn 7). gesture_tap on OsmAnd's map overlay search controls fails (turns 11-12). These wasted turns contributed to the agent settling on wrong coordinates.

---

### 2.10 OsmAndTrack (FAILED, 28 turns) — 4 false-successes

| Turn | Target | Method | Result | Screen Changed? | Verdict |
|------|--------|--------|--------|-----------------|---------|
| 3 | Route tool | node_action_click | Success | Yes | Genuine |
| 5 | Departure field (336,132) | gesture_tap | Success | No | **FALSE-SUCCESS** |
| 6 | From field (elem 16) | node_action_click | Success | Yes | Genuine |
| 7 | Search option | node_action_click | Success | Yes | Genuine |
| 10 | "Liechtenstein" result | gesture_tap (fallback) | Success | Yes | Genuine |
| 11 | Add waypoint | node_action_click | Success | Yes | Genuine |
| 12 | Search in destination | node_action_click | Success | Yes | Genuine |
| 16 | "Address" tab | node_action_click | Success | Yes | Genuine |
| 18 | "INCREASE SEARCH RADIUS" | node_action_click | Success | No — still "Could not find anything" | **FALSE-SUCCESS** |
| 20 | "FIRST SPECIFY CITY/TOWN/LOCALITY" | gesture_tap (fallback) | Success | Yes | Genuine |
| 23 | "INCREASE SEARCH RADIUS" again | node_action_click | Success | No — identical | **FALSE-SUCCESS** |
| 25 | Back arrow | node_action_click | Success | Yes | Genuine |
| 26 | "Nearest cities" | gesture_tap (fallback) | Success | No | **FALSE-SUCCESS** |
| 27 | Back arrow | node_action_click | Success | Yes | Genuine |

**Key pattern**: Same "INCREASE SEARCH RADIUS" false-success as OsmAndMarker (turns 18, 23). gesture_tap on OsmAnd Address sub-options also fails silently (turn 26).

---

## 3. High-Confidence False-Successes vs. Other Categories

### 3.1 High-Confidence False-Successes (25 instances across 7 tasks)

These are cases where the tool returned "Success" and the screen was verifiably unchanged:

| Category | Count | Tasks | Root Cause |
|----------|-------|-------|------------|
| **File tile click in Files app** | 3 | BrowserDraw (2,6), BrowserMultiply (2) | node_action_click dispatched but file row doesn't respond. Overlay may intercept. |
| **Chrome first-run button** | 2 | BrowserDraw (11), BrowserMultiply (8) | "Use without an account" node_action_click dispatched but button doesn't activate. |
| **gesture_tap on content area** | 6 | BrowserDraw (13,18,21,25,26,27) | Taps on Files content/overlay area all intercepted. Zero screen change. |
| **a11y_scroll on non-scrollable view** | 5 | ExpenseGallery (7,8,9,12), ExpenseMarkor (10) | Scroll action reports success on views that can't scroll. |
| **OsmAnd "INCREASE SEARCH RADIUS"** | 3 | OsmAndMarker (7), OsmAndTrack (18,23) | Button click dispatched, loading spinner flashes, but results unchanged. |
| **gesture_tap on OsmAnd overlay** | 4 | OsmAndMarker (11,12), OsmAndTrack (5,26) | Taps on map/search overlay controls produce no effect. |
| **Stale element click** | 1 | BrowserMultiply (22) | Page transitioned; element_index now points to different widget. |
| **Filter option in Pro Expense** | 1 | ExpenseDeleteDuplicates2 (9) | Sort option click didn't register in filter dialog. |

### 3.2 Observation Lag / Timing (0 confirmed)

No clear cases of observation lag were found. In all false-success cases, the a11y tree also confirmed no state change — not just the screenshots.

### 3.3 Overlay / Unsupported Action (subset of above)

- **Debug overlay interception**: Primarily affects BrowserDraw (turns 13, 18, 21, 25, 26, 27). The overlay's touch capture zone blocks gesture_taps in the content area. This is the single largest source of false-successes (6 instances).
- **Unsupported action on view**: a11y_scroll on Gallery image viewer (4 instances) — the view simply doesn't support this accessibility action, but the action layer doesn't verify the result.

### 3.4 Ambiguous Cases (6 instances)

- BrowserDraw turn 14: Off-screen coordinate caused unintended navigation
- BrowserDraw turn 16: Minor filter chip change, not intended target
- BrowserMultiply turn 7: Click below viewport, negligible change
- BrowserMultiply turn 19: "Click Me" produced same number (could be random)
- ExpenseMarkor turn 12: Possible minor save state change
- ExpenseDeleteDuplicates2 turn 11: Node identity mismatch, fallback hit wrong element

---

## 4. Global vs. Local Regression Assessment

### This is NOT a global regression. It is a combination of app-specific and mechanism-specific issues.

**Evidence for local/app-specific:**
- **3 tasks had zero false-successes**: AudioRecorder, ClockTimerEntry, MarkorMergeNotes. These use standard, simple UI elements (buttons, list items, text fields) that respond reliably to both node_action_click and gesture_tap.
- **False-successes cluster around specific apps/UIs**: Files app file rows, Chrome first-run, OsmAnd search overlay, Gallery image viewer.
- The 2 successful tasks (ClockTimerEntry, BrowserMultiply) both had clean or recoverable execution.

**Evidence for mechanism-specific patterns (cross-app):**
1. **node_action_click dispatch-without-effect**: Affects Files app file tiles and Chrome buttons. The a11y framework acknowledges the action but the target widget doesn't process it. This is likely related to the debug overlay intercepting the underlying touch event chain.
2. **gesture_tap on overlay-covered areas**: Universal failure when the debug overlay occupies the tap zone. Affects Files content area and OsmAnd map overlay.
3. **a11y_scroll on non-scrollable views**: The action layer doesn't validate whether the scroll actually moved content. Affects Gallery and Markor.

### Severity Ranking

| Issue | Severity | Frequency | Impact |
|-------|----------|-----------|--------|
| Debug overlay intercepting content-area touches | **Critical** | 9 instances (BrowserDraw: 6, OsmAnd: 3) | Blocks entire task (BrowserDraw couldn't open file) |
| node_action_click silent failure on file rows | **High** | 3 instances (Browser tasks) | Wastes turns; recoverable via long_press workaround |
| a11y_scroll false-success on non-scrollable views | **Medium** | 5 instances (Expense tasks) | Wastes turns; causes incorrect agent conclusions |
| OsmAnd INCREASE SEARCH RADIUS no-op | **Medium** | 3 instances | Wastes turns; app-specific, not an agent bug |
| Chrome first-run button non-responsive | **Low** | 2 instances | Recoverable on retry; one-time setup issue |

### Recommendations

1. **Post-action screen diff**: The tool layer should compare before/after a11y trees (or screenshots) and flag when "Success" was returned but no state changed. This would catch all 25 false-successes.
2. **a11y_scroll validation**: After dispatching a scroll action, verify the top-of-tree element positions changed. If not, return failure.
3. **Debug overlay awareness**: The overlay's touch capture zone needs to be excluded from gesture_tap coordinates, or the overlay should be set to non-intercepting during task execution.
4. **File row click strategy**: Since node_action_click fails on Files app rows, the agent should prefer long_press → "Open with" as the primary file-opening strategy (as BrowserMultiply eventually discovered).
