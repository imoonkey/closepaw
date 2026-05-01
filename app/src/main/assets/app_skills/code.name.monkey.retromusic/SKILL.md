---
name: app-retromusic
description: App-specific guidance for Retro Music player.
metadata:
  package: code.name.monkey.retromusic
---

- Playlist detail view shows total duration. Add songs from the Songs tab, not from inside the playlist.
- The "Add to playlist" button on a playlist row adds the PLAYLIST to another playlist, not songs into it.
- If multi-select toolbar doesn't respond, use per-song 3-dot menu → "Add to playlist" as fallback.
- If the goal specifies songs in a particular order, add them in that order — not screen order.
- "Provided songs" or "available songs" means songs in the app's music library (Songs tab).

## Navigation
- Bottom tabs: Home, Songs, Albums, Artists, Playlists.
- Create playlists from Playlists tab, then add songs from Songs tab.

## Safety

**DANGEROUS -- ask user before:**
- Deleting playlists
- Removing songs from existing playlists

**SAFE -- proceed normally:**
- Creating new playlists
- Adding songs to playlists
- Browsing songs, albums, artists, and playing music
