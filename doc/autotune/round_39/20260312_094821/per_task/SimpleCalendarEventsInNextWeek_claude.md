# SimpleCalendarEventsInNextWeek -- R91 (R39 eval run 20260312_094821)

**Score: 0.0 | Turns: 5/30 | GoalAchieved (false positive)**

## Root Cause: Cognition -- "next week" date boundary misinterpretation

Device date is 2023-10-15 (Sunday). "Next week" starting Monday = Mon Oct 16 -- Sun Oct 22.
The correct events are: Product demo (Oct 18), Movie night (Oct 22).
The agent listed 18 events from Oct 23--27, treating "next week" as the *following* Monday--Sunday
rather than the immediately upcoming one. The event list view clearly showed Oct 18 and Oct 22
events before the Oct 23 section, so the data was visible -- the agent simply applied the wrong
date boundary.

## R91 prompt cuts -- not a contributing factor

The removed line ("Due next week = the very next Monday through Sunday") was in the Tasks.org skill,
not the Simple Calendar skill. Simple Calendar has its own skill file. The agent never lacked
domain-specific guidance here; it made a general reasoning error about relative week semantics
when "today" is the last day of the current week (Sunday).

## History: flaky (1/3 recent, "regressed" in scoreboard)

Passed R11 (qwen3.5), R16 (gpt-5.4). Failed R10, R39. Historically 3/10 overall. The task is
inherently fragile because "next week" interpretation depends on the model's date reasoning,
which varies across seeds and models.

**One-line summary:** Cognition error -- agent counted Oct 23--27 events instead of Oct 16--22; unrelated to R91 prompt cuts.
