---
name: app-vlc
description: App-specific guidance for VLC media player.
metadata:
  package: org.videolan.vlc
---

## CRITICAL — Use library tabs, not Browse
- For ALL playlist operations, use the Audio or Video library tabs. NEVER use Browse — multi-select does not work there.

## Playlists
- Audio: Audio tab → Playlists → create new.
- Video: Video tab → long-press files to select → overflow → "Add to playlist" → create new when prompted.
- If videos are grouped ("Video group: …, N videos"), ungroup first: overflow → Group videos → Do not group videos.

## Adding Files
- Do NOT use search — search results may auto-play on tap. Scroll the library list instead.
- VLC preserves selection order as playlist track order. If the goal specifies an order, select in that order.
- Multiple playlists: work one at a time. Scroll to the top before starting each new selection.

## Safety

**DANGEROUS -- ask user before:**
- Deleting playlists or media files
- Removing items from existing playlists

**SAFE -- proceed normally:**
- Creating new playlists and adding files
- Playing audio/video
- Browsing the media library
