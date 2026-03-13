---
name: com.simplemobiletools.calendar.pro
description: App-specific guidance for Simple Calendar Pro.
---

# Simple Calendar Pro Skill

## Monthly View Navigation
- Month grid cells have NO per-cell accessibility nodes. Do not click coordinates to select dates.
- Tap the day-number cell text to open a specific day. Use the < > header arrows only to change months.
- Always verify the displayed month/date matches the target before reading or creating events.

## Date & Time
- Use the device date context to map relative labels and requested absolute dates.
- "Nh" notation ALWAYS means 24-hour format: "5h" MUST be 05:00, NOT 17:00.
- For date pickers with NumberPicker spinners: use scroll/swipe gestures, NOT type(). NumberPicker widgets reject typed input.
- Before answering a date-specific query, confirm the current view shows the target date.

## Event Management
- Prefer editing event date and time fields directly instead of incrementally scrolling picker widgets.
- After saving an event, reopen or inspect it to verify the title, date, start time, end time, and description.
