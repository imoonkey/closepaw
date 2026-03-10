# ExpenseAddMultipleFromGallery - R31 Trace Analysis

**Score**: 0.0 | **Turns**: 13/60 | **Reason**: Error (self-reported failure) | **Duration**: ~90s

**Goal**: Add the expenses from `expenses.jpg` in Simple Gallery Pro to Pro Expense.

## Root Cause

**Category**: WrongFile + OverlayBlock + EarlyGiveUp

Two compounding failures:

1. **Wrong source file**: The agent opened the first visible thumbnail in DCIM instead of locating `expenses.jpg` specifically. It landed on `old_expenses_6.jpg`, then inadvertently swiped to `old_expenses_7.jpg`. The a11y tree clearly showed `old_expenses_6.jpg` in the title bar at turn 6, but the agent never checked the filename.

2. **Agent overlay blocking image bottom**: The agent's own UI overlay (Takeover/Stop bar + text input) covers the bottom ~20% of the screen. The last visible expense entry is "Social Club Dues" (partially cut off). The agent spent turns 8-12 trying to scroll/swipe/dismiss the overlay, all failing. At turn 13 it gave up and called `complete_task(failure)`.

The "Error" stop_reason in the eval framework is because the agent called `complete_task` with `status: "failure"`.

## Turn-by-Turn Analysis

| Turn | Action | Result | Note |
|------|--------|--------|------|
| 1 | `open_app("Simple Gallery Pro")` | Error: app not found | App is registered as "Gallery" |
| 2 | `open_app("Gallery")` | Success | Opened Gallery (com.simplemobiletools.gallery.pro) |
| 3 | `click(element 11)` - DCIM folder | Success | Entered DCIM folder showing multiple expense image thumbnails |
| 4 | `click(element 11)` - first thumbnail | Success | Opened an image without verifying it was `expenses.jpg` |
| 5 | `scratchpad(write)` | Success | Extracted 8 visible expenses from the image |
| 6 | `scroll(down)` | Success | Scrolled but image content did not change; title shows `old_expenses_6.jpg` |
| 7 | `scratchpad(write)` | Success | Re-recorded same 8+1 partial expenses |
| 8 | `scroll(down)` | Success | Swiped to next image: `old_expenses_7.jpg` (same content pattern) |
| 9 | `click(562, 1706)` | Success (no change) | Tried to dismiss overlay by tapping image area |
| 10 | `scroll(down)` | Error | Scroll had no observable effect (already at bottom of image) |
| 11 | `swipe(549,1760 -> 548,980)` | No change | Aggressive swipe attempt to move image up past overlay |
| 12 | `click(977, 1867)` | No change | Tried to tap collapse button on overlay |
| 13 | `complete_task(failure)` | Task failed | Gave up citing overlay obstruction |

### Key Observations

**Turn 4**: The agent clicked the first visible thumbnail without any filename verification. The DCIM folder contained multiple expense images (old_expenses_6, old_expenses_7, old_expenses_8, etc.) plus presumably `expenses.jpg`. The thumbnails all look similar (text-heavy expense lists), making visual distinction impossible.

**Turn 6**: The a11y tree exposed `old_expenses_6.jpg` as the filename at index 8. The agent stored expense data to scratchpad but never checked whether this was the correct file.

**Turn 8**: Scrolling inside the Gallery image viewer swiped to the next image (`old_expenses_7.jpg`) rather than scrolling within the current image. The agent did not notice the filename change.

**Turns 9-12**: Five consecutive turns wasted trying to see content hidden behind the agent's own overlay. The agent never considered alternative strategies (e.g., pinch-to-zoom, landscape rotation, or proceeding with visible data and going back for the rest).

**Turn 13**: The agent surrendered with a detailed failure message. It never attempted to open Pro Expense or add any expenses.

## What Worked

- Gallery navigation to DCIM was correct (after the app-name correction).
- OCR-style extraction from the screenshot was accurate for visible entries.
- The agent correctly planned a cross-app workflow with todos.

## What Did Not Work

1. **No filename verification**: Opened the first thumbnail without checking if it was `expenses.jpg`.
2. **No Gallery search/filter**: Did not use the "Search in DCIM" bar visible at the top of the folder view.
3. **Overlay-blocked content**: The agent overlay covers the bottom of full-screen images. There is no effective dismiss mechanism.
4. **Premature surrender**: 13 of 60 turns used. The agent could have proceeded with the 8 visible expenses and still partially completed the task. Instead it stopped entirely.
5. **Wrong file, no course correction**: Even if unblocked, the agent was reading from the wrong file.

## Proposed Fixes

### Fix 1: Gallery app skill - file search guidance (high priority)
Create `app/src/main/assets/app_skills/com.simplemobiletools.gallery.pro/SKILL.md` with:
- When looking for a specific image file, use the search bar at the top of the folder view to search by filename instead of browsing thumbnails.
- After opening an image, verify the filename in the title bar matches the expected file before extracting content.

### Fix 2: Agent overlay mitigation for full-screen content reading
The agent's own overlay (Takeover/Stop/input bar) permanently covers ~20% of the screen bottom. For image-reading tasks this is fatal. Options:
- **Collapse overlay before reading**: If the minimize button on the overlay works, the agent skill should instruct tapping it before extracting image content.
- **Use landscape orientation**: Rotating to landscape may shift the overlay away from critical content.
- **Platform fix**: Auto-collapse the agent overlay when a full-screen image is detected, or provide a tool that reads image content without relying on the visible screen region.

### Fix 3: Prompt-level resilience - proceed with partial data
Add guidance that when content is partially visible (e.g., overlay obstruction), the agent should proceed with what it can read rather than giving up entirely. 8 of ~10 expenses visible is still better than 0 added.

### Fix 4: Cross-app task never-give-up-early rule
The agent used only 13 of 60 turns. It should at minimum attempt the add flow in Pro Expense with the data it already extracted, even if incomplete.

## Classification

- **Source-artifact binding failure**: Same issue as R5 (opened wrong file, never verified filename).
- **Overlay obstruction**: Platform-level blocker unique to image-reading tasks.
- **Early capitulation**: Agent surrendered without attempting the second phase (adding to Pro Expense).
- **Repeat offender**: This task has failed in every round it has been attempted (R5, R18-R21, R28-R31). The wrong-file issue was identified in R5 but never fixed.
