# SportsTrackerActivitiesOnDate -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 5 | **Reason**: Agent answered with track display names instead of activity type categories
**Goal**: "What activities did I do October 1 2023 in the OpenTracks app? Answer with the activity type only. If there are multiple types, format your answer in a comma separated list."

## Root Cause
**Category**: FalseCompletion
**Summary**: The agent navigated to the OpenTracks track list, scrolled to find the Oct 1 entries ("Active Rest Day" and "Mindful Movement"), and submitted those track display names as the answer. However, the task asks for the "activity type" -- the CATEGORY/TYPE label (e.g., "running", "walking") -- which is only visible inside each track's detail view. The system prompt explicitly warns that "activity type" means the CATEGORY/TYPE label, not the display name, but the LLM (qwen3.5) ignored this instruction.

## Turn-by-Turn Analysis
- Turn 1: `open_app("OpenTracks")` -- Successfully launched OpenTracks. Post-action shows the app's intro/welcome screen ("OpenTracks is a sport tracking application that completely respects your privacy.") with 6 elements.
- Turn 2: `click(element_index=5)` -- Clicked the ImageButton (next/proceed arrow) on the intro screen. Screen stayed on the intro screen (same 6 elements, text unchanged). The click may have been registered but the screen had not yet transitioned, or this was page 1 of a multi-page intro.
- Turn 3: `click(element_index=5)` -- Clicked the same proceed button. Post-action screen now only showed 4 status bar elements (transition state). The intro's text had changed to "OpenTracks itself does not provide a map" between turns 2 and 3, confirming multi-page intro navigation.
- Turn 4: At turn start, screen shows the main track list (83 elements) with activities sorted by date (newest first: Today, Friday, Thursday, Tuesday, Monday, 7 Oct, 6 Oct). `scroll(direction="down", element_index=9)` -- Scrolled the RecyclerView down. Post-action shows entries from 6 Oct through 1 Oct, including "Active Rest Day | 1 Oct" and "Mindful Movement | 1 Oct".
- Turn 5: Screen shows 81 elements including the two Oct 1 tracks. `complete_task(status="success", answer="Active Rest Day, Mindful Movement")` -- Agent completed task with track display names instead of activity type categories.

## Failure Points
- **Turn 5**: The agent completed the task with track NAMES ("Active Rest Day", "Mindful Movement") rather than activity TYPE CATEGORIES. The system prompt line 88 explicitly states: `"activity type" or "what activities" = the CATEGORY/TYPE label (e.g., "running"), NOT the display name`. The LLM failed to follow this critical instruction.
- **Missing turns**: The agent should have clicked into each Oct 1 track (at least 2 more turns) to view the track detail screen where the activity type category (e.g., "running", "yoga", "walking") is displayed.

## What Worked
- App opening was clean and direct (1 turn)
- Navigating through the multi-page intro screen was handled correctly (2 turns)
- Scrolling the track list to find Oct 1 entries worked efficiently (1 scroll)
- Correctly identified the two tracks dated "1 Oct" in the list
- Efficient turn usage overall (5 turns out of 30 budget) -- the agent had plenty of turns remaining to do the additional investigation

## What Didn't Work
- The LLM (qwen3.5) confused track display names with activity type categories, despite explicit system prompt guidance
- No use of scratchpad to record findings or plan next steps
- Agent did not click into any track to inspect its detail view where the actual activity type category would be visible
- Premature task completion without verifying the answer matched what was asked (activity TYPE, not track NAME)

## Suggested Fix
1. **Prompt reinforcement for OpenTracks**: Add an OpenTracks-specific App Tip section in the system prompt:
   ```
   ### OpenTracks / Sports Tracker
   - The track list shows TRACK NAMES (display names), NOT activity types.
   - To find the activity TYPE (category like "running", "biking", "walking"),
     you MUST click into each track's detail view.
   - For "what activities" or "activity type" questions, NEVER use the track
     name from the list view. Always open the track to find the category.
   ```
2. **Scratchpad discipline**: The agent should have been prompted or trained to use scratchpad before completing QA tasks -- writing down what it found and cross-checking against the goal's exact wording. The system prompt's QA section already recommends this but qwen3.5 did not follow it.
3. **LLM quality**: This is partly a model capability issue. The system prompt at line 88 already explicitly instructs `"activity type" = the CATEGORY/TYPE label (e.g., "running"), NOT the display name`, but qwen3.5 ignored this. A stronger model might follow this instruction more reliably. Consider whether qwen3.5 is consistently failing on instruction-following for QA tasks.
4. **Pre-completion verification gate**: Consider adding a programmatic check that warns the agent if it attempts to complete a QA task asking for "activity type" within the first few turns of viewing a track list (i.e., without ever opening a track detail view). This would serve as a safety net for this failure pattern.
