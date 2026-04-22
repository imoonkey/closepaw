# SportsTrackerActivitiesOnDate — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 5 | **Reason**: GoalAchieved (false) | **Duration**: 36s
**Goal**: What activities did I do October 1 2023 in the OpenTracks app? Answer with the activity type only. If there are multiple types, format your answer in a comma separated list.

## Root Cause
**Category**: QAProtocol / FieldSemantics
**Summary**: Agent answered with track NAMES ("Active Rest Day, Mindful Movement") instead of ACTIVITY TYPES (the sport/category). The screen showed tracks listed by name, and the agent confused the track display name with the activity type field. The goal explicitly asks for "activity type" not "activity name."

## Turn-by-Turn Analysis
### Turn 1
- **Action**: `open_app(app_name="OpenTracks")`
- **Result**: Success
- **Assessment**: Good

### Turns 2-3
- **Action**: `mobile_action(click, elem=5)` × 2 — skip intro screens
- **Result**: Success — navigated past onboarding
- **Assessment**: Good

### Turn 4
- **Action**: `mobile_action(scroll, elem=9, direction="down")` — scroll to find Oct 1
- **Result**: Success — Oct 1 entries visible:
  - `[64] Track | Active Rest Day | 1 Oct` (50:00, 1.24 mi)
  - `[71] Track | Mindful Movement | 1 Oct` (2:45:00, 5.59 mi)
- **Assessment**: Good navigation, but poor data extraction

### Turn 5
- **Action**: `complete_task(status="success", answer="Active Rest Day, Mindful Movement")`
- **Result**: Task claimed success but scored 0.0
- **Assessment**: **Wrong field extracted** — agent reported the track NAMES, not the ACTIVITY TYPES

## Screen State at Turn 4
The a11y tree showed elements like:
```
[64] Track | Active Rest Day | 1 Oct
[66] Active Rest Day          ← This is the track NAME
[71] Track | Mindful Movement | 1 Oct
[73] Mindful Movement         ← This is the track NAME
```

The "activity type" (e.g., "Running", "Walking", "Hiking") is a separate field that is NOT displayed in the list view. The agent would need to click into each track to see its activity type/category.

## Failure Points
1. **Field semantics confusion**: "activity type" ≠ "track name". The agent treated the display name as the activity type
2. **No investigation of individual tracks**: Should have clicked into each Oct 1 track to find the category/type field
3. **Too hasty completion**: Only 5 turns — agent should have verified the field semantics before answering
4. **No scratchpad usage**: Didn't record findings for review

## What Worked
- Fast navigation to correct date
- Quick task completion attempt (efficient but wrong)

## What Didn't Work
- Incorrect field identification — critical QA failure
- No verification that the extracted data matched what the goal asked for

## Recommendations
- **QA field semantics in prompt**: Reinforce that "activity type" means the sport/category label (e.g., "running"), NOT the display name of the track/session
- **Verification before answering**: For QA tasks, agent should open at least one item to verify it's extracting the correct field
- **Known app patterns**: OpenTracks shows track names in the list view; activity types require opening the track detail view
