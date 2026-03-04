# SportsTrackerActivitiesOnDate -- Per-Task Analysis

**Run ID**: 20260303_232753
**Score**: 0.0 | **Turns**: 15/30 | **Stop Reason**: GoalAchieved (FalseCompletion)
**Model**: qwen3.5 | **Screenshot input**: disabled

---

## Task Goal

> What activities did I do October 1 2023 in the OpenTracks app? Answer with the activity type only. If there are multiple types, format your answer in a comma separated list.

## What the Agent Answered

> Active Rest Day, Mindful Movement

## What Was Expected

The eval expected **activity types** (category labels like "running", "biking", "walking"), NOT track display names. The agent returned the track **names** instead.

---

## Turn-by-Turn Trace Summary

| Turn | Action | Thought | Observation |
|------|--------|---------|-------------|
| 1 | `open_app("OpenTracks")` | Open OpenTracks to find activities from Oct 1, 2023 | App opened, landed on intro/setup screen |
| 2 | `click(7)` | Click button to proceed past intro screen | Another intro step |
| 3 | `click(7)` | Click button to proceed to main app | Reached main track list |
| 4 | `scroll(down, 11)` | Scroll down to find tracks from Oct 1, 2023 | Track list scrolled, now showing tracks dated back to "1 Oct" -- found "Active Rest Day" and "Mindful Movement" |
| 5 | `click(66)` | Click "Active Rest Day" track from 1 Oct | Entered track detail view: Stats tab showing name, date, distance, times, speeds, elevation |
| 6 | `click(40)` | Navigate back to track list to check both tracks | Back on list view (at lower scroll position showing Oct 1 tracks) |
| 7 | `click(73)` | Click "Mindful Movement" track from 1 Oct | Entered Mindful Movement detail view |
| 8 | `click(43)` | Click More options to see if activity type is in menu | Menu opened with: Markers, Edit, Delete, P... (truncated) |
| 9 | `click(40)` | Go back to track list to examine Oct 1 tracks | Still on detail view with menu shown |
| 10 | `system_button(back)` | Dismiss options menu | Menu dismissed |
| 11 | `scroll(down, 14)` | Scroll down in track detail to look for activity type | Scrolled to Intervals tab content (no activity type shown) |
| 12 | `click(24)` | Go back to track list | Returned to track list |
| 13 | `scratchpad(write, {"oct_1_activities": "Active Rest Day, Mindful Movement"})` | Store the two activity types found for Oct 1 | Stored to scratchpad |
| 14 | `click(66)` | Click Active Rest Day to view detail and find activity type | Back in detail view |
| 15 | `complete_task(success, "Active Rest Day, Mindful Movement")` | Final answer | Score: 0.0 |

---

## Root Cause Analysis

### Primary Failure: Confused Track Names with Activity Types

The agent consistently conflated **track names** (display titles like "Active Rest Day", "Mindful Movement") with **activity types** (category labels like "running", "biking"). Despite the system prompt containing an explicit app tip:

> **OpenTracks (Sports Tracker)**: The track list shows track NAMES, not activity types. To find the activity type (e.g., "running", "walking"), you must tap into each track's detail view. "What activities" questions ask for category/type labels, NOT track display names.

The agent failed to follow this guidance. It tapped into both track detail views but never found or extracted the actual activity type.

### Secondary Failure: Activity Type Not Exposed in A11y Tree

The activity type in OpenTracks is represented **only as an icon** (e.g., a running figure, a cycling figure) in both the track list and the track detail view. In the accessibility tree:

- **Track list**: Each track's icon is described as `"Track"` (generic ImageView desc) -- no activity type text
- **Track detail view**: The activity icon appears at the top-left but has no text/content-description in the a11y tree that reveals the type

The a11y tree for the "Active Rest Day" detail view shows:
- `"Active Rest Day"` (track name)
- `"Gained new knowledge and skills."` (description)
- `"Oct 1, 2023, 1:30:00 PM"` (date)
- Stats fields (distance, time, speed, elevation)
- **No field labeled "Activity type" or containing "running", "walking", etc.**

Similarly for "Mindful Movement" -- the cycling icon is visible in the screenshot but not in the a11y tree.

### Tertiary Failure: Did Not Try the "Edit" Path

When the agent opened the "More options" menu (turn 8), it saw options including "Edit". Tapping "Edit" would likely have opened a form showing the activity type as a selectable/labeled field. The agent did not try this path -- instead it dismissed the menu and gave up trying to find the activity type, falling back to using track names as the answer.

---

## Cognitive Failures

1. **Instruction adherence failure**: The system prompt explicitly told the agent that track names are not activity types, but the model (qwen3.5) did not internalize this distinction. It wrote `{"oct_1_activities": "Active Rest Day, Mindful Movement"}` to scratchpad with the thought "Store the two activity types found" -- demonstrating it genuinely believed track names were activity types.

2. **Premature completion**: The agent called `complete_task` without verifying its answer matched the expected format (category labels). It had 15 turns remaining but chose to submit track names despite the system prompt warning.

3. **Incomplete exploration**: The agent saw the "Edit" option in the More Options menu but did not try it. The Edit screen is the most likely place to expose the activity type as a labeled text field in the a11y tree.

4. **No vision fallback awareness**: Without screenshot input enabled, the agent cannot see the activity type icons. It needed to find the type through a text-accessible path (Edit form). The agent did not recognize this limitation or adapt its strategy accordingly.

---

## Structural / Platform Issues

### A11y Gap: Activity Type Icon Not Labeled

This is a significant accessibility gap in OpenTracks. The activity type is conveyed only through an icon, with no `contentDescription` or text label that exposes the type name (e.g., "running", "biking") in the accessibility tree. This makes it impossible to determine the activity type from the Stats tab or the track list using a11y alone.

**Possible workarounds**:
1. **Edit form**: Tapping "More options > Edit" likely exposes the activity type as a labeled form field
2. **Screenshot input**: With vision enabled, the agent could visually identify the icon shape (running figure = running/walking, bicycle = biking)

---

## Prompt / Agent Improvements

### 1. Strengthen the OpenTracks App Tip (High Impact)

The current tip says "you must tap into each track's detail view" but the detail Stats tab does NOT expose the type either. The tip should be more specific:

```
### OpenTracks (Sports Tracker)
- Track list shows track NAMES (e.g., "Active Rest Day"), NOT activity types.
- The Stats detail view also does NOT show the activity type as text.
- To find the activity type: tap More options > Edit in the track detail view.
  The Edit form has an "Activity type" field with the actual category label.
- "What activities" questions ask for category/type labels (e.g., "running", "biking"),
  NOT track display names. Never return track names as activity types.
```

### 2. Add Self-Check Before Completion for QA Tasks (Medium Impact)

The agent should be prompted to verify that its answer matches the expected semantic category. For "activity type" questions, the answer should be generic category labels, not proper noun track names.

### 3. Model Capability (Medium Impact)

The qwen3.5 model failed to follow the explicit system prompt guidance distinguishing track names from activity types. A stronger model might have caught this distinction. However, even a stronger model would struggle without the "Edit" path knowledge since the a11y tree simply does not contain the activity type text in the Stats view.

### 4. Enable Screenshot Input (Low-Medium Impact)

With screenshot input enabled, the agent could visually identify the activity type icons (running figure, cycling figure, etc.). However, this is a fragile approach since icon recognition varies by icon set and model capability.

---

## Tags

`#false-completion` `#name-vs-type-confusion` `#a11y-gap` `#app-tip-insufficient` `#unexplored-edit-path` `#model-instruction-adherence`
