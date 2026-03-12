# SportsTrackerLongestDistanceActivity — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 25/30 | **Reason**: GoalAchieved | **Duration**: 181s

**Goal**: Find the longest distance mountain biking activity this week in OpenTracks. Answer in meters, rounded.

## Root Cause

**Category**: FalseCompletion / PerceptionFailure

The agent spent 25 turns and 28 tool calls searching through tracks but answered "0" — indicating it found no mountain biking activities. The actual data contains mountain biking tracks but the agent failed to identify them, most likely because the activity type label in the accessibility tree does not display as "mountain biking" or "mountain bike" in a recognizable form, causing the agent to skip valid records. The agent then gave up and returned 0 rather than reporting uncertainty.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1–3 | Open OpenTracks, view activity list | Yes | Correct | Good | OK | Normal start |
| 4–10 | Scroll through activity list, read track names | Partial | Correct | Moderate | OK | Scanning for mountain biking |
| 11–18 | Open individual track details, read activity type | Yes | Correct | Weak | OK | Checking type per activity |
| 19–23 | Continue scanning remaining tracks | Partial | Struggling | Weak | OK | Repeated pattern, no match found |
| 24 | Conclude no mountain biking found | No | Incorrect | Bad | — | FalseCompletion decision |
| 25 | Answer "0" | — | Wrong | Bad | — | Should have stated uncertainty |

### Key Turns

**Turns 11–23** — The agent opened multiple track detail views to read activity types. Despite 28 tool calls, it could not match any track to "mountain biking." This suggests the a11y tree label for mountain biking activities uses a variant the agent didn't recognize (e.g., "Cycling", "MTB", an icon without text, or a different locale string).

**Turn 24** — Instead of reporting "could not determine" or asking for clarification, the agent concluded there were no mountain biking activities and prepared to answer 0.

**Turn 25** — Answered "0" as the longest distance. This is a FalseCompletion: the agent misidentified the data state rather than returning a correct distance.

## What Worked

- Agent persisted for 25 turns, showing appropriate effort
- Systematic scanning of multiple tracks
- Correct strategy of checking activity type per detail view

## What Didn't Work

- Failed to recognize mountain biking activity type label in the a11y tree
- Answered "0" instead of flagging uncertainty — incorrect confidence
- 28 tool calls without finding a match suggests the activity type perception is broken for this category
- "This week" date filter may have been applied incorrectly, excluding the relevant activities from the scan window

## Recommendations

- Investigate what label OpenTracks uses for mountain biking in the accessibility tree — it may be a numeric type code, an icon, or a non-English string
- Agent should report "not found / uncertain" rather than "0" when no matching activities are found after exhaustive search
- Add fuzzy matching for activity types: "cycling", "bike", "mtb", "mountain" should all be treated as potential mountain biking candidates
- The "this week" constraint should be verified against actual track dates, not assumed from list ordering
- Not caused by tool description compression — this is a persistent perception issue with OpenTracks activity type labels
