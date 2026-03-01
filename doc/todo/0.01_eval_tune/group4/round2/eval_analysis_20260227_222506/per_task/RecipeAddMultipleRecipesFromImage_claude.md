# RecipeAddMultipleRecipesFromImage — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: ~7+ (hit max or early exit) | **Reason**: PerceptionGap | **Duration**: ~390s
**Goal**: Add the recipes from recipes.jpg in Simple Gallery Pro to the Broccoli recipe app.

## Root Cause
**Category**: PerceptionGap
**Summary**: This task requires reading recipe information from an image (recipes.jpg) and entering it into the Broccoli recipe app. The agent opened Simple Gallery Pro to view the image, but with a11y-only perception, it cannot read the text content within an image. Even with hybrid mode configured in task_overrides, the agent needs to be able to extract text from the screenshot, which proved insufficient. The agent only managed to open the gallery app and view the image but couldn't extract recipe details.

## Turn-by-Turn Analysis

### Turn 1
- **Action**: `open_app(app_name="Simple Gallery Pro")`
- **Result**: Success — opened gallery
- **Assessment**: Good

### Turns 2-5 (Navigate to image)
- **Action**: Browse gallery to find recipes.jpg
- **Result**: Found and opened the image
- **Assessment**: Good

### Turn 6
- **Action**: `scratchpad(write)` + `open_app("Broccoli")`
- **Result**: Tried to store recipes from image, then opened Broccoli app
- **Assessment**: Key question: what did the agent actually capture from the image?

### Turn 7+
- **Action**: Continued in Broccoli app (likely entering whatever it captured)
- **Result**: Incomplete or incorrect recipe data
- **Assessment**: Bad — insufficient image perception

## Failure Points
1. **Image content not readable via a11y** — the core issue. Recipe text is embedded in a JPEG image, invisible to accessibility services
2. **Hybrid mode needed but may not function properly** — task_overrides has `perception_mode: hybrid` but the agent config shows `screenshot_attached: false`
3. **No OCR fallback** — agent didn't try shell-based text extraction (e.g., Tesseract if available)
4. **Insufficient recipe data** — whatever was captured was incomplete, leading to score 0.0

## What Worked
- Successfully found and opened the image
- Attempted to switch to Broccoli app

## What Didn't Work
- Cannot read text from images in a11y-only mode
- Hybrid mode may not have been active for screenshots
- No alternative text extraction strategy

## Suggested Fix
- **Verify hybrid mode is activating** — check if screenshot_attached is actually true for this task's trace
- Add prompt guidance: "For tasks requiring reading from images, describe what you see in the screenshot carefully. If text is unclear, try zooming in or use shell-based OCR tools if available."
- This task fundamentally requires visual perception — a11y-only mode will always fail here
