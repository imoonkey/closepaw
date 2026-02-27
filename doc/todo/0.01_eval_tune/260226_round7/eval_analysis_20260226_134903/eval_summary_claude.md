# Round 7 Eval Analysis — Run 20260226_134903

## Summary

**Pass Rate: 16/20 (80%)**
**Average Turns: ~18.6**
**Run Config**: `eval/config/aw_subset_group_1.txt` (20 tasks)
**Commit**: `e2ce450` (round 7 aligned analysis implementation)

## Results Table

| # | Task | Result | Score | Turns | Notes |
|---|------|--------|-------|-------|-------|
| 1 | AudioRecorderRecordAudio | PASS | 1.0 | 7 | Clean execution |
| 2 | AudioRecorderRecordAudioWithFileName | **FAIL** | 0.0 | 30 | Rename doesn't stick |
| 3 | BrowserDraw | **FAIL** | 0.0 | 30 | Canvas drawing limitation |
| 4 | BrowserMaze | PASS | 1.0 | 17 | Chrome setup overhead |
| 5 | CameraTakeVideo | PASS | 1.0 | 7 | Clean execution |
| 6 | ClockStopWatchPausedVerify | PASS | 1.0 | 3 | Optimal |
| 7 | ClockStopWatchRunning | PASS | 1.0 | 4 | Optimal |
| 8 | ContactsNewContactDraft | PASS | 1.0 | 11 | Good execution |
| 9 | ExpenseAddMultipleFromGallery | **FAIL** | 0.0 | 30 | Scratchpad overuse, ran out of turns |
| 10 | ExpenseAddMultipleFromMarkor | **FAIL** | 0.0 | 17 | Wrong data extraction |
| 11 | ExpenseAddMultiple | PASS | 1.0 | 24 | Good execution |
| 12 | ExpenseDeleteDuplicates | PASS | 1.0 | 21 | Delete UI discovery took time |
| 13 | SimpleCalendarAddOneEvent | PASS | 1.0 | 21 | CP2/CP5 tips worked |
| 14 | SimpleCalendarAddOneEventInTwoWeeks | PASS | 1.0 | 22 | Correct date math |
| 15 | SimpleCalendarAddOneEventRelativeDay | PASS | 1.0 | 30 | Passed barely, double-attempt |
| 16 | SimpleCalendarAddOneEventTomorrow | PASS | 1.0 | 29 | Year picker confusion |
| 17 | SimpleCalendarAddRepeatingEvent | PASS | 1.0 | 20 | Most efficient calendar task |
| 18 | SimpleCalendarDeleteEventsOnRelativeDay | PASS | 1.0 | 30 | Over-verification (23 turns on nav) |
| 19 | SimpleCalendarDeleteEvents | PASS | 1.0 | 13 | Efficient |
| 20 | SimpleCalendarDeleteOneEvent | PASS | 1.0 | 18 | Used search effectively |

## CP Impact Assessment

### CP1: Trace Flush — CONFIRMED WORKING
All 20 tasks have complete trace.jsonl files with proper flush. No truncated traces.

### CP2: Time Picker Text Input Mode — HIGH IMPACT
All 6 calendar event creation tasks used text input mode for time pickers. This was the single highest-impact improvement. Tasks where it was observed:
- SimpleCalendarAddOneEvent (turns 12, 16)
- SimpleCalendarAddOneEventInTwoWeeks (turn 15)
- SimpleCalendarAddOneEventRelativeDay (turns 15, 19)
- SimpleCalendarAddOneEventTomorrow (turns 18, 22)
- SimpleCalendarAddRepeatingEvent (turns 7, 16)

### CP3: Loop Detection Tuning — PARTIALLY EFFECTIVE
Loop detection did not visibly trigger for the stuck tasks (AudioRecorderRecordAudioWithFileName looped ~4 times on rename, BrowserDraw looped ~18 times on color selection). The increased MAX_ACTION_HISTORY (5→8) may be too lenient for these patterns.

