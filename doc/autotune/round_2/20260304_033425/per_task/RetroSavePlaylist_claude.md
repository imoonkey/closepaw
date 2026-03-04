# RetroSavePlaylist — Cog-Tune Analysis

**Score**: 1.0 | **Turns**: 30/30 | **Reason**: MaxTurnsReached (but scored 1.0)

**Goal**: Create playlist "Blues Break 567" with 3 songs, then export to Downloads directory.

## Root Cause

**Category**: Success (marginal — hit turn limit but device state scored correct)

Agent created playlist and added songs (18 turns), then spent 12 turns on export. Export involved navigation confusion and a file picker interaction. Scored 1.0 because the file was saved at turn 26 (SAVE click) even though shell verification in turns 27-28 showed empty Downloads dir (likely sync delay).

## What Worked

- **P1 hint text fix**: Playlist name handled correctly
- **P5 Retro workflow**: Song addition followed correct pattern
- Shell verification attempt shows good instincts (even if it gave false negative due to sync)
- Agent did NOT false-complete when shell showed empty Downloads

## What Didn't Work

- Export flow cost 12 turns — inefficient due to navigation confusion (turns 20-21)
- Needed scrolling to find "Twilight Calling", adding extra turns
- Hit MaxTurnsReached — would have failed on verification without the scorer's device-state check
- Never called `complete_task` (only task in round that didn't)
