# AudioRecorderRecordAudioWithFileName — Failure Analysis

## Scripted Success Criteria

From `audio_recorder.py` lines 114-122:

```python
def is_successful(self, env: interface.AsyncEnv) -> float:
    super().is_successful(env)
    file_name = self.params["file_name"]
    exists = file_utils.check_file_or_folder_exists(
        file_name + ".m4a", self.create_file_task.data_directory, env.controller
    )
    if not exists:
        logging.info("%s not found", file_name)
        return 0.0
    return 1.0
```

The check concatenates `params["file_name"] + ".m4a"` and looks for that file in `AUDIORECORDER_DATA` (`/storage/emulated/0/Android/data/com.dimowner.audiorecorder/files/Music/records`).

`generate_random_params` produces `file_name` by calling `generate_modified_file_name(random.choice(name) + ".m4a")`, which splits on extension, adds a random suffix to the stem, and re-appends `.m4a`. So `params["file_name"]` already includes `.m4a` (e.g., `"presentation_fGwr.m4a"`).

Therefore `is_successful` checks for `"presentation_fGwr.m4a.m4a"` — a **double extension**.

## Task Setup

`initialize_task` delegates to `CreateFile.initialize_task` (which clears device storage) and then calls `_clear_audio_recorder_data` (which clears the Audio Recorder records directory). The device starts with an empty recordings folder.

## Agent's Last Attempt (R25, run 20260309_201619)

Goal: `Record an audio clip and save it with name "presentation_fGwr.m4a" using Audio Recorder app.`

9 turns, 47 seconds, all tool calls succeeded:

| Turn | Action | Detail |
|------|--------|--------|
| 1 | `open_app` | Launched Audio Recorder |
| 2 | `click` | Dismissed welcome screen |
| 3 | `click` | Accepted default setup |
| 4 | `click` | Started recording |
| 5 | `click` | Stopped recording (~3 sec) |
| 6 | `click` | Tapped save — triggered rename dialog (title "New name", field pre-filled with "Record-1") |
| 7 | `type` | Typed `presentation_fGwr` into rename field (`clear: true`). Agent thought: "the app adds .m4a automatically" |
| 8 | `click` | Clicked "Save" button to confirm rename |
| 9 | `complete_task` | Reported success: "Recorded and saved the audio clip as presentation_fGwr.m4a." |

Post-save a11y tree confirms: recording appears as `presentation_fGwr` (display name) with `M4a` format — i.e., file on disk is `presentation_fGwr.m4a`.

## Gap Analysis

**Root cause: the agent's app skill explicitly tells it to strip `.m4a`, but the script expects the double extension.**

1. The goal includes the full param value: `"presentation_fGwr.m4a"`.
2. The app skill at `app/src/main/assets/app_skills/com.dimowner.audiorecorder/SKILL.md` line 16-20 states:
   - "Enter the exact filename specified in the task **(without the .m4a extension — the app adds it automatically)**"
   - "The app auto-appends `.m4a` extension — do NOT include it in the filename"
3. The agent followed this instruction and typed `presentation_fGwr` (without `.m4a`).
4. The Audio Recorder app saved the file as `presentation_fGwr.m4a`.
5. The script checks for `presentation_fGwr.m4a` + `.m4a` = `presentation_fGwr.m4a.m4a` — not found.

**To pass**, the agent must type the full `file_name` param (including `.m4a`) into the rename field. The app then saves it as `presentation_fGwr.m4a.m4a`, which matches the scripted check. The double extension is ugly but is what the script expects.

The current app skill actively prevents the agent from doing this.

## Verdict

**Fixable** — update the app skill to instruct the agent to type the filename exactly as given in the goal, including any extension. Remove the "do NOT include .m4a" guidance. The corrected instruction should be:

> Enter the filename exactly as specified in the task goal, including any extension. The app will append `.m4a` on top — this is expected.

No code changes needed. Skill-only fix in `app/src/main/assets/app_skills/com.dimowner.audiorecorder/SKILL.md`.