### CP4: Pre-Completion Verification — MIXED
Some tasks show verification behavior (search after save, view change to confirm), while ExpenseAddMultipleFromMarkor completed without verification and scored 0.0.

### CP5: "Nh" 24-Hour Format — CONFIRMED WORKING
- "13h" → 13:00 (SimpleCalendarAddOneEvent)
- "5h" → 05:00 (SimpleCalendarAddOneEventRelativeDay)
- "14h" → 14:00 (SimpleCalendarAddRepeatingEvent)
- "17h" → 17:00 (SimpleCalendarAddOneEventInTwoWeeks)
- "20h" → 20:00 (SimpleCalendarAddOneEventTomorrow)

### CP6: Calendar Day-Cell Navigation — PARTIALLY EFFECTIVE
Used in some tasks but navigation still confusing. SimpleCalendarDeleteEventsOnRelativeDay spent 23 turns on navigation despite CP6 guidance.

### CP8: ExpenseAddMultipleFromGallery Hybrid Mode — INSUFFICIENT
Hybrid mode was active but the task still failed. The agent could see the image but spent too many turns on scratchpad extraction (17/30 turns).

## Common Failure Patterns

### Pattern 1: Scratchpad Overuse (ExpenseAddMultipleFromGallery)
The agent used scratchpad 17+ times to extract 3 expenses from an image, storing one field at a time. This consumed all available turns. **Fix**: Prompt tip to extract all structured data in a single scratchpad call.

### Pattern 2: Action Loop Without Strategy Change (AudioRecorderRecordAudioWithFileName, BrowserDraw)
The agent retried the same failing action pattern (rename, color selection) without changing approach. Loop detection tuning (CP3) didn't break these patterns. **Fix**: Consider lowering cycleMatchThreshold or adding a strategy-change prompt tip for specific failure modes.

### Pattern 3: Incorrect Data Extraction (ExpenseAddMultipleFromMarkor)
The agent extracted and entered data confidently but got it wrong. The efficient workflow (17 turns) suggests the error was in comprehension, not execution. **Fix**: Stronger CP4 verification — prompt should emphasize reviewing entered data against source.

### Pattern 4: Over-Verification (SimpleCalendarDeleteEventsOnRelativeDay, SimpleCalendarDeleteOneEvent)
Some tasks spent excessive turns navigating to verify work that was already done correctly. This wastes the turn budget. **Fix**: Prompt tip: "After completing the primary action, verify briefly (1-2 turns max) then complete_task."

### Pattern 5: Double-Attempt Pattern (SimpleCalendarAddOneEventRelativeDay, SimpleCalendarDeleteOneEvent)
The agent completed the action, then started doing it again from scratch due to uncertainty. **Fix**: Prompt tip: "Trust your actions. If you saved/deleted successfully, verify rather than redo."

## Proposed Next-Round Changes (P0/P1)

### P0: Critical
1. **Scratchpad efficiency tip**: "When reading structured data from images or text files, extract ALL fields in a single scratchpad call. Use structured format: `name: X, amount: Y, category: Z`."
2. **Pre-completion verification strengthening (CP4 v2)**: Move verification from suggestion to requirement: "BEFORE calling complete_task, verify your work by checking the result (e.g., list view, search, shell check). Maximum 2 verification turns."

### P1: Important
3. **Audio Recorder rename tip**: "When renaming a recording in Audio Recorder, use set_text to replace the entire field. Do NOT include the .m4a extension — the app adds it automatically."
4. **Verification budget tip**: "After completing the main action, spend at most 2 turns verifying. Do not re-navigate or redo the entire task."
5. **Loop detection tuning v2**: Consider reducing cycleMinOccurrences from current value, or adding tool-specific loop detection (e.g., if same scratchpad key is written 3+ times, force strategy change).

### P2: Nice to have
6. **BrowserDraw canvas tip**: "For HTML canvas drawing, use swipe actions on the canvas area. Select a color, then swipe to draw. Repeat for each color." (Low priority — this task is fundamentally limited by a11y mode.)
7. **Chrome setup bypass**: Pre-configure Chrome in eval setup to skip first-run dialogs.
