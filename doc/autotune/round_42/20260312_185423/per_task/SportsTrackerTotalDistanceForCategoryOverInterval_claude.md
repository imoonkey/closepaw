# SportsTrackerTotalDistanceForCategoryOverInterval — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 37/50 | **Reason**: GoalAchieved (false completion) | **Duration**: 313s

**Goal**: Total distance for kayaking activities in OpenTracks from Oct 9-10 2023. Answer in meters rounded to nearest integer.

## Root Cause

**Category**: FalseCompletion (PerceptionGap + NoScroll)

The agent checked all 5 visible tracks in the Oct 9-10 range (improvement over R41's 2/5), but never scrolled down to discover additional tracks below the fold. The correct answer is 6502m (known from R25 which scrolled exhaustively), meaning kayaking tracks exist further down the list. Additionally, every Edit screen had a **complete a11y tree failure** — the OpenTracks Edit form fields were invisible to the accessibility service (only status bar elements captured). The agent hallucinated all 5 activity types based on track names, which happened to match reality but represents a fragile approach.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("OpenTracks")` | Y | G | G | G | Launched app |
| 2 | `write_todos` (plan) | N | G | G | G | Good plan: identify tracks, verify types in Edit, sum |
| 3-4 | `click(8)` x2 onboarding | Y | G | G | G | Dismissed two onboarding screens |
| 5 | `scratchpad` — note date-range tracks | N | G | G | G | Correctly mapped: Tue=Oct10, Mon=Oct9. Listed 5 tracks |
| 6 | `click(55)` — open Road Riding | Y | G | G | G | First track in range |
| 7 | `scratchpad` — record stats | N | G | G | G | 1266.40 ft, Oct 10 |
| 8 | `click(43)` — More options | Y | G | G | G | Opened popup menu |
| 9 | `click(9)` — Edit | Y | G | G | G | Opened Edit form |
| 10 | `scratchpad` — "road biking" | N | **B** | B | G | **Hallucinated** — a11y tree was empty |
| 11 | `system_button(back)` | Y | G | G | G | Return from Edit |
| 12-13 | Navigate back, open City Stroll | Y | G | G | G | — |
| 14 | `scratchpad` + `click(43)` | Y | G | G | G | Record stats, open menu |
| 15 | `click(9)` — Edit | Y | G | G | G | — |
| 16 | `scratchpad` — "walking" | N | **B** | B | G | **Hallucinated** — a11y tree was empty |
| 17-19 | Back, navigate, open Morning Walk | Y | G | G | G | — |
| 20 | `scratchpad` + `click(43)` | Y | G | G | G | Record stats, open menu |
| 21 | `click(9)` — Edit | Y | G | G | G | — |
| 22 | `scratchpad` — "walking" | N | **B** | B | G | **Hallucinated** — a11y tree was empty |
| 23-25 | Back, navigate, open Brisk Walk | Y | G | G | G | — |
| 26 | `scratchpad` — stats | N | G | G | G | — |
| 27 | `click(43)` — More options | Y | G | G | G | — |
| 28 | `click(9)` — Edit | Y | G | G | G | — |
| 29 | `scratchpad` — "walking" | N | **B** | B | G | **Hallucinated** — a11y tree was empty |
| 30-32 | Back, navigate, open Sailing Excursion | Y | G | G | G | — |
| 33 | `scratchpad` — stats | N | G | G | G | — |
| 34 | `click(43)` — More options | Y | G | G | G | — |
| 35 | `click(9)` — Edit | Y | G | G | G | — |
| 36 | `scratchpad` — "sailing" | N | **B** | B | G | **Hallucinated** — a11y tree was empty |
| 37 | `complete_task(answer="0")` | N | **B** | G | G | **FalseCompletion** — never scrolled |

### Key Turns

**Turn 5** — Agent noted 5 tracks in range but did not scroll down:
- **What was on screen**: Track list (88 elements, RecyclerView marked SCROLLABLE). Visible: Sculling Session (Today) through Sailing Excursion (Monday). List ends with "Record" button at bottom.
- **What agent decided**: Stored 5 tracks as the complete set for Oct 9-10.
- **What should have happened**: Scrolled down to check for additional Monday (Oct 9) tracks below "Sailing Excursion". The skill says "Scroll down repeatedly until the list stops changing."

**Turn 10** (and 16, 22, 29, 36) — Agent hallucinated activity types from empty a11y tree:
- **What was on screen**: Screenshot shows Edit form with Name, Activity type dropdown, Description fields. Activity type clearly visible as text in the form.
- **What agent saw**: a11y tree with only 7 elements — all system status bar items. Zero form fields. screenshot_attached=false.
- **What agent decided**: Wrote activity type to scratchpad as if reading from the Edit form.
- **What actually happened**: The LLM inferred activity types from track names (Road Riding -> "road biking", City Stroll -> "walking", etc.). All 5 happened to be correct per screenshot verification, but the agent had zero evidence for these claims in its actual input.
- **Perception check**: OpenTracks TrackEditActivity does not properly expose its form fields to the accessibility service. The a11y tree contained only `com.android.systemui` elements. This is a known PerceptionGap for this activity.

**Turn 37** — False completion:
- **What agent decided**: "All tracks in range have verified types and none are kayaking" -> answer "0".
- **What should have happened**: Before concluding, scroll the track list to verify no additional Oct 9 tracks exist below the fold. The app skill explicitly says: "Scroll down repeatedly until the list stops changing to see all tracks."

## What Worked

- R42 skill change succeeded: agent checked all 5 visible tracks (vs R41's 2/5). The "open Edit for ALL tracks" instruction was followed.
- Correct date mapping: Tue=Oct10, Mon=Oct9 based on device date Oct 15 (Sunday).
- Systematic approach: well-structured todos, consistent pattern per track (stats -> menu -> Edit -> record -> back).
- Activity type hallucinations happened to be correct for all 5 tracks (lucky, not reliable).

## What Didn't Work

- **No scrolling**: Despite the app skill saying "Scroll down repeatedly until the list stops changing" and the system prompt saying "scroll the full list," the agent never scrolled. It treated the initial view as the complete list.
- **A11y tree empty on Edit screens**: The OpenTracks TrackEditActivity does not expose its form fields through the accessibility API. The agent had zero evidence for the activity types it recorded. This is an infra-level PerceptionGap.
- **No screenshot fallback**: screenshot_input was disabled (config: `"screenshot_input": false`). With screenshots enabled, the model could have actually read the Edit form fields, making the hallucinated types grounded.
- **37 turns consumed**: 6 turns per track x 5 tracks + 7 overhead = 37 turns. If the agent had scrolled and found more tracks, it could have exceeded the 50-turn limit.

## Comparison with Prior Rounds

| Round | Tracks Checked | Scrolled? | Answer | Score | Key Difference |
|-------|---------------|-----------|--------|-------|----------------|
| R25 | ALL (scrolled) | Yes | 6502 | 1.0 | Exhaustive scan, 45 turns |
| R39 | Partial | No | 2832 | 0.0 | Found some kayaking, missed others |
| R41 | 2/5 visible | No | 0 | 0.0 | Smart Filtering skipped 3 tracks |
| R42 | 5/5 visible | No | 0 | 0.0 | Checked all visible, still no scroll |

## Recommendations

1. **Strengthen scroll instruction in app skill**: Change the "Scrolling" section to be more assertive: "BEFORE identifying tracks in a date range, scroll the entire list end-to-end. The initial view may not show all tracks. You MUST scroll until no new items appear."

2. **Add scroll-first step to counting/summing section**: In the "Counting/Summing by Activity Type" section, add: "Step 1: Scroll the full list to identify ALL tracks in the date range. Step 2: Open Edit for each. Do not start checking tracks until you have scrolled the full list."

3. **Enable screenshot_input for OpenTracks tasks**: The Edit form has a complete a11y tree failure. Without screenshots, the agent has zero evidence for activity types. Either enable screenshot_input or investigate why TrackEditActivity doesn't expose form fields via accessibility.

4. **Detect empty a11y tree as anomaly**: When the agent navigates to a new screen and the a11y tree contains only status bar elements (all from `com.android.systemui`), this should trigger a warning or fallback strategy (e.g., request screenshot, try scrolling, use coordinates). Currently the agent silently hallucinates content.

5. **Zero-result sanity check**: When an info-extraction task yields 0 matches after checking a subset of items, the agent should verify completeness by scrolling. This was recommended in R41 but not implemented.
