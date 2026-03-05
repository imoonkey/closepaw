# Shell Tool Usage Analysis: Group 2 & Group 3

## Overview

| Metric | Group 2 | Group 3 |
|--------|---------|---------|
| Tasks involving shell | 4 | 5+ |
| Effective success rate | ~25% | ~10-20% |
| Wasted turns | ~14 | ~40+ |

Overall shell effectiveness: **~15-20%**, with the majority of shell calls being false starts.

---

## What Shell Is Used For

### 1. Markor File Operations (most common, 6 tasks)

- `cat`, `find` for reading/locating files
- `mkdir -p` for creating folders
- Text concatenation, prepend operations

### 2. Image/Binary File Operations (2 tasks)

- `strings`, `hexdump`, `base64` attempting to extract text from images
- All failed — shell cannot substitute for OCR/Vision

### 3. Database/App Storage Access (3 tasks)

- `find ... -name "*.db"` to locate databases
- Failed due to path and permission issues

---

## Detailed Failure Cases

### Group 2

**MarkorCreateFolder (FAIL, 30 turns)**
- Turn 16: `mkdir -p /sdcard/Markor/folder_20260226_215757`
- Created filesystem folder but Markor app doesn't recognize it (not in app's database)
- Shell creates at OS level; Markor requires UI/database registration

**MarkorEditNote (FAIL, 30 turns)**
- Turns 8, 13-14, 17-18, 20, 25: Multiple shell attempts
- Tried: `cat /sdcard/Documents/note_SiFbv.txt` (file not found)
- Tried: `find /sdcard -name "*.txt" | grep -i note`
- Tried: `su -c` with prepend operation (permission denied)
- Wrong path: Markor stores at `/sdcard/Markor/` not `/sdcard/Documents/`
- 14 turns wasted on failed shell attempts

**MarkorAddNoteHeader (FAIL, 26 turns)**
- Turn 5: `cat /sdcard/Documents/2023_08_10_neat_wolf.txt`
- Read file successfully but then replaced content instead of prepending
- Shell read succeeded but editing strategy was wrong

### Group 3

**MarkorMergeNotes (FAIL, 30 turns)**
- Turns 2-30: Repeated shell find/cat commands
- Attempted paths: `/sdcard/Document/`, `/sdcard/Documents/markor/`, various find permutations
- All returned empty despite files visible in Markor UI
- Possible causes: case sensitivity, permissions
- 20+ turns wasted on repetitive shell commands returning empty results

**MarkorTranscribeReceipt (FAIL, 30 turns)**
- Turn 5: `cat /sdcard/DCIM/.receipt_text.txt` (empty)
- Turn 7: `find /sdcard -name "receipt.png"` (empty)
- Turn 10: `cat /sdcard/Pictures/receipt.png` (empty)
- Turn 16: `strings /sdcard/Pictures/receipt.png | head -30` (empty)
- Turn 24: `hexdump -C /sdcard/DCIM/.Receipt/receipt.png` (empty)
- Turn 29: `cat ... | base64` (Permission denied)
- 13 shell attempts, all failed. Task requires vision (OCR), not shell

---

## Five Failure Patterns

### Pattern 1: Incorrect Path Knowledge

- **Evidence:** MarkorEditNote used `/sdcard/Documents/` instead of `/sdcard/Documents/Markor/`
- **Impact:** 14 wasted turns
- **Fix:** Pre-seed app storage paths in context

### Pattern 2: Filesystem vs App Database Mismatch

- **Evidence:** MarkorCreateFolder `mkdir` created directory but Markor didn't recognize it
- **Impact:** Shell approach entirely ineffective for app state changes
- **Fix:** Recognize when shell changes won't update app state; prefer UI

### Pattern 3: Repetitive Failed Commands (No Loop Detection)

- **Evidence:** MarkorMergeNotes repeated identical `find` commands 5+ times, all returned empty
- **Impact:** 20+ wasted turns
- **Fix:** After 3 identical empty results, force strategy pivot

### Pattern 4: Permission/Access Issues

- **Evidence:** `su -c` denied, `base64` read denied
- **Impact:** 3-5 turns per task
- **Fix:** Document available shell permissions; avoid suggesting `su` commands

### Pattern 5: Wrong Tool for the Job

- **Evidence:** Using `strings`/`hexdump`/`base64` on images to extract text
- **Impact:** 13 wasted turns
- **Fix:** Recognize vision tasks require vision input, not shell text tools

---

## Shell Command Effectiveness

### Commands That Work (when path is correct)

- `ls`, `ls -la`, `ls -laR` — directory listings
- `find /sdcard -name "filename"` — file search
- `cat <filepath>` — file reading

### Commands That Fail

- `mkdir -p` — filesystem creation without app database sync
- `su -c` — no root permissions available
- `strings`, `hexdump`, `base64` on binary files — no meaningful output for images
- `find` returning empty — path or permissions issue
- Repeated identical commands — no loop detection

---

## Recommendations

### P0: Pre-seed App Storage Paths

Add to system prompt/tool context:
- Markor: `/sdcard/Documents/Markor/` (capital M)
- Tasks: likely `/data/data/org.tasks/databases/`

Prevents 14+ wasted turns in MarkorEditNote-like tasks.

### P0: Shell Loop Detection

After 3 identical shell commands returning empty/unchanged, force strategy pivot. Add to prompt or agentorch logic:

> "If you've tried the same shell command 3+ times with no results, stop and use a different approach."

Prevents 20+ wasted turns in MarkorMergeNotes-like tasks.

### P1: Early Vision Task Recognition

If task requires reading image/visual content and agent is in accessibility-only mode (no vision), recognize incompatibility and fail early or use vision tool.

Prevents 13 wasted turns in MarkorTranscribeReceipt.

### P1: Strategy Commitment Guidance

Pick one strategy (UI or shell) and commit to it for 3-5 turns. If not working, switch and commit to new strategy. Do not alternate turn-by-turn.

Add scratchpad tracking:
```
Strategy: [UI|shell]
Attempts: N
Result: [success|empty|permission_denied]
```

### P2: Shell-First vs UI-First Decision Matrix

**Prefer shell for:**
- File operations (copy, delete, move) when path is known
- Text manipulation (prepend, append, replace)
- Content reading when path is confirmed

**Prefer UI for:**
- App state changes (folder creation, playlist creation)
- Complex dialogs with multiple steps
- Any operation requiring app database updates

**Never use shell for:**
- Image/visual content extraction
- Operations requiring root permissions
- App-specific data modifications that need database sync

---

## Impact Estimate

These improvements would recover **~50+ wasted turns** across both groups:

| Fix | Turns Saved |
|-----|-------------|
| Pre-seed paths | ~14 |
| Loop detection | ~20 |
| Vision task recognition | ~13 |
| Strategy commitment | ~5-10 |
