---
name: com.simplemobiletools.calendar.pro
description: App-specific guidance for Simple Calendar Pro.
---

## CRITICAL — Month grid has no per-cell accessibility nodes
- Do not click coordinates to select dates. Tap the day-number text to open a specific day.
- Use < > header arrows to change months.

## Date & Time
- "Nh" notation ALWAYS means 24-hour format: "5h" = 05:00, NOT 17:00.
- For NumberPicker spinners: use scroll/swipe gestures, NOT type().
- For date range queries, navigate to EACH day individually — the month grid does not expose event data.

## Events
- Verify the displayed date matches the target before reading or creating events.
- After saving, reopen the event to verify title, date, and time.
