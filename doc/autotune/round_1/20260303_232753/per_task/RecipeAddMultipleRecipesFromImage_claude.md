# Per-Task Analysis: RecipeAddMultipleRecipesFromImage

**Run ID**: 20260303_232753
**Score**: 0.0 | **Turns**: 35/50 | **Stop Reason**: Error (LLM SSE error)
**Model**: qwen3.5 (qwen/qwen3.5-plus-02-15)
**Duration**: ~331s (~5.5 min)

---

## Task Summary

The task requires the agent to open an image containing multiple recipes, read the recipe details, then add each recipe to the Broccoli recipe app. The agent needed to extract recipe data from `recipes.jpg` in the Gallery, then create new recipe entries in Broccoli with correct titles, descriptions, servings, prep time, ingredients, and directions.

---

## What Happened (Turn-by-Turn Narrative)

### Phase 1: Finding the Image (Turns 1-10, ~10 turns)

| Turn | Action | Outcome |
|------|--------|---------|
| 1 | `open_app("Simple Gallery Pro")` | **FAIL**: App not found. Agent guessed wrong app name. |
| 2 | `open_app("Gallery")` | Success: Gallery launched |
| 3 | `click(element_index=8)` | Clicked search bar |
| 4 | `type("recipes", element_index=8)` | Typed search term |
| 5 | `click(element_index=12)` | Switched to file search |
| 6 | `type("recipes.jpg", element_index=8)` | More specific search |
| 7 | `shell("find /sdcard -name '*.jpg' 2>/dev/null \| grep -i recipe")` | exit=1 (no results) |
| 8 | `shell("find /sdcard -name '*.jpg' -o -name '*.jpeg' 2>/dev/null \| head -20")` | exit=0 (found files) |
| 9 | `click(element_index=7)` | Back to gallery main view |
| 10 | `click(element_index=11)` | Clicked image thumbnail - **found recipes.jpg** |

**Observation**: At turn 10, the screen shows `recipes.jpg` displayed full-screen in the Gallery app. The agent could see the image content through the screenshot.

### Phase 2: Extracting Recipe Data (Turn 11)

| Turn | Action | Outcome |
|------|--------|---------|
| 11 | `scratchpad(write, {...})` | Stored 3 recipes with all fields: titles, descriptions, servings, prep time, ingredients, directions |

The agent extracted 3 recipes from the image:
1. **Eggplant Parmesan** - 8 servings, 10 mins, "An ideal recipe for experimenting..."
2. **Baked Cod with Lemon and Dill** - 2 servings, 45 mins, "A quick and easy meal..."
3. **Chickpea Vegetable Soup** - 2 servings, 2 hrs, "An ideal recipe for experimenting..."

### Phase 3: Adding Recipe 1 - Eggplant Parmesan (Turns 12-22, ~11 turns)

| Turn | Action | Outcome |
|------|--------|---------|
| 12 | `open_app("Broccoli")` | Success: Broccoli launched, showing existing recipe list |
| 13 | `click(element_index=34)` | Clicked "New Recipe" FAB |
| 14 | `type("Eggplant Parmesan", element_index=12)` | Typed title |
| 15 | `type("Eggplant Parmesan", element_index=12, clear=true)` | Re-typed title (possibly title field had focus issues) |
| 16 | `type("An ideal recipe for experimenting...", element_index=14)` | Typed description |
| 17 | `type(3 parallel calls for servings/prep/ingredients)` | Attempted 3 fields at once, but multi-call had issues |
| 18 | `type("10 mins", element_index=17)` | Re-typed prep time |
| 19 | `scroll(down, element_index=9)` | Scrolled to reveal directions field |
| 20 | `type("varies", element_index=18)` | Typed ingredients |
| 21 | `type("Slice eggplant, bread, and fry...", element_index=19)` | Typed directions |
| 22 | `click(element_index=7)` | **Clicked SAVE** |

**Note**: The "Description" field ended up as "DescriptionAn ideal recipe for experimenting..." in the final state - the word "Description" was prepended, likely the field label/hint was concatenated with the typed text. This is a UI interaction bug.

### Phase 4: Adding Recipe 2 - Baked Cod with Lemon and Dill (Turns 23-33, ~11 turns)

| Turn | Action | Outcome |
|------|--------|---------|
| 23 | `click(element_index=9)` | Navigated back to recipe list |
| 24 | `click(element_index=34)` | Clicked "New Recipe" |
| 25 | `type("Baked Cod with Lemon and Dill", element_index=12)` | Typed title |
| 26 | `type("Baked Cod with Lemon and Dill", element_index=12, clear=true)` | Re-typed title |
| 27 | `type("A quick and easy meal...", element_index=14)` | Typed description |
| 28 | `type("2 servings", element_index=16)` | Typed servings |
| 29 | `type("45 mins", element_index=17)` | Typed prep time |
| 30 | `type("subject to change", element_index=18)` | Typed ingredients |
| 31 | `scroll(down, element_index=9)` | Scrolled to directions |
| 32 | `type("Place cod fillets in a baking dish...", element_index=19)` | Typed directions |
| 33 | `click(element_index=7)` | **Clicked SAVE** |

### Phase 5: Starting Recipe 3 - Chickpea Vegetable Soup (Turn 34-35, TERMINATED)

