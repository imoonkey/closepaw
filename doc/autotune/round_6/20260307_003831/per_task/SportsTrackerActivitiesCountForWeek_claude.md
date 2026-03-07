# SportsTrackerActivitiesCountForWeek -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 5/30 | **Reason**: GoalAchieved | **Duration**: 35s

**Goal**: Count running activities for the current week (Monday-Sunday) in OpenTracks app. Express as a single integer.

## Root Cause

**Category**: ReasoningError

The agent navigated to the activity list successfully and scrolled through all this-week entries (Today through Monday). However, the a11y tree only exposes activity *names* (user-given titles like "Bicycle Adventure", "Trail Hike", "Mountain Biking") and a generic "Track" icon -- it does not expose the underlying activity *type* (running, cycling, hiking, etc.). The agent attempted to infer the activity type from the title text alone, concluded only 1 activity was running-related, and submitted "1". The actual running count was different, meaning activities whose names did not contain "run" were in fact categorized as running type in the app. The agent had no way to determine this from the a11y tree without opening each activity's detail screen to check its type.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("OpenTracks")` | Y | G | G | G | Opened app, hit onboarding screen |
| 2 | `click(5)` | Y | G | G | G | Dismissed first onboarding prompt |
| 3 | `click(5)` | Y | G | G | G | Dismissed second onboarding prompt, reached activity list |
| 4 | `scroll(down, 9)` | Y | G | G | G | Scrolled to see Monday entries and past-week boundary |
| 5 | `complete_task("1")` | -- | B | -- | -- | Answered based on name heuristic, wrong count |

### Key Turns

**Turn 5** -- The agent saw all this-week activities across two screens (turns 4-5): Bicycle Adventure (Today), Trail Biking (Yesterday), Trying and failing to keep up with John (Yesterday), Trail Hike (Friday), Active Rest Day (Friday), Cycle Trip (Thursday), Alpine Skiing (Thursday), Mountain Biking (Wednesday), Bicycle Adventure (Wednesday), Hill Walk (Tuesday), Skateboard Ride (Tuesday), Trail Ride (Monday), Indoor Climbing (Monday), Cross-country Skiing (Monday). None of these titles explicitly contain "running" or "run" (activities named "Off-road Run" and "Running Challenge" are dated 6 Oct, outside the current week). The agent guessed "1" -- likely interpreting one title as running-adjacent -- but the actual activity types (stored as app metadata, not visible in the a11y tree) classified more activities as running. The agent should have tapped into individual activities to check the recorded sport type rather than relying on title-based heuristics.

## What Worked

- Efficient navigation: opened app, dismissed two onboarding screens, reached activity list in 3 turns
- Correct scrolling strategy to see all days from Today down to Monday
- Correctly identified the week boundary (Monday) and stopped scrolling past prior-week items

## What Didn't Work

- Relied on activity name text to infer activity type instead of checking each activity's actual sport/type metadata
- Did not open any individual activity detail screen to verify the recorded sport type
- Premature completion after a single scroll -- no verification step before answering
- The a11y tree lacks activity-type information at the list level, making name-based inference unreliable

## Recommendations

- **Teach drill-down for ambiguous metadata**: When the task requires filtering by activity type and the list view does not expose type directly, the agent should open representative activities to discover where type info lives, then systematically check each candidate
- **Add a "verify before answer" heuristic for counting tasks**: Before calling complete_task with a count, the agent should enumerate the items it counted and confirm each one matches the filter criteria -- this would have surfaced that no title clearly says "running"
- **Consider using OpenTracks filter/search**: The app has a Search button (visible in the toolbar). The agent could have searched for "running" or used any built-in filter to narrow results by activity type rather than manually scanning titles
