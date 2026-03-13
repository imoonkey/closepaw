# SportsTrackerTotalDistanceForCategoryOverInterval — R41 Cog-Tune Analysis

**Run**: 20260312_173354 | **Score**: 0.0 | **Turns**: 16/50 | **Reason**: GoalAchieved | **Duration**: 119s
**Model**: gpt-5.4 | **Answer**: "0" (wrong)

**Goal**: Total distance for kayaking activities in OpenTracks from Oct 9 to Oct 10, 2023. Answer in meters.

## Context

This task was targeted by the R41 info-extraction verification fix added to the system prompt:
> "For info-extraction: scroll the full list, cross-check totals against scratchpad, verify date boundaries before answering."

Historical: Was a 14-turn FalseCompletion in R39 (answer: 2832, also wrong). Fixed in R25 (1.0, answer 6502m with 45 turns). Persistent fragility across rounds.

## Root Cause

**Category**: SmartFilteringOverreach + NoScroll

The agent applied the app skill's "Smart Filtering" heuristic too aggressively, skipping tracks whose names seemed unrelated to kayaking. It only opened the Edit screen for 2 of the 5 visible tracks in the Oct 9-10 range. It also never scrolled, so any kayaking tracks below the fold were completely missed. The system prompt's info-extraction instruction ("scroll the full list") was overridden by the app skill's "skip tracks whose names clearly indicate an unrelated activity" guidance.

## Turn-by-Turn Analysis

| Turn | Action | Thought | Assessment |
|------|--------|---------|------------|
| 1 | `open_app("OpenTracks")` | Open app to inspect activities | Good |
| 2 | `click(8)` — dismiss onboarding | Dismiss intro screen | Good |
| 3 | `click(8)` — advance onboarding | Advance through info screen | Good |
| 4 | `write_todos` — plan created | "Inspect Tue Oct 10, then Mon Oct 9, then sum" | Good date mapping (Mon=Oct9, Tue=Oct10) |
| 5 | `click(55)` — open "Road Riding" (Tuesday) | "Open to verify actual activity type in Edit, since names can be misleading" | **Selective** — only picked this one "ambiguous" Tue track |
| 6 | `click(43)` — More options | Open menu to reach Edit | Good |
| 7 | `click(9)` — Edit | Enter Edit to read activity type | Good |
| 8 | `scratchpad` + `write_todos` | Recorded: road biking, 1266.40 ft. Marked Oct 10 done. | **Premature completion** of Oct 10 — 2 other Tue tracks unchecked |
| 9 | `click(14)` — Cancel/back | Return from Edit | Good |
| 10 | `click(40)` — Navigate up | Return to track list | Good |
| 11 | `click(79)` — open "Sailing Excursion" (Monday) | "The only plausible kayaking candidate visible for Oct 9" | **Selective** — skipped "Brisk Walk" on Mon |
| 12 | `click(43)` — More options | Open menu to reach Edit | Good |
| 13 | `click(9)` — Edit | Enter Edit to read activity type | Good |
| 14 | `scratchpad` | Recorded: sailing, 5.41 mi | Correct for this track |
| 15 | `write_todos` | "No kayaking tracks on Oct 9 or Oct 10; plan complete" | **Wrong conclusion** — only checked 2/5 tracks |
| 16 | `complete_task(answer="0")` | No kayaking found | **FalseCompletion** |

## Detailed Failure Analysis

### Tracks visible in the Oct 9-10 range (from 88-element a11y tree):

**Tuesday (Oct 10):**
- Road Riding — 1,266.40 ft — CHECKED (road biking)
- City Stroll — 10.11 mi — SKIPPED (name filtering)
- Morning Walk — 8.87 mi — SKIPPED (name filtering)

**Monday (Oct 9):**
- Brisk Walk — 12.14 mi — SKIPPED (name filtering)
- Sailing Excursion — 5.41 mi — CHECKED (sailing)

The agent checked 2 out of 5 tracks in range. The 3 skipped tracks ("City Stroll", "Morning Walk", "Brisk Walk") were dismissed because their names "clearly indicate an unrelated activity." But the app skill itself states: "Track names are user-chosen and do NOT reliably indicate activity type." Any of these could have "kayaking" as their actual activity type in the Edit screen.

### No scrolling performed

The agent never scrolled down. There could be additional Monday (Oct 9) or earlier tracks below the fold. The system prompt line 42 explicitly says "scroll the full list" for info-extraction tasks, and the app skill says "Scroll down repeatedly until the list stops changing." Both were ignored.

### Info-extraction verification fix was present but ineffective

The system prompt (line 42) contained the R41 fix: "For info-extraction: scroll the full list, cross-check totals against scratchpad, verify date boundaries before answering." The agent:
- Did NOT scroll the full list
- Did NOT cross-check totals (it had 0, so nothing to cross-check)
- DID correctly map date boundaries (Mon=Oct9, Tue=Oct10)

The fix was overridden by the app skill's "Smart Filtering" section, which told the agent to "skip tracks whose names clearly indicate an unrelated activity." The agent took this as license to skip 3/5 tracks without opening their Edit screens.

## Conflicting Instructions

The failure is caused by a direct conflict between two instruction sources:

1. **System prompt** (line 42): "scroll the full list, cross-check totals against scratchpad"
2. **App skill** (Smart Filtering): "Skip tracks whose names clearly indicate an unrelated activity"

The app skill gave the agent a shortcut that is fundamentally incompatible with exhaustive info-extraction. When the agent found 0 kayaking tracks among the 2 it checked, it had no reason to doubt its conclusion. The system prompt's "cross-check totals against scratchpad" check is vacuous when the total is 0 — there's nothing to cross-check.

## Comparison with R39

R39 answer was 2832 (also wrong). R39 used 14 turns vs R41's 16. In R39, the agent found some kayaking tracks but missed others (incomplete scan). In R41, the agent found zero kayaking tracks because it was even more selective about which tracks to examine. The Smart Filtering guidance made the agent worse, not better.

## Comparison with R25 (FIXED, 1.0)

R25 used 45 turns, scrolled exhaustively, and found all kayaking tracks with total 6502m. The key difference: R25 checked ALL tracks in the date range, not just "plausible" ones.

## Recommendations

1. **Remove or weaken Smart Filtering**: The "skip tracks whose names clearly indicate an unrelated activity" advice is dangerous for info-extraction tasks requiring exhaustive coverage. Either remove it entirely or add a caveat: "For counting/summing tasks, check ALL tracks in the date range regardless of name."

2. **Strengthen the exhaustive-scan instruction**: The current system prompt line is insufficient because it doesn't override app-skill shortcuts. Consider: "For info-extraction tasks requiring sums or counts: you MUST check every item in the date range. Do not skip items based on name heuristics — names are unreliable."

3. **Zero-result sanity check**: When an info-extraction task yields 0 matches, the agent should trigger extra verification (scroll further, re-check skipped items) before answering "0." A zero answer on a counting task is often wrong.
