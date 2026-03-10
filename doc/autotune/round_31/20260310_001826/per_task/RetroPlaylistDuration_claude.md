# RetroPlaylistDuration — R32 Trace Analysis

**Score**: 0.0 | **Turns**: 60/60 (exhausted) | **Stop reason**: Error (ran out of turns)

## Goal

Create a playlist titled "Reggae Rhythms 51" with total duration between 45 and 50 minutes using provided songs.

## Evaluator Contract

- `is_successful()` queries the playlist DB for songs in the named playlist, sums `duration_ms`, and checks `45*60*1000 <= total <= 50*60*1000`.
- Task provides 9-11 "target" songs whose durations sum to exactly 47.5 minutes, plus 4-6 "noise" songs (3-5 min each).
- Adding ALL target songs guarantees success; adding noise songs would overshoot.

## What the Agent Did

| Phase | Turns | Outcome |
|-------|-------|---------|
| Open app, navigate to Playlists | 1-5 | OK |
| Write todos | 6 | OK |
| Name playlist, tap Create | 7-8 | OK — playlist "Reggae Rhythms 51" created |
| Navigate to Songs tab | 9 | OK |
| Long-press first song to enter selection mode | 10 | OK |
| Try to tap "Add to playlist" icon via coordinates | 11-48 | **STUCK** — 38 turns wasted |
| Lose selection mode, navigate back, re-select | 49-53 | Partial retry |
| Same coordinate-tap loop again | 54-59 | Still stuck |
| Give up, complete_task(failure) | 60 | Failure |

## Root Cause: "Add to playlist" button not in a11y tree

When the agent enters multi-select mode on the Songs tab, the action bar shows these accessible elements:

- `[6] Navigate up / Done` — exits selection
- `[10] Play next` — button
- `[11] Add to playing queue` — button
- `[12/13] More options` — overflow menu

There is **no "Add to playlist" button** exposed in the accessibility tree. The agent saw what it interpreted as an "add to playlist" icon in the screenshot and spent ~40 turns tapping various coordinates (x=370-906, y=88-130) trying to hit it. Every tap either:
- Hit the wrong target (triggered "More options" which showed Grid size/Style/Sort menus)
- Fell on the agent's own floating capsule overlay
- Produced "No observable screen change detected"

The "More options" overflow in selection mode leads to the **song list view settings** (Grid size, Grid style, Sort order, Settings), NOT to "Add to playlist". This means the Retro Music selection-mode toolbar simply does not have "Add to playlist" as either a direct button or an overflow item exposed to accessibility.

### Secondary Issue: Agent capsule overlay obstruction

The agent's own floating UI capsule covered parts of the screen, consuming additional turns as the agent repeatedly tried to minimize it before retapping.

## What Should Have Worked

The app skill already documents the correct flow:
> Songs tab -> long-press a song -> select additional songs -> use "Add to playlist" from the action bar.

The problem is that "Add to playlist" is **visually present** in the toolbar but **not exposed in the accessibility tree**. The agent correctly followed the skill guidance but hit an a11y tree gap.

### Possible workarounds

1. **Per-song context menu**: Instead of multi-select, use the 3-dot menu on each individual song row. If that menu exposes "Add to playlist", the agent can add songs one at a time.
2. **Coordinate-based click on the actual icon**: The agent tried many coordinates but may have been hitting the wrong element. A precise coordinate from the screenshot (the icon between "Add to playing queue" and "More options") might work — but this is fragile.
3. **Shell-based approach**: Use `adb shell` + content provider or media store commands to insert songs into the playlist DB directly (risky, may not be possible without root).

## Proposed Fix

1. **Investigate per-song context menu**: Check if individual song 3-dot menus expose "Add to playlist". If yes, update the app skill to prefer this path for reliability, even though it costs more turns.
2. **Update app skill with fallback**: Add guidance that if the selection-mode "Add to playlist" icon is not in the a11y tree, the agent should use coordinate-tap on the icon between "Add to playing queue" and "More options" at approximately `(x=850, y=211)` based on the bounds observed — or fall back to per-song context menus.
3. **Fix the coordinate targeting**: The agent was tapping y=91 (status bar area) instead of y=211 (actual toolbar row). The a11y tree shows toolbar buttons at y=211-212. The agent confused the toolbar row with a different screen area. Adding explicit coordinate guidance to the skill could fix this.

### Most Likely Quick Win

The toolbar buttons "Play next" and "Add to playing queue" are at `y=212`. The "Add to playlist" icon, if it exists visually, should also be at `y~212`. The agent was tapping `y=91` (status bar) — **completely wrong row**. Updating the skill to say "the selection toolbar is at y~210, not y~90" or instructing the agent to use coordinates relative to known toolbar elements could resolve this immediately.
