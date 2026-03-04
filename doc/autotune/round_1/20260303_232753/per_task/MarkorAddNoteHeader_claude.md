# MarkorAddNoteHeader -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 30/30 | **Reason**: MaxTurnsReached | **Duration**: 296s

**Goal**: Update the Markor note `2023_08_10_neat_wolf.txt` by adding the text "RnI8sP34yDzJQbvkfplR" with a new blank line before the existing content, and rename it to `busy_wolf_2023_07_23.txt`.

## Root Cause
**Category**: Text editing failure / Content format mismatch + Select-all loop

The agent successfully opened the file, typed the header text, renamed the file, but placed the new text incorrectly (appended on the same line as existing content instead of as a proper header with a blank line separator). Upon re-opening the file to fix the content, the agent entered a 15+ turn loop trying to select-all the text and replace it, repeatedly getting stuck on context menus and unable to actually perform the replacement. The core issue is twofold: (1) the initial type action did not correctly position text as "header + blank line + existing content", and (2) the agent lacked a reliable strategy for full-text replacement in Markor's editor.

## Turn-by-Turn Analysis

### Phase 1: Open and Navigate (Turns 1-4)
| Turn | Action | Result |
|------|--------|--------|
| 1 | `open_app("Markor")` | Markor opens, file list visible |
| 2 | `click(element_index=16)` - open `2023_08_10_neat_wolf.txt` | File opens showing "Weekend plans: Hiking trip to Blue Mountain." |
| 3 | `click(element_index=14)` - click EditText to position cursor | Cursor placed in text area |
| 4 | `click(element_index=14)` - click beginning of EditText | Cursor at start |

Good: Efficient navigation to the target file in 4 turns.

### Phase 2: Type Header Text (Turn 5)
| Turn | Action | Result |
|------|--------|--------|
| 5 | `type("RnI8sP34yDzJQbvkfplR\n", element_index=14)` | Text typed into the editor |

**Critical Issue**: The text was appended at what the agent believed was the cursor start, but the result shows the content as "Weekend plans: Hiking trip to Blue Mountain.\nRnI8sP34yDzJQbvkfplR" -- the new text ended up on the second line AFTER existing content rather than BEFORE it with a blank line. The `\n` was appended after the new text, not before the existing text, indicating the cursor was not actually at position 0 of the document. Additionally, the task required a blank line between the header and existing content, meaning the correct input should have been `"RnI8sP34yDzJQbvkfplR\n\n"` prepended before existing text.

### Phase 3: Navigate Back and Rename (Turns 6-13)
| Turn | Action | Result |
|------|--------|--------|
| 6 | `click(element_index=6)` - navigate up button | Attempts to go back |
| 7 | `system_button("back")` | Returns to file list |
| 8 | `click(element_index=10)` - save | Saves the file |
| 9 | `system_button("back")` | Navigates back to file list |
| 10 | `long_press(element_index=16)` - long press file | Selection mode activates |
| 11 | `click(element_index=8)` - click Rename | Rename dialog opens |
| 12 | `type("busy_wolf_2023_07_23.txt", clear=true)` | New filename entered |
| 13 | `click(element_index=9)` - click OK | Rename confirmed |

Good: Rename completed successfully. The file list shows `busy_wolf_2023_07_23.txt`.

### Phase 4: Re-open and Discover Content Issue (Turns 14-16)
| Turn | Action | Result |
|------|--------|--------|
| 14 | `click(element_index=19)` - open renamed file | File opens, shows "Weekend plans: Hiking trip to Blue Mountain.\nRnI8sP34yDzJQbvkfplR" |
| 15 | `click(element_index=14)` - click EditText to fix content | Tries to reposition cursor |
| 16 | `shell("cat /sdcard/Documents/Markor/busy_wolf_2023_07_23.txt")` | **Used shell command** despite R1 prompt tip |

**Key observation**: The agent noticed the content order was wrong. The text "RnI8sP34yDzJQbvkfplR" appeared AFTER "Weekend plans..." instead of before it. It attempted to use a shell command to read the file state, violating the prompt guidance.

