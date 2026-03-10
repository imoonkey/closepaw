# MarkorDeleteNewestNote — Failure Analysis

## Scripted Success Criteria

From `.reference/eval/android_world/android_world/task_evals/single/markor.py`, lines 348-368:

```python
def is_successful(self, env: interface.AsyncEnv) -> float:
    super().is_successful(env)
    new_file_list = file_utils.get_file_list_with_metadata(
        device_constants.MARKOR_DATA, env.controller
    )
    new_file_list_sorted = sorted(new_file_list, key=lambda f: f.change_time)
    for i in range(len(new_file_list)):
        # Both file lists are ordered by file change time, so by simply checking
        # file names and their change time are the same, we can ensure all other
        # files have not been changed.
        if not (
            new_file_list_sorted[i].file_name
            == self.initial_file_list_sorted[i].file_name
            and new_file_list_sorted[i].change_time
            == self.initial_file_list_sorted[i].change_time
        ):
            return 0.0
    one_fewer_file = (
        len(new_file_list_sorted) == len(self.initial_file_list_sorted) - 1
    )
    return 1.0 if one_fewer_file else 0.0
```

Two conditions must BOTH hold:
1. **File count**: `len(new) == len(initial) - 1` (exactly one file deleted).
2. **Remaining files unchanged**: For every remaining file at index `i`, both `file_name` and `change_time` must match `initial_file_list_sorted[i]` exactly. The lists are sorted by `change_time`, so the deleted file must be the one with the HIGHEST `change_time` (last in sorted order), and all other files must have identical `change_time` values as before.

The second condition is strict: if ANY remaining file's `change_time` has shifted by even a second, the evaluator returns 0.0.

## Task Setup

From `initialize_task` (lines 324-346):
- The Markor data directory is first cleared (via parent `Markor.initialize_task`).
- 2-6 random notes are created with `file_utils.create_file`.
- Between each creation, system time is advanced by `random.randint(-500, 500)` minutes using `datetime_utils.advance_system_time`. This makes each file's `change_time` differ, sometimes non-monotonically (time can go backward).
- The initial file list is captured with metadata and sorted by `change_time`.
- "Newest" = the file with the highest `change_time` = the last entry in `initial_file_list_sorted`.

In this specific run (seed 1958615684), two notes were created:
- `final_friendly_king.md` — shown in Markor date-sorted view with timestamp `10/15/2023, 15:17`
- `fine_jelly_final.md` — shown in Markor date-sorted view with timestamp `10/15/2023, 15:34`

The newest note per filesystem `change_time` is `fine_jelly_final.md` (15:34 > 15:17).

## Agent's Last Attempt (R24, run 20260309_194958)

7 turns, 44.5 seconds, agent claimed GoalAchieved.

| Turn | Action | Detail |
|------|--------|--------|
| 1 | `open_app` | Opened Markor |
| 2 | `click` index 7 | Tapped "Sort by" button |
| 3 | `click` index 8 | Selected "Date" sort option |
| 4 | `long_press` index 14 | Long-pressed `fine_jelly_final.md` (top file after date sort = newest) |
| 5 | `click` index 9 | Tapped "Delete" in the action bar |
| 6 | `click` index 8 | Confirmed deletion in "Confirm Delete" dialog |
| 7 | `complete_task` | Reported success: "Deleted the newest note in Markor." |

The agent correctly identified the newest file by:
1. Sorting by date in Markor's UI
2. Selecting the first file in the date-sorted list (`fine_jelly_final.md` at 15:34)
3. Deleting it via long-press > Delete > Confirm

Post-deletion, only `final_friendly_king.md` remained — the correct file to keep.

**Result**: `scripted_score: 0.0` despite the agent visibly deleting the correct file.

## Gap Analysis

The agent's behavioral outcome (deleted `fine_jelly_final.md`, kept `final_friendly_king.md`) appears correct. The failure is in the evaluator's strict `change_time` equality check.

### Root cause: `change_time` mutation during deletion

The evaluator compares `change_time` values before and after. Two mechanisms can cause `change_time` to shift on surviving files:

1. **Markor's internal file operations**: When Markor deletes a file through its UI, it may touch or re-index sibling files, updating their `ctime`/`mtime`. Markor's file manager layer could trigger metadata updates on the parent directory or sibling files.

2. **System time was advanced during setup**: The `initialize_task` method calls `datetime_utils.advance_system_time` with random offsets (up to +/- 500 minutes). The system clock at evaluation time may differ from the system clock at setup time. If the evaluator reads `change_time` using the current (shifted) system clock's `stat()` results, or if any filesystem operation during the agent's interaction causes a `ctime` update on remaining files, the timestamps will not match.

3. **`adb stat` vs filesystem resolution**: The `change_time` comparison uses exact equality. Even sub-second differences from filesystem journaling or metadata refresh could cause a mismatch.

### Evidence from trace

The Markor UI shows a suspicious timestamp shift after deletion:
- Before deletion: `final_friendly_king.md` shows `10/15/2023, 15:17`
- After deletion (tree 98): `final_friendly_king.md` shows `10/15/2023, 15:34`

This display change suggests Markor's UI may be showing a cached/wrong value, OR the file's metadata was actually touched during the deletion of the sibling file. If the filesystem `change_time` of `final_friendly_king.md` shifted from 15:17 to 15:34, the evaluator's index-based comparison at position 0 would fail: it expects `(final_friendly_king.md, 15:17)` but finds `(final_friendly_king.md, 15:34)`.

However, it is also possible the Markor display is misleading and the actual filesystem timestamps are fine, but something else is off (e.g., `get_file_list_with_metadata` includes the `.app` folder which may have a different `change_time` post-deletion).

## Verdict

**Likely fixable, but the fix is NOT in the agent — it is in the evaluator or task design.**

The agent's behavior is correct: it identified the newest note by date, deleted it, and only it. The failure is caused by one of:

1. **Timestamp mutation side-effect**: The act of deleting a file in Markor mutates the `change_time` of surviving files (or the parent directory entries picked up by `get_file_list_with_metadata`). This is a filesystem/app-level side-effect outside the agent's control.

2. **System time drift**: The setup's `advance_system_time` shifts the clock, and the evaluator reads timestamps at a different system time than when they were written.

**Agent-side mitigation options**:
- Use `shell` tool with `rm` to delete the file directly on the filesystem instead of through Markor's UI. This avoids any UI-triggered side-effects on sibling files. However, the agent would still need to determine which file is newest (could use `ls -lt` or `stat`).
- Use `shell` tool to `stat` files first, identify the newest, then `rm` it. This bypasses Markor entirely and avoids any timestamp mutation from UI interactions.

**Risk**: The shell-based approach depends on the agent having write access to Markor's data directory, which it should since it runs within the same user context.

**Classification**: Structural mismatch between evaluator assumptions (no side-effects on surviving files) and real Markor/filesystem behavior. Fixable via shell-based deletion strategy in the app skill.
