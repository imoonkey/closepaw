# BrowserDraw

**Status: cannot_handle** — Color perception limitation in hybrid mode. Agent reaches Chrome but can't match palette colors to target colors. 3 consecutive failures R22-R24 with different root causes each round (nav trap → overlay occlusion → color perception). Re-parked R25.

## R24 (20260309_194958) — gpt-5.4 — RE-PARKED
- Score: 0.0 | Turns: 29 | ColorPerception — reached Chrome, drew on canvas, submitted. Wrong colors — gpt-5.4 can't reliably match palette colors. 3rd consecutive failure.

## R23 (20260309_192810) — gpt-5.4
- Score: 0.0 | Turns: 28 | InfraOcclusion — Files nav fixed, reached Chrome. Agent UI overlay covered canvas + target colors. Couldn't see what to draw.

## R22 (20260309_190216) — gpt-5.4
- Score: 0.0 | Turns: 30 | NavigationTrap — spent all turns in Files app clicking hamburger zone (x<150). Never reached Chrome. Chrome skill + hybrid perception added.

## AT-R5-GPT54 (20260306_003753) — FAIL
- Score: 0.0
- Turns: 28/30
- InfraError: Task never reached the drawing page. Chrome first-run stayed blocked, and the emulator had `pointer_location=1` plus `show_touches=1`, polluting all hybrid screenshots with the Android developer touch overlay.
- See `round_5/20260306_003753/per_task/BrowserDraw_codex.md` for details.
