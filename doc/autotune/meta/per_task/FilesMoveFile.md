# FilesMoveFile

## R41 (20260312_173354) — gpt-5.4 — PASS — SEARCH-BASED DISAMBIGUATION
- Score: 1.0 | Turns: 20 | GoalAchieved | Duration: 151s
- **Root cause of fix**: Agent used Documents UI search to find exact `holiday_photos.jpg` instead of grabbing decoy `holiday_photos_ZYUM.jpg`. In turn 6, agent explicitly noted the visible file was a "close variant" and searched for the exact filename before selecting. This is the same failure point as R9/R39 — the agent just made a better decision this time.
- **No skill guidance injected** — task uses `com.google.android.documentsui` which has no SKILL.md. The Files skill (`com.google.android.apps.nbu.files`) with swipe/shell-mv fallback was not loaded.
- **Reliability concern**: Fix is non-deterministic model behavior, not systematic. Same model failed R39 by not searching. Consider adding a `com.google.android.documentsui/SKILL.md` with explicit "search for exact filename" guidance to make this reliable.

## R39 (20260312_094821) — gpt-5.4 — FAIL — FILENAME MISMATCH (same as R9)
- Score: 0.0 | Turns: 13 | Error. Agent said "I couldn’t complete the move exactly as requested."
- **Trace-verified (Codex review)**: NOT a drawer navigation failure. Trace shows agent successfully: opened Files → storage roots → Podcasts → scrolled → long-pressed `holiday_photos_ZYUM.jpg` → "Move to…" → navigated to DCIM → clicked MOVE. All actions succeeded.
- **Root cause**: Agent moved `holiday_photos_ZYUM.jpg` instead of `holiday_photos.jpg` — identical to R9’s GroundingError. The target file `holiday_photos.jpg` existed elsewhere; agent picked the wrong file with a randomized prefix. Agent correctly self-reported the mismatch.
- **No prompt fix needed for drawer fallback** — drawer worked fine. The issue is filename matching with randomized prefixes.

## R9 (GPT-5.4, 20260307, full baseline) — FAIL
- Score: 0.0 | Turns: 14 | GroundingError — moved wrong file (holiday_photos_ZYUM.jpg instead of holiday_photos.jpg). Incomplete scroll + no post-verification.