### Phase 5: Select-All Loop (Turns 17-30) -- CRITICAL FAILURE
| Turn | Action | Result |
|------|--------|--------|
| 17 | `long_press(element_index=14)` | Context menu appears (Cut/Copy/Paste/Share) |
| 18 | `click(element_index=16)` - "Select all" | All text selected |
| 19 | `click(element_index=19)` - click EditText | Tries to dismiss context menu |
| 20 | `click(element_index=19)` - click EditText again | Still stuck with context menu / selection |
| 21 | `click(x=50, y=400)` - coordinate click | Tries to dismiss context menu |
| 22 | `click(element_index=6)` - title bar | Tries to dismiss context menu |
| 23 | `click(x=10, y=300)` - coordinate click | Tries again |
| 24 | `click(element_index=19)` - EditText | Loop continues |
| 25 | `system_button("back")` | Tries system back to dismiss |
| 26 | `click(x=10, y=285)` | Tries coordinate-based click again |
| 27 | `click(element_index=22)` - "Special Key" | Opens Special Key dialog (Page Up, Document Start, etc.) |
| 28 | `click(element_index=14)` - "Document Start" | Moves cursor to document start |
| 29 | `click(element_index=22)` - "Special Key" again | Opens Special Key dialog |
| 30 | `click(element_index=16)` - "Select all" from Special Key | Selects all text -- but turn limit reached |

The agent spent **14 turns (17-30)** trying to select-all text, dismiss context menus, and reposition the cursor. It never reached the point of actually typing the replacement text. The strategy repeatedly alternated between:
- Long press -> Select all -> stuck on context menu -> try to dismiss
- Try Special Key menu -> Document Start -> repeat

## What Worked
1. **App navigation**: Turns 1-4 efficiently opened Markor and navigated to the target file.
2. **File rename**: Turns 10-13 successfully renamed the file to `busy_wolf_2023_07_23.txt` via long-press > Rename dialog.
3. **No shell for file write**: The agent did not attempt shell-based file modification (a known R0 anti-pattern). It correctly used Markor's UI for editing.
4. **Special Key discovery**: In turns 27-28, the agent found Markor's Special Key menu with "Document Start" -- a useful capability for future tasks.

## What Didn't Work
1. **Cursor positioning before typing**: The agent assumed clicking the EditText would place the cursor at position 0, but it actually placed it at the end or after existing text. The typed header text ended up AFTER the existing content instead of before it.
2. **Missing blank line**: The type action used `"RnI8sP34yDzJQbvkfplR\n"` but the task required a blank line between header and content, meaning it needed `\n\n` separation.
3. **No strategy for full-text replacement**: After discovering the content was wrong, the agent had no effective approach to select-all and retype. It looped between context menu interactions for 14 turns without ever reaching a type action.
4. **Context menu dismissal failure**: The agent could not reliably dismiss the Cut/Copy/Paste context menu that appeared after text selection. It tried clicking elsewhere, system back, coordinate clicks -- none consistently worked.
5. **Shell command still used (turn 16)**: Despite the R1 prompt tip "Do NOT use shell commands for Markor file operations", the agent used `cat` to read the file. The tip was partially effective (no shell writes) but did not fully prevent shell usage.
6. **No undo strategy**: The agent never considered using the Undo button visible in Markor's toolbar to revert the incorrect edit and retry from a clean state.

## Recommendations

### P0 - Critical
1. **Teach "Document Start" before typing at beginning**: Add a prompt pattern: "To type text at the beginning of a Markor document, first use Special Key > Document Start to ensure cursor is at position 0, then type." The agent discovered this in turn 28 but too late and never used it effectively.
2. **Add select-all + type replacement pattern**: Provide explicit guidance: "To replace all text in an editor: (1) use Special Key > Select All or Ctrl+A, (2) immediately type replacement text -- this replaces the selection. Do NOT try to dismiss the context menu first."
3. **Fix blank line handling in type actions**: The system should make it clearer that `\n\n` produces a blank line separator in Markor. The agent should compose the full desired content (header + `\n\n` + existing) and type it in one shot.

### P1 - Important
4. **Add Undo as a recovery strategy**: When the agent detects content is wrong after an edit, it should use the Undo button (visible in toolbar) before re-attempting, rather than entering a complex select-all-replace loop.
5. **Strengthen shell command prohibition**: The cat command on turn 16 shows the prompt tip was not strong enough. Consider making the prohibition more prominent or adding it as a system-level constraint.
6. **Anti-loop: detect context menu thrashing**: The agent spent 8+ turns clicking to dismiss a context menu without progress. The loop detector should recognize "clicking to dismiss UI element without state change" as a loop pattern and escalate to an alternative strategy (e.g., system back, then undo).

### P2 - Nice to Have
7. **Pre-read content before editing**: Before typing, the agent should read/verify the current content and cursor position from the a11y tree to plan the correct edit sequence, rather than assuming cursor placement worked.
8. **Compose-then-type strategy for header insertion**: For "add text before existing content" tasks, teach the agent to: (a) read current content from the a11y tree, (b) compose full new content = header + blank line + original, (c) select-all, (d) type the composed content. This is more reliable than trying to position the cursor.
