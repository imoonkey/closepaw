---
name: code.name.monkey.retromusic
description: App-specific guidance for Retro Music player.
---

# Retro Music Skill

## Adding Songs to Playlists
- Songs must be added from the Songs tab or a song-level context menu, not from the empty playlist view.
- The playlist's own context menu "Add to playlist" adds the PLAYLIST to another playlist, NOT songs into it.
- To add songs: navigate to Songs tab → long-press a song → select additional songs → use "Add to playlist" from the action bar.

## Navigation
- Bottom tabs: Home, Songs, Albums, Artists, Playlists.
- To create a playlist and add songs: first create the playlist (Playlists tab → + or menu), then navigate to Songs tab to select and add.

## Duration-Constrained Playlists
When creating a playlist with a target total duration (e.g., 45-50 minutes):
1. First, go to the Songs tab. **Song durations are visible on screen but NOT in the accessibility tree.** You must read durations from the screenshot (visual perception).
2. Record all song names and their durations in scratchpad. Scroll through the entire song list.
3. Calculate which combination of songs fits the target duration range.
4. Then select those specific songs and add them to the playlist.
5. Do NOT blindly add songs one by one hoping to hit the target — plan first, then execute.
6. If you can't read durations visually, open each song's details (3-dot menu → Details) to find the duration field.
