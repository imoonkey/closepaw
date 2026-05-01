---
name: app-simple-calendar
description: App-specific guidance for Simple Calendar Pro.
metadata:
  package: com.simplemobiletools.calendar.pro
---

## CRITICAL — Month grid has no per-cell accessibility nodes
- Do not click coordinates to select dates. Tap the day-number text to open a specific day.
- Use < > header arrows to change months.

## Date & Time
- "Nh" notation ALWAYS means 24-hour format: "5h" = 05:00, NOT 17:00.
- For NumberPicker spinners: use scroll/swipe gestures, NOT type().
- For date range queries ("next week", "this month"): compute the exact date range to scratchpad, then tap EACH day individually to check for events. The month grid does not expose event data.

## Events
- Verify the displayed date matches the target before reading or creating events.
- After saving, reopen the event to verify title, date, and time.

## Safety

**DANGEROUS -- ask user before:**
- Deleting events
- Modifying date, time, or title of existing events

**SAFE -- proceed normally:**
- Creating new events as specified in the task
- Reading event details, checking schedules
- Navigating between days and months