| Turn | Action | Outcome |
|------|--------|---------|
| 34 | `click(element_index=9)` | Navigated back to recipe list |
| 35 | LLM request sent (105 input items, screenshot attached) | **LLM ERROR**: `SseException - 200: Provider returned error` |

**The session terminated** due to an LLM provider SSE error at turn 35, while the agent was about to start adding the third recipe (Chickpea Vegetable Soup).

---

## Final State Analysis

At the final screen (after turn 34), the Broccoli recipe list shows these recipes visible:
- Chickpea Vegetable Soup (pre-existing, "A delicious and healthy choice...")
- Classic Margherita Pizza (pre-existing)
- **Eggplant Parmesan** (ADDED by agent, description: "DescriptionAn ideal recipe for experimenting...")
- Moroccan Chickpea Stew (pre-existing)
- Pan-Seared Salmon with Quinoa (pre-existing)
- Salmon with Dill Sauce (pre-existing)

**Missing from visible list**: "Baked Cod with Lemon and Dill" - the second recipe was either not saved properly, or it was scrolled off screen. However, since the agent clicked SAVE at turn 33 and then navigated back at turn 34, the recipe should have been saved.

---

## Root Cause Analysis

### Primary Failure: LLM Provider Error (Infrastructure)

The session terminated at turn 35 with:
```
LLM error: SseException - 200: Provider returned error
type: java.lang.RuntimeException
```

This is an **infrastructure-level failure** -- the LLM provider (OpenRouter/Qwen) returned an error during the SSE stream. The agent's behavior was not at fault; it was making correct progress toward completing the task.

### Contributing Factor: No LLM Error Retry

The agent loop does not appear to retry on transient LLM errors. A single SSE error kills the entire session. This is a framework-level robustness gap.

### Secondary Issue: Description Field Concatenation Bug

The Eggplant Parmesan recipe's description shows as "DescriptionAn ideal recipe for experimenting..." -- the field label "Description" was concatenated with the typed text. This suggests the Broccoli app's description field has a label/hint that either:
- Was part of the text content when the agent typed into it (no `clear=true` used for description field)
- The hint text is not cleared on focus and gets combined with input

### Tertiary Issue: Turn Efficiency

Each recipe takes ~10-11 turns to complete. With 3 recipes, the agent needs ~30+ turns for data entry alone plus ~10 turns for image finding. The current approach of single-action-per-turn for typing each field is inherently slow for this multi-recipe task.

---

## Scoring Implications

Even if the LLM error had not occurred, the scoring may have yielded partial credit at best:
1. **Eggplant Parmesan**: Added but with corrupted description ("DescriptionAn ideal..."). Depending on scoring criteria, this might not count.
2. **Baked Cod with Lemon and Dill**: Likely saved successfully (SAVE clicked at turn 33) but not confirmed visible in the list at the final screenshot.
3. **Chickpea Vegetable Soup**: Not started.

The `scoring_context.json` shows `ui_element_count: 0`, suggesting the scoring script could not find the expected elements.

---

## Failure Classification

| Dimension | Classification |
|-----------|---------------|
| **Primary** | Infrastructure - LLM provider SSE error |
| **Agent behavior** | Correct strategy, reasonable execution |
| **Framework gap** | No retry on transient LLM errors |
| **Data quality** | Description field hint/label contamination |
| **Efficiency** | ~11 turns per recipe is borderline for 3-recipe tasks |

---

## Recommendations

### P0: Framework - LLM Error Retry
- Add retry logic (1-3 retries with exponential backoff) for transient LLM errors (SSE errors, 5xx, timeouts)
- This single change would have prevented this failure entirely

### P1: Agent - Use `clear=true` on All Text Fields
- The description field contamination ("DescriptionAn ideal...") suggests the agent should always use `clear=true` when typing into potentially pre-populated fields
- The agent already does this for the title field (turns 15, 26) but not for description/other fields

### P2: Agent - Verify Saves
- After clicking SAVE, the agent should verify it returned to the recipe list and confirm the new recipe appears
- Currently the agent just assumes SAVE worked and moves on

### P3: Efficiency - Batch Field Entry
- Turn 17 attempted parallel field entry (3 type actions in one turn) which is the right idea but may have had execution issues
- Investigate whether parallel tool calls for independent fields can reduce turn count

### P4: Agent - Image Discovery Strategy
- The agent spent 10 turns finding the image, including a failed `open_app("Simple Gallery Pro")` and two shell commands
- Consider adding a more direct strategy: open gallery, browse recent files, or use `shell` to locate the file first then open it directly

---

## Key Metrics

| Metric | Value |
|--------|-------|
| Turns to find image | 10 |
| Turns for recipe 1 | 11 (turns 12-22) |
| Turns for recipe 2 | 11 (turns 23-33) |
| Turns for recipe 3 | 1 (terminated at turn 35) |
| Tool failures | 1 (open_app "Simple Gallery Pro") |
| LLM errors | 1 (SSE error at turn 35, fatal) |
| Recipes successfully added | 2 of 3 (Eggplant Parmesan with corrupted desc, Baked Cod likely saved) |
| Recipes incomplete | 1 of 3 (Chickpea Vegetable Soup not started) |
