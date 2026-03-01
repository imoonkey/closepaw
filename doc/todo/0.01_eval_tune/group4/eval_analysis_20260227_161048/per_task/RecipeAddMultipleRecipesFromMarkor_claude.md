# RecipeAddMultipleRecipesFromMarkor -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 16 | **Reason**: Error (repeated action loop at turn 16) | **Duration**: 114s
**Goal**: Add the recipes from recipes.txt in Markor to the Broccoli recipe app.

## Root Cause
**Category**: Cognition (planning) + System (false-positive loop detection)
**Summary**: Two compounding problems. First, the agent adopted a one-action-per-turn field-filling strategy (click field, then type in next turn), which is too slow for a task requiring 3 recipes with 6+ fields each (~36+ turns of data entry alone against a 30-turn budget). Second, the anti-loop detector falsely flagged the agent at turn 16 because the repeated click-type-click-type pattern on element indices 10/11/12 looked like a loop, even though the agent was making real progress filling distinct form fields that simply re-indexed after scrolling. The agent had successfully filled Title, Description, Servings, and Time for the first recipe and was about to fill Ingredients when it was terminated.

## Turn-by-Turn Analysis

### Turn 1
- **Action**: `open_app { app_name: "Markor" }`
- **Result**: Successfully launched Markor. The app package `net.gsantner.markor` appeared on screen.
- **Assessment**: Good -- correct first step. The agent correctly identified it needs to read the recipes from Markor first.

### Turn 2
- **Action**: `mobile_action { action: "click", element_index: 13 }` -- click on `recipes.txt`
- **Result**: Successfully opened the file. The full content of recipes.txt was visible in the a11y tree as a single EditText element (index 12) with all 3 recipes: Thai Peanut Noodle Salad, Garlic Butter Shrimp, BBQ Chicken Quesadillas. Each recipe had: description, servings, preparationTime, ingredients, and directions.
- **Assessment**: Good -- the agent correctly navigated to and opened the recipes file. Importantly, the entire file content was accessible in a single a11y element.

### Turn 3
- **Action**: `scratchpad { action: "write", content: "{\"recipes\": \"Recipe 1: Thai Peanut Noodle Salad...\"}" }`
- **Result**: Recipe content saved to scratchpad for cross-app transfer.
- **Assessment**: Good strategy -- the agent used the scratchpad to memorize recipe data before switching apps. However, the scratchpad content was slightly inaccurate compared to the source file (e.g., "ingredients: as desired" became just a generic reference). This could cause downstream issues if the agent used scratchpad content rather than its own context memory.

### Turn 4
- **Action**: `open_app { app_name: "Broccoli" }`
- **Result**: Successfully launched the Broccoli recipe app. The main screen showed an empty recipe list with a "New Recipe" FAB, plus bottom navigation (Recipes, Seasonal Calendar, All Recipes, Favorites, Settings).
- **Assessment**: Good -- correct navigation to the target app.

### Turn 5
- **Action**: `mobile_action { action: "click", element_index: 32 }` -- click the "New Recipe" FAB
- **Result**: Successfully opened the "New Recipe" form. Visible fields: Recipe photo, Title, Categories, Description, Source, Servings, Time, Ingredients, Directions. SAVE button in toolbar.
- **Assessment**: Good -- correct action to begin entering the first recipe.

### Turn 6
- **Action**: `mobile_action { action: "click", element_index: 10 }` -- click the Title field
- **Result**: Title field received focus. The keyboard appeared, causing the visible viewport to shrink (ScrollView bounds reduced from y=2337 to y=1517).
- **Assessment**: Correct but inefficient -- the agent should have used `type` with `element_index` directly, which would focus and type in one step. Instead, it spent an entire turn just clicking to focus the field.

### Turn 7
- **Action**: `mobile_action { action: "type", input_text: "Thai Peanut Noodle Salad", element_index: 10 }`
- **Result**: Title field now contains "Thai Peanut Noodle Salad". Field still focused.
- **Assessment**: Good -- correct recipe title entered.

### Turn 8
- **Action**: `mobile_action { action: "click", element_index: 12 }` -- click the Description field
- **Result**: Description field received focus. Title still shows "Thai Peanut Noodle Salad".
- **Assessment**: Same inefficiency -- wasting a turn clicking to focus rather than typing directly.

### Turn 9
- **Action**: `mobile_action { action: "type", input_text: "A quick and easy meal, perfect for busy weekdays.", element_index: 12 }`
- **Result**: Description field now contains the text. Viewport scrolled slightly.
- **Assessment**: Good -- correct description entered.

### Turn 10
- **Action**: `mobile_action { action: "scroll", direction: "down", element_index: 7 }` -- scroll the form ScrollView
- **Result**: Successfully scrolled. Now visible fields are: Description (filled), Source (empty), Servings (empty), Time (empty), Ingredients (empty), Directions (empty). Title and photo are now scrolled off-screen.
- **Assessment**: Good -- necessary to reach the lower form fields. Note that element indices shifted after scroll: Servings is now index 10, Time is 11, Ingredients is 12, Directions is 13.

