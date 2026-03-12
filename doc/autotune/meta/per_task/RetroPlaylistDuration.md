# RetroPlaylistDuration

**Status: FIXED R33** — Per-song 3-dot menu "Add to playlist" + add-all-then-prune strategy.


## R37 (20260311_232252) — gpt-5.4 — NONE (ACCEPTED REGRESSION)
- Score: None | Error — same perception gap. Agent can't find song durations in a11y tree. Removed duration-constrained playlist algorithm was eval-specific. Accepted regression.

## R36 (20260311_215019) — gpt-5.4 — NONE
- Score: None | Turns: 9 | Error — agent blocked, can't find song durations in a11y tree. Scorer SQL bug too

## R33 (20260310_004519) — gpt-5.4 — FIXED
- Score: 1.0 | Turns: 57 | Fix: per-song context menu "Add to playlist" as primary method bypasses a11y gap in multi-select toolbar.

## R32 (20260310_001826) — gpt-5.4
- Score: 0.0 | Turns: 60/60 | A11yGap — "Add to playlist" in multi-select toolbar not in a11y tree. Agent tried coordinate taps at wrong y-position (status bar y=91 vs toolbar y=211). Created playlist but 0 songs added. New strategy: use per-song 3-dot menu instead.

## R26 (20260309_203139) — gpt-5.4 — RE-PARKED
- Score: 0.0 | Turns: 8 | PerceptionGap — gave up at turn 8/50. Can't read durations from a11y OR screenshot. Hybrid didn't help. 3rd consecutive failure.

## R25 (20260309_201619) — gpt-5.4
- Score: 0.0 | Turns: 19 | PerceptionGap — found one song = 00:49 via details dialog. Gave up saying songs too short. Misunderstood short songs can combine.

## R24 (20260309_194958) — gpt-5.4
- Score: 0.0 | Turns: 24 | TurnExhaustion — created playlist, found 2 durations via details. Hit Error at turn 24. Hybrid perception + duration strategy skill added.

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 45/45 | **Agent**: qwen3.5
- **Behavior**: TurnExhaustion (7th consecutive 0.0). Agent attempted batch-select but hit duplicate "More options" grounding error. Only 2 songs added (06:37 vs 45-50min target).
- **R4 fix result**: batch-select tip + max_turns=45 applied. Tip partially followed but model can't execute. Recommend park as qwen3.5-blocked.

## AT-R3 (20260304_152742) - FAIL
- Score: 0.0
- Turns: 30/30
- Root cause: TurnExhaustion. R2 strategy tip present but agent wasted 12 turns pre-reading durations before pivoting to iterative add at turn 19. Only ~5 songs added. 3-round persistent failure.
- R4 fix: Replace old strategy tip with batch-select app tip (long-press multi-select on Songs tab). Set max_turns=45.

## AT-R3 (20260304_152742) — FAIL
- Score: 0.0
- Turns: 30/30
- TurnExhaustion/ReasoningError: P2 duration strategy tip present in prompt but agent still wasted 12 turns pre-reading durations before pivoting to iterative add at turn 19. Only ~5 songs added. Never checked playlist running total. 3-round persistent failure. May be qwen3.5 model limitation.

## AT-R2 (20260304_033425) — FAIL
- Score: 0.0
- Turns: 30/30
- TurnExhaustion: Agent pre-read individual song durations via Details dialog (~3 turns each) instead of iterative add-and-check. Added only 2 songs, self-reported failure. 6 wasted turns (shell commands, tab bouncing). P5 Retro tips helped with song addition pattern but duration strategy guidance was insufficient.
- See `round_2/20260304_033425/per_task/RetroPlaylistDuration_claude.md` for details.

## AT-R1 (20260303_232753) — FAIL
- Score: 0.0
- Turns: 30/30
- NavigationFailure: Agent created playlist successfully but spent 11 turns discovering add-to-playlist workflow. Added only 3 songs. Couldn't see song durations (not in a11y tree). Self-reported failure.
- See `round_1/20260303_232753/per_task/RetroPlaylistDuration_claude.md` for details.

## R3 (20260301_012237) — FAIL
- Score: 0.0
- Root cause: Song list missing + workflow
