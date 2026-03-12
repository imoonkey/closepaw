# VlcCreatePlaylist

## R40 (20260312_122824) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 13 | Clean pass in 13 turns. (unchanged)

## R39 (20260312_094821) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 15 | Clean pass in 15 turns. (unchanged)

## R7 (run 20260307_025501, qwen3.5) — PASS
- **Score**: 1.0 | **Turns**: 20/30
- **Behavior**: Success. Canary pass sustained. Same long-press > add-to-playlist workflow. No hint text contamination.
- **Delta vs R6**: Turns 14→20. Core workflow identical (turns 1-14). Extra turns from verification navigation overhead (5 turns to navigate to Playlists tab and verify) plus 1 grounding error (stale element index on turn 15, recovered). Normal variance for qwen3.5 vs gpt-5.4.
- See `round_7/20260307_025501/per_task/VlcCreatePlaylist_claude.md` for details.

## R6 (run 20260307_003831, gpt-5.4) — PASS
- **Score**: 1.0 | **Turns**: 14/30
- **Behavior**: Success. FIRST-EVER PASS. Used multi-select + overflow menu + verify pattern.
- **Delta vs R1**: No hint text contamination (gpt-5.4), correct playlist name. Clean workflow.

## AT-R1 (20260303_232753) — FAIL
- Score: 0.0
- Turns: 19/30
- FalseCompletion: Agent executed correct VLC workflow (browse files, select, create playlist) but `type` action concatenated hint text with playlist name: "Playlist nameUltimate Fails Series". Scorer exact match failed. VLC infra fix confirmed working (no crash).
- See `round_1/20260303_232753/per_task/VlcCreatePlaylist_claude.md` for details.

## R3 (20260301_012237) — INFRA
- Infra error: app_db directory missing
