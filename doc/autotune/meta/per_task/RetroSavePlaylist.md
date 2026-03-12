# RetroSavePlaylist


## R37 (20260311_232252) — gpt-5.4 — NONE (SCORER BUG)
- Score: None | Error — scorer SQL bug + possible export permission issue. Parked as infra issue.

## R36 (20260311_215019) — gpt-5.4 — NONE
- Score: None | Turns: 30 | GoalAchieved but score=None. Scorer SQL bug + possible export failure

## R9 (GPT-5.4, 20260307, full baseline) — FAIL
- Score: 0.0 | Turns: 20 | ReasoningError — created playlist but got stuck in empty playlist view, never navigated to Songs to add tracks. Regression from R2.

## AT-R2 (20260304_033425) — PASS
- Score: 1.0
- Turns: 30/30 (MaxTurnsReached but scored 1.0 via device state)
- First eval. P1 hint text fix working. Song addition clean. Export flow cost 12 turns with navigation confusion. Never called complete_task — scored by device state. Marginal pass.
