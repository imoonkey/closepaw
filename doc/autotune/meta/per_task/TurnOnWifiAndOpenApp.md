

## R40 (20260312_122824) — gpt-5.4 — FAIL
- Score: 0.5 | Turns: 6 | Failed — GoalAchieved. Answer: Wi‑Fi is on and the Settings app is open. (regressed from 1.0)

## R39 (20260312_094821) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 5 | Clean pass in 5 turns. (unchanged)

## R19 (20260309_143418) — gpt-5.4
- Score: 1.0, Turns: 7
- Success: shell commands failed, UI fallback worked. First pass ever.

# TurnOnWifiAndOpenApp

## R17 (gpt-5.4, 20260309_132808)
- Score: 0.5 | Turns: 5 | PerceptionError — opened Settings (0.5) but assumed Wi-Fi was on from "Internet" tile appearance. Status bar showed "No internet".
