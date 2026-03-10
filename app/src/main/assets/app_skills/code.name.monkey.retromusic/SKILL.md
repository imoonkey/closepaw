---
name: code.name.monkey.retromusic
description: App-specific guidance for Retro Music player.
---

# Retro Music Skill

## Adding Songs to Playlists
- **Preferred method (per-song)**: From the Songs tab, tap the 3-dot menu on a song → "Add to playlist" → select the target playlist. Repeat for each song.
- **Multi-select method**: Long-press a song → select additional songs → use "Add to playlist" from the action bar. Note: the toolbar "Add to playlist" button may not be in the accessibility tree. If it doesn't respond, fall back to the per-song method.
- The playlist's own context menu "Add to playlist" adds the PLAYLIST to another playlist, NOT songs into it.

## Navigation
- Bottom tabs: Home, Songs, Albums, Artists, Playlists.
- To create a playlist and add songs: first create the playlist (Playlists tab → + or menu), then navigate to Songs tab to select and add.

## Duration-Constrained Playlists
When creating a playlist with a target total duration (e.g., 45-50 minutes):
1. **Add ALL available songs** to the playlist first. Don't try to read individual durations upfront.
2. Go to the playlist page. The **total playlist duration is shown** on the playlist detail view.
3. If the total duration exceeds the target, remove songs one at a time and recheck the total after each removal until the duration is within the target range.
4. If the total is under the target, you may already have the right set — verify against the goal.
