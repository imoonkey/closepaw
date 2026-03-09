---
name: de.dennisguse.opentracks
description: App-specific guidance for OpenTracks.
---

# OpenTracks Skill

## Important: Activity Type vs Track Name
Track names are user-provided labels and do NOT reliably indicate the activity type category. A track named "Skill work" could be categorized as "Running". The activity type is stored as separate metadata.

## Efficient Strategy: Use Shell to Query Database
For questions about activity types, categories, distances, or durations, use the `shell` tool to query the SQLite database directly. This is far more efficient than checking tracks one by one through the UI.

```
sqlite3 /data/data/de.dennisguse.opentracks/databases/database.db "SELECT name, category, activity_type, totaldistance, totaltime, datetime(starttime/1000, 'unixepoch', 'localtime') as start FROM tracks ORDER BY starttime DESC"
```

Key columns: `name` (track label), `category` (activity type), `activity_type`, `totaldistance` (meters), `totaltime` (ms), `starttime` (unix ms).

Filter by date and category in SQL for one-turn answers:
```
sqlite3 /data/data/de.dennisguse.opentracks/databases/database.db "SELECT name, category, totaldistance FROM tracks WHERE category='Running' AND date(starttime/1000, 'unixepoch', 'localtime') BETWEEN '2023-10-09' AND '2023-10-15'"
```

## Fallback: UI Navigation
If shell access fails, use the track list UI:
- The track list shows name, date, duration, and distance.
- To check the actual activity type category: open a track → More options → Edit → read the Activity type field.
- Budget ~4 turns per track for this approach.
