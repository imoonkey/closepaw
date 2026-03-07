# VlcCreateTwoPlaylists

## R6 (run 20260307_003831, gpt-5.4)
- **Score**: 0.0 | **Turns**: 22/30
- **Behavior**: ReasoningError. Used inefficient one-at-a-time approach instead of multi-select; only got first playlist partially done.
- **Delta vs R1**: No hint text contamination, but failed on strategy — didn't reuse the multi-select pattern that worked in VlcCreatePlaylist.

## AT-R1 (20260303_232753) — FAIL
- Score: 0.0
- Turns: 30/30
- FalseCompletion: Same `type` hint text contamination as VlcCreatePlaylist. Both playlist names corrupted: "Playlist nameUltimate Fails Series", "Playlist nameAdventure Marathon". Agent workflow was otherwise correct. VLC infra fix confirmed working.
- See `round_1/20260303_232753/per_task/VlcCreateTwoPlaylists_claude.md` for details.

## R3 (20260301_012237) — INFRA
- Infra error: app_db directory missing
