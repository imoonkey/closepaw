# RetroSavePlaylist — Cog-Tune Analysis

**Score**: 1.0 | **Turns**: 30/30 | **Reason**: Error (agent-side in `20260312_132005`) | **Duration**: 224s

**Goal**: Create a playlist in Retro Music titled `Blues Break 567` with `Chasing Shadows`, `Lost in the Echo`, and `Twilight Calling` in that order, then export the playlist to Downloads.

## Root Cause

**Category**: Success

Compared runs: `20260312_094821` (1.0, 30 turns, 209s, local trace available) and `20260312_132005` (1.0, 30 turns, 224s, only `per_task.jsonl` available locally). There is no new post-R39 device-side regression: both runs satisfy the scorer. The remaining issue is late-turn reasoning/completion policy. After a successful export flow, the agent keeps trying to prove success with shell checks against `/sdcard/Download*`, gets false negatives, and either times out (`20260312_094821`) or self-reports partial failure (`20260312_132005`).

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Retro Music")` | Y | G | G | G | Launches Retro Music from Android Agent. |
| 2 | `write_todos(...)` | N | G | G | G | Tracks create/add/export subtasks. |
| 3-7 | Open Playlists, choose `New playlist`, type `Blues Break 567`, tap `Create` | Y | G | G | G | Turn 7 shows `Blues Break 567 | 0 Songs`. |
| 8 | `write_todos(...)` | N | G | G | G | Marks playlist creation complete. |
| 9-19 | Go to Songs, add `Chasing Shadows`, `Lost in the Echo`, scroll once, add `Twilight Calling` | Y | G | G | G | Turns 10, 13, and 17 target the correct songs visible in the tree. |
| 20-21 | Return to Playlists and open `Blues Break 567` | Y | G | G | G | Turn 21 shows the requested order: `Chasing Shadows`, `Lost in the Echo`, `Twilight Calling`. |
| 22-24 | Open playlist menu, choose `Save as file`, tap `SAVE` in the Downloads picker | Y | G | G | G | Turn 24 pre-tree shows `Downloads`, filename `Blues Break 567`, and `SAVE`. |
| 25 | `shell("ls -l /sdcard/Download")` | N | B | B | G | External verification returns `total 0` and sends the run down the wrong path. |
| 26 | `write_todos(...)` | N | B | G | G | Treats export as still unresolved. |
| 27-28 | Reopen export flow | Y | B | G | G | Redundant retry after a successful save flow. |
| 29 | `shell("ls -l /sdcard/Download | grep 'Blues Break 567.m3u'")` | N | B | B | G | Bad call: the current picker already shows `Blues Break 567.m3u | 268 B | 15:36`. |
| 30 | `remember_experience(...)` | N | B | G | G | Uses the final turn on memory instead of `complete_task`. |

### Key Turns

**Turn 24** — Export action succeeded from the UI's perspective:
- **What was on screen**: DocumentsUI was rooted at `Downloads`, with the filename field populated as `Blues Break 567` and a visible `SAVE` button.
- **What agent decided**: Press `SAVE` to export the playlist.
- **What action was taken**: `mobile_action` click on the save button.
- **What actually happened**: The next turn is back in Retro Music, so the save flow exited normally; later, turn 29 shows `Blues Break 567.m3u` listed in the same Downloads picker.
- **What should have happened**: Once the file later appears in the picker, the agent should treat export as verified and finish.
- **Perception check**: No gap. The filename field and save controls are present in the a11y tree.

**Turn 29** — Visible success was ignored:
- **What was on screen**: The Downloads picker lists `Blues Break 567.m3u | 268 B | 15:36` and still shows the filename field plus `SAVE`.
- **What agent decided**: Run a shell grep against `/sdcard/Download` instead of trusting the visible file row.
- **What action was taken**: `shell("ls -l /sdcard/Download | grep 'Blues Break 567.m3u'")`, which exits 1.
- **What actually happened**: No UI change; the agent discarded stronger on-screen evidence in favor of a brittle filesystem probe.
- **What should have happened**: Conclude the export succeeded, or at minimum treat the picker evidence as stronger than the shell false negative.
- **Perception check**: No gap. The `.m3u` row is clearly present in the sanitized tree.

## What Worked

- Playlist creation and song selection are solid; the agent reaches the right tabs and targets the requested songs without misclicks.
- Turn 21 verifies the exact requested order inside the playlist.
- The export interaction itself works: the agent reaches DocumentsUI, saves to Downloads, and later sees the exported `.m3u` file in the picker.

## What Didn't Work

- The endgame still over-trusts shell verification and under-trusts app/UI evidence.
- `20260312_132005` does not show a new navigation or action regression; it repeats the same verification pattern and only changes the ending from `MaxTurnsReached` to an explicit partial-success `Error`.
- The agent never converts visible export confirmation into `complete_task`, so both recent runs spend all 30 turns despite already satisfying the scorer.

## Recommendations

- Teach the Retro export skill/prompt that DocumentsUI evidence is sufficient: if the picker is on `Downloads` and shows the target filename or an existing `.m3u` row, mark export verified and complete.
- Stop using `ls /sdcard/Download*` as the primary proof for DocumentsUI-backed exports; if shell verification remains, treat it as secondary and non-blocking.
- Add a late-turn completion guardrail: when the goal state is already visible on-screen, prefer `complete_task` over extra shell or memory calls.