### Turn 11
- **Action**: `mobile_action { action: "click", element_index: 10 }` -- click the Servings field
- **Result**: Servings field (now at index 10 post-scroll) received focus.
- **Assessment**: Again, wasted turn by clicking before typing. This is the third time the agent follows the click-then-type pattern for a form field.

### Turn 12
- **Action**: `mobile_action { action: "type", input_text: "8 servings", element_index: 10 }`
- **Result**: Servings field now contains "8 servings".
- **Assessment**: Good -- correct value entered.

### Turn 13
- **Action**: `mobile_action { action: "click", element_index: 11 }` -- click the Time field
- **Result**: Time field received focus.
- **Assessment**: Same click-before-type pattern.

### Turn 14
- **Action**: `mobile_action { action: "type", input_text: "3 hrs", element_index: 11 }`
- **Result**: Time field now contains "3 hrs".
- **Assessment**: Good -- correct value entered.

### Turn 15
- **Action**: `mobile_action { action: "click", element_index: 12 }` -- click the Ingredients field
- **Result**: Ingredients field received focus. All previously filled fields (Description, Servings="8 servings", Time="3 hrs") remain correctly filled.
- **Assessment**: The agent was about to type the ingredients value, making genuine progress. But this was the last turn before loop detection.

### Turn 16
- **Action**: `complete_task { status: "failure" }` -- FORCED by anti-loop detector
- **Result**: Session terminated. "detected repeated action loop at turn 16."
- **Assessment**: False positive from the anti-loop heuristic. The agent was NOT stuck -- it had successfully filled 4 out of ~6 fields for the first recipe and was progressing linearly. The repeated click-type pattern on element indices 10, 12, 10, 11, 12 triggered the detector because those index values recurred after scrolling despite targeting different form fields.

## What Went Wrong

### 1. Task exceeds turn budget (Planning failure)
The task requires adding 3 complete recipes. Each recipe has ~6 fields (Title, Description, Servings, Time, Ingredients, Directions). With the agent's click-then-type strategy, each field costs 2 turns. That is a minimum of 36 turns for data entry alone, plus:
- 1 turn to open Markor
- 1 turn to open recipes.txt
- 1 turn for scratchpad
- 1 turn to open Broccoli
- 3 turns for "New Recipe" button (once per recipe)
- 3 turns for SAVE button (once per recipe)
- ~3 turns for scrolling

Total: ~48+ turns against a 30-turn budget and a loop detector that fires much sooner.

### 2. Inefficient field-filling pattern (Cognition failure)
The agent uses two turns per field: click to focus, then type. The `type` action with an `element_index` parameter should be able to focus and type in a single action. By skipping the separate click step, the agent could cut data entry turns by nearly half (~18 turns for 3 recipes instead of ~36).

### 3. False-positive loop detection (System failure)
The anti-loop detector saw repeated mobile_action calls with action types `click` and `type` on element indices 10, 11, 12 and concluded the agent was looping. In reality, after scrolling (Turn 10), the a11y tree re-indexed the visible elements, so index 10 referred to Servings (not Title), and index 12 referred to Ingredients (not Description). The detector does not account for **element identity** -- only for the surface-level action pattern.

## Recommendations

### Prompt/Context Engineering
1. **Multi-field form-filling strategy**: Add guidance in the system prompt that for forms with many fields, the agent should type directly into elements by index without a separate click-to-focus step. This halves the turn cost per field.
2. **Planning for multi-item tasks**: When a task requires repeating a similar workflow N times (adding N recipes), the agent should estimate turn cost upfront and consider efficiency optimizations like batch typing or skipping optional fields.

### Anti-Loop Heuristic
3. **Element identity awareness**: The loop detector should compare element semantic identity (e.g., hint_text="Servings" vs hint_text="Title"), not just element_index values, to distinguish genuine progress through a form from actual loops.
4. **Progress detection in forms**: Consider whitelisting a "form fill" pattern where the agent is typing new values into form fields that were previously empty. If each field gains new content between iterations, that is progress, not a loop.

### Efficiency
5. **Turn budget awareness**: For tasks that clearly require many repeated steps (adding 3 recipes), the agent could use shell commands or intents to pre-populate data rather than manual field-by-field typing.
6. **Skip unnecessary fields**: The agent could skip optional/less-critical fields (like Source, Categories) to stay within budget, filling only required fields (Title, Description, Ingredients, Directions).

## Efficiency Notes
- Even with optimal one-action-per-field typing, adding 3 complete recipes would require ~24+ turns. The 30-turn budget makes this task extremely tight. A smarter agent would recognize this upfront and prioritize the most critical fields.
- The first 4 turns (open Markor, read file, scratchpad, open Broccoli) were efficient and well-executed. The bottleneck was purely in the form-filling phase.
- Total time spent was 114s across 16 turns (~7s/turn average), with LLM inference dominating latency.
