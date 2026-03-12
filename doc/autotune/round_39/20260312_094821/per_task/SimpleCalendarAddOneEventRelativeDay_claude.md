# SimpleCalendarAddOneEventRelativeDay — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 21/30 | **Reason**: GoalAchieved | **Duration**: 156s

**Goal**: Create a calendar event on a relative day (e.g., "tomorrow", "next Friday").

## Root Cause

**Category**: FalseCompletion

The agent resolves the relative date ("tomorrow", "next Friday", etc.) to an absolute date incorrectly. The ambiguity arises from the agent's uncertainty about the current date at execution time combined with relative day interpretation differences (e.g., "next week" boundary, "this Friday" vs "next Friday"). The event is created on the wrong date, but the agent sees a successfully created event and declares GoalAchieved without verifying the selected date matches the intended relative-day target.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-3 | Launch Simple Calendar, navigate to add event | Yes | Correct | Good | OK | Add event form opened |
| 4-6 | Resolve relative date to absolute date | No | Reasoning error | Failed | Wrong date computed | Off-by-1-day or wrong week boundary |
| 7-12 | Fill event details, set computed date in calendar | Yes | Agent believes correct | Degraded | Wrong date set | Calendar picker navigated to wrong date |
| 13-18 | Fill title, time, and remaining fields | Yes | Correct | Good | OK | Other fields correct |
| 19-21 | Save event, declare GoalAchieved | Yes | False | Failed | FalseCompletion | Event saved but on wrong date |

### Key Turns

**Turn 4** — Date reasoning failure: agent maps the relative day reference to an absolute date incorrectly. Common failure modes: treating "tomorrow" as today's date; ambiguity in "next [day]" (current week vs following week); miscounting days when near week/month boundaries.

**Turn 7** — Calendar picker is set to the wrong computed date. Agent navigates to this date without cross-checking it against the relative reference from the task description.

**Turn 21** — GoalAchieved declared after event is saved. Agent saw a successfully created event and concluded the task was done, without verifying the event date matched the relative day in the goal.

## What Worked

- App launched and event creation form was reached
- All event fields (title, time, description) were filled correctly
- Calendar date picker navigation worked mechanically
- Event was saved successfully

## What Didn't Work

- Relative-to-absolute date conversion was wrong
- No verification that the selected date in the calendar matched the intended relative day
- FalseCompletion: event was created but on wrong date
- Agent had no ground-truth current-date check to anchor relative reasoning

## Recommendations

- Prompt: "Before setting a date for a relative day reference (tomorrow, next Friday, etc.), first navigate to today's date in the calendar to confirm today's date, then calculate the target date from there. Do not assume the current date."
- Add explicit date verification step: "After selecting the date, read back the displayed date in the calendar and confirm it matches your computed target before saving"
- Consider adding a `get_current_date` tool call or injecting current date into system prompt context to eliminate date ambiguity
- The verification step alone (read back selected date and compare to intent) would catch most of these failures
