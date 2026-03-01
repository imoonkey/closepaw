# MarkorTranscribeVideo — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 30 (max) | **Reason**: PerceptionGap + TurnExhaustion | **Duration**: ~570s
**Goal**: Transcribe the contents of video ZwUN_moment_70_.mp4 by watching it in VLC player (located in Download) and writing the sequence of strings shown on each frame to the text file ZwUN_moment_70__transcription.txt in Markor.

## Root Cause
**Category**: PerceptionGap
**Summary**: Agent could only capture 2 names ("Maria", "Charlotte") from the video across 30 turns because the a11y-only perception mode cannot see video frame content. The agent repeatedly tried to seek/pause the video to capture text but accessibility trees don't expose video pixel content. With hybrid mode configured for this task, the agent should have had screenshots — but the behavior suggests the video text wasn't readable even from screenshots, or the model couldn't parse the screenshots well enough.

## Turn-by-Turn Analysis

### Turn 1
- **Action**: `open_app(app_name="VLC")`
- **Result**: Opened VLC
- **Assessment**: Good

### Turn 2
- **Action**: `click(elem=6)` — skip VLC welcome
- **Assessment**: Good

### Turn 3
- **Action**: `click(elem=36)` — click on video to play
- **Assessment**: Good

### Turn 4
- **Action**: `click(elem=2)` — close tips overlay
- **Assessment**: Good

### Turn 5
- **Action**: `click(elem=0)` — tap video to show controls
- **Assessment**: Good

### Turn 6
- **Action**: `scratchpad(write, "Charlotte")` + `click(elem=9)` — pause + record first text
- **Assessment**: Good — captured one text

### Turns 7-30 (Repeated video interaction loop)
- **Actions**: Repeated cycle of: click video → tap to show controls → pause → try to seek → scratchpad → click video again...
- **Key observations**:
  - Turn 7: tap video (no result)
  - Turn 8: click video file again
  - Turn 9-10: tap, pause
  - Turn 11-12: tap, click video file
  - Turn 13: scratchpad — "Maria, Charlotte"
  - Turns 14-30: more tap/seek attempts, never found additional text
- **Assessment**: Agent was fundamentally stuck — cannot perceive video content via a11y tree

### Turn 30
- **Action**: `scratchpad(write, "Maria, Charlotte")` + `open_app("Markor")`
- **Result**: Gave up on video, opened Markor to write what it had
- **Assessment**: Good recovery — but too late (max turns hit before writing to file)

## Failure Points
1. **Cannot read video frame text via a11y** — video content is opaque to accessibility services. This is a fundamental perception limitation.
2. **Only captured 2 names** — the video likely contains many more text sequences
3. **Ran out of turns** — spent 27 turns trying to read video, never wrote to Markor file
4. **Hybrid mode may not have been active** — task override configured hybrid mode but agent config shows `screenshot_attached: false` in trace meta
5. **No fallback strategy** — didn't try `shell` to extract video metadata or frames

## What Worked
- Identified VLC and opened correct video
- Used scratchpad to track found text
- Eventually tried to switch to Markor (but too late)

## What Didn't Work
- A11y perception cannot see video content
- No alternative approach (e.g., shell `ffprobe`, frame extraction)
- 27 turns wasted trying the same unworkable video viewing approach
- Never wrote to the output file

## Suggested Fix
- **This task requires hybrid/screenshot mode to capture video frame text** — verify task_overrides actually enables hybrid for this task
- Add prompt guidance: "For video transcription tasks, pause at regular intervals and describe what you see in the screenshot. If you cannot see text after 3-5 attempts, try shell-based frame extraction: `ffmpeg -i video.mp4 -vf fps=1 frame_%d.png`"
- Add prompt guidance for time management: "If task involves capturing information AND writing it somewhere, use no more than 60% of turns for capture. Switch to writing early to avoid running out of turns."
