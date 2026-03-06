# BrowserDraw - Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 28/30 | **Reason**: Error | **Duration**: 145s

**Goal**: Open `task.html` from Downloads in Files, open it with Chrome, create a drawing using the three colors shown at the top, and submit it.

## Root Cause

**Category**: InfraError

This run never reached the drawing page. The agent successfully found `task.html`, used `Open with`, and selected Chrome, but Chrome first-run remained uncleared and hybrid screenshots were polluted by Android developer touch overlays (`pointer_location=1`, `show_touches=1`), which visually covered every screenshot in the run. That makes this result primarily an eval-environment failure, not evidence about BrowserDraw's historical canvas-perception ceiling.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-10 | Files navigation, `Open with`, select Chrome | Y | G | G | G | Correctly reached Chrome first-run blocker |
| 11-14 | Repeated attempts to dismiss Chrome first-run | N | G | G | B | `Use without an account` was visible, but successful clicks did not advance the screen |
| 15-21 | Return to Files, retry list/grid/open flows | Y | B | G | G | Agent started attributing failure to an overlay instead of isolating the actual blocker |
| 22-27 | Search and coordinate retries in Files | N | B | B | G | Burned turns on retries after the environment was already suspect |
| 28 | `complete_task(failure)` | N | G | G | G | Correctly stopped and reported blockage |

### Key Turns

**Turns 11-14** - Chrome showed `Use without an account`, and the tool reported successful clicks, but the screen stayed on the same first-run page. That is consistent with environment/setup breakage rather than reasoning failure.

**Environment evidence** - During this run, the emulator had `settings get system pointer_location -> 1` and `show_touches -> 1`. The captured screenshots visibly contained the developer pointer overlay (`P:`, `dx`, `dy`, `Press`, `Size`) across the top, polluting every hybrid screenshot.

## What Worked

- The agent found the target file and the `Open with -> Chrome` path.
- It did not false-complete; it eventually surfaced the blockage explicitly.

## What Didn't Work

- Chrome snapshot/setup was not ready for Browser tasks.
- Hybrid screenshots were contaminated by developer overlays, which confounded the model.
- The agent spent too many turns retrying after the run had already become suspect.

## Recommendations

- Do not treat this run as signal about BrowserDraw drawing ability.
- Fix eval hygiene first: disable `pointer_location` and `show_touches` before snapshot capture and ensure Chrome first-run is cleared in the Browser baseline.
