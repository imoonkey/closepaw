---
name: de.dennisguse.opentracks
description: App-specific guidance for OpenTracks sport tracker.
---

## CRITICAL — Activity type is hidden
- Track names do NOT indicate activity type. A track named "Trail Biking" could be "running" internally.
- The authoritative type is ONLY in: tap track → 3-dot menu → Edit → Activity type field.
- Activity types must match EXACTLY — "biking" is NOT "mountain biking".

## Track Data
- Click a track to see Stats tab: distance, moving time. No need for Edit.
- The track list extends below the visible area. Scroll down repeatedly until content stops changing.

## Date Labels
- Track list uses relative labels (Today, Yesterday, Monday, etc.). Map each to an absolute date using the device date.
- For date-ranged queries: compute the exact date range to scratchpad first, then filter tracks by their absolute dates.

## Counting & Aggregation
- For "how many X activities" or "total distance for X": you MUST check the activity type of each candidate track via Edit before counting or summing. Do not assume from track names.
