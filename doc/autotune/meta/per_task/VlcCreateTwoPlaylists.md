# VlcCreateTwoPlaylists

## R8 (GPT-5.4, 20260307)
Score: 0.0 | Turns: 29 | REGRESSION. VLC app skill NOT injected. Created 1 playlist with 1 file (R7: 1 playlist fully). Multi-action not used by model.

## R7 (run 20260307_025501, qwen3.5)
- **Score**: 0.5 | **Turns**: 30/30
- **Behavior**: TurnExhaustion. Correct one-file-at-a-time workflow. Completed playlist 1 perfectly (4/4 files, turns 1-22). Started playlist 2, created it and added 2/4 files (turns 23-30), then hit max turns. One wasted turn (19) from empty LLM response.
- **Delta vs R6**: Score improved 0.0 -> 0.5. With loop detection warnings removed, the agent no longer derailed — it executed a clean, methodical workflow. The bottleneck is now purely turn budget: 8 files x 3 turns/file + setup = ~31 turns needed vs 30 available.
- **Fix path**: Multi-select app skill (long-press + tap to select multiple files before "Add to playlist") would cut per-file cost from 3 turns to ~1, easily fitting both playlists in 30 turns.

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
