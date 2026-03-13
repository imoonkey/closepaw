---
name: com.simplemobiletools.calendar.pro
description: App-specific guidance for Simple Calendar Pro.
---

# Simple Calendar Pro Skill

## Monthly View Navigation
- Month grid cells have NO per-cell accessibility nodes. Do not click coordinates to select dates.
- Tap the day-number cell text to open a specific day. Use < > header arrows only to change months.
- Always verify the displayed month/date matches the target before reading or creating events.

## Date & Time
- Use the device date context to map relative labels and requested absolute dates.
- "Nh" notation ALWAYS means 24-hour format: "5h" = 05:00, NOT 17:00.
- For NumberPicker spinners: use scroll/swipe gestures, NOT type().
- Before answering a date-specific query, confirm the current view shows the target date.

## Date Range Queries
For "next week" or similar: compute the exact date range FIRST, write it to scratchpad (e.g., `"range: Oct 16-22"`), then browse. Only include events whose date falls within this range. Once the range is written, do NOT revise it based on visual scrolling.

## Event Management
- Prefer editing date/time fields directly over incrementally scrolling picker widgets.
- After saving an event, reopen or inspect it to verify title, date, start/end time, and description.
