# RecipeAddMultipleRecipesFromImage -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 16 | **Reason**: Error | **Duration**: 121s
**Goal**: Add the recipes from recipes.jpg in Simple Gallery Pro to the Broccoli recipe app.

## Root Cause
**Category**: Capability Gap (Vision/OCR)
**Summary**: The task requires reading recipe text from an image file (recipes.jpg) and transcribing it into the Broccoli recipe app. The agent operates without vision/screenshot mode (`screenshot_attached: false` on every LLM request), meaning it receives only the accessibility tree -- which for an image viewer contains just the element class (`ImageView`) and filename (`recipes.jpg`), not the visual content of the image. The agent correctly identified this fundamental limitation after 16 turns of exploration and reported failure. This is an unsolvable task under the current a11y-only perception pipeline without OCR or vision capabilities.

**Secondary Issue**: Two shell commands were policy-rejected in turns 9-10, wasting 2 turns. The agent attempted `find /sdcard -type f -name "*recipe*"` and `find /sdcard -name "*.jpg" -o -name "*.jpeg"` which both returned empty results or were blocked. The `recipes.jpg` file was visible in the Gallery UI (DCIM folder) but not findable via shell, suggesting the file may reside in a media-scoped storage path not directly accessible via `/sdcard`.

## Turn-by-Turn Analysis

### Turn 1
- **Action**: `open_app { app_name: "Simple Gallery Pro" }`
- **Result**: Failed -- "App not found: 'Simple Gallery Pro'. Similar apps: Gallery, Android Agent, Retro Music, Voice Search."
- **Assessment**: Reasonable first attempt. The task says "Simple Gallery Pro" but the installed app is named "Gallery" (package: `com.simplemobiletools.gallery.pro`). The open_app tool correctly suggested "Gallery" as an alternative.

### Turn 2
- **Action**: `open_app { app_name: "Gallery" }`
- **Result**: Success -- launched Gallery (`com.simplemobiletools.gallery.pro`). Post-action screen shows the Gallery main view with a "Search folders" bar, "Open camera" button, and one folder: "DCIM | 1".
- **Assessment**: Good recovery from the Turn 1 failure. The agent adapted to the suggested app name.

### Turn 3
- **Action**: `mobile_action { action: "scroll", direction: "down" }` -- scroll down to find more folders
- **Result**: Success -- scrolled down. Only DCIM folder visible (same content, 1 item).
- **Assessment**: Reasonable exploratory action. The agent was looking for a recipes-specific folder but only DCIM exists.

### Turn 4
- **Action**: `mobile_action { action: "click", element_index: 4 }` -- click on "DCIM | 1" folder
- **Result**: Success -- opened the DCIM folder. Post-action screen shows folder contents with image thumbnails.
- **Assessment**: Good -- the agent navigated into the only available folder to look for `recipes.jpg`.

### Turn 5
- **Action**: `mobile_action { action: "scroll", direction: "down" }` -- scroll down in DCIM folder
- **Result**: Success -- scrolled down.
- **Assessment**: Exploratory scroll looking for more images.

### Turn 6
- **Action**: `mobile_action { action: "click", element_index: 4 }` -- click on an image thumbnail
- **Result**: Success -- opened an image. Post-action screen shows an ImageView with text "recipes.jpg" in the a11y tree, confirming this is the target image file. The a11y tree only shows: `ImageView` with text containing "recipes.jpg", status bar elements, and bottom toolbar buttons (Edit, Properties, etc.).
- **Assessment**: Good -- the agent found and opened `recipes.jpg`. However, this is where the fundamental problem becomes apparent: the a11y tree contains no OCR text from the image, just the filename and UI controls.

### Turn 7
- **Action**: `shell { command: "ls -la /sdcard/DCIM/" }`
- **Result**: Success -- exit=0, output: "total 0" (empty directory listing).
- **Assessment**: The agent pivoted to shell to try to find the file path, likely hoping to use an OCR command or file inspection tool. However, the DCIM directory appears empty from shell, suggesting the image is stored in a scoped storage location not directly accessible via this path.

### Turn 8
- **Action**: `shell { command: "find /sdcard -name \"recipes.jpg\" 2>/dev/null" }`
- **Result**: Success -- exit=0, no output (file not found).
- **Assessment**: Continued shell exploration. The file is not findable via `find` on `/sdcard`, confirming scoped storage isolation. The agent cannot directly access the raw image bytes.

### Turn 9
- **Action**: `shell { command: "find /sdcard -type f -name \"*recipe*\" 2>/dev/null" }` (attempted)
- **Result**: POLICY_REJECTION -- tool arbitration dropped this shell call entirely.
- **Assessment**: Wasted turn. The policy rejected the command (likely duplicate/suspicious pattern after two similar `find` commands). No tool was executed this turn.

### Turn 10
- **Action**: `shell { command: "find /sdcard -name \"*.jpg\" -o -name \"*.jpeg\" 2>/dev/null | head -20" }` (attempted)
- **Result**: POLICY_REJECTION -- tool arbitration dropped this shell call entirely.
- **Assessment**: Second consecutive wasted turn. The agent was caught in a search loop with increasingly broad `find` queries. The anti-loop policy correctly identified and blocked the repetitive pattern, but the agent was not informed why the tool was rejected, leading it to silently continue.

### Turn 11
- **Action**: `mobile_action { action: "click", element_index: 8 }` -- click "Properties" button in Gallery image viewer
- **Result**: Success -- opened the Properties dialog. A11y tree shows "recipes.jpg | Properties | Name" -- the dialog displays file metadata (name, path, resolution, etc.) but no image content or OCR text.
- **Assessment**: Creative attempt to extract information from the image metadata. However, Properties only shows file metadata (name, size, resolution, etc.), not the visual content of the image.

### Turn 12
- **Action**: `mobile_action { action: "click", element_index: 16 }` -- close the Properties dialog
- **Result**: Success -- dismissed the dialog, returned to the image viewer.
- **Assessment**: Correct cleanup action.

### Turn 13
- **Action**: `mobile_action { action: "click", element_index: 11 }` -- click "Edit" button to look for OCR/text extraction
- **Result**: Success -- opened an edit/share picker (likely an intent chooser for editing the image). Post-action screen shows the `android` package, indicating a system dialog appeared.
- **Assessment**: Another creative approach, hoping the edit flow might expose text content. However, an edit intent sends the image to an image editor, not an OCR tool.

### Turn 14
- **Action**: `system_button { button: "back" }` -- press Back to dismiss the edit picker
- **Result**: Success -- returned to the Gallery image viewer.
- **Assessment**: Correct recovery from the dead-end edit picker.

### Turn 15
- **Action**: `shell { command: "find /sdcard -name \"*.jpg\" -o -name \"*.jpeg\" 2>/dev/null | head -20" }`
- **Result**: Success -- exit=0, no output (no jpg files found on `/sdcard`).
- **Assessment**: Redundant search (same command as Turn 10, which was policy-rejected). This time it was allowed but still returned empty results.

### Turn 16
- **Action**: `complete_task { status: "failure", answer: "Cannot read image without vision mode. The task requires extracting recipe text from recipes.jpg, but I don't have vision capabilities to perform OCR on the image. The image is viewable in the Gallery app, but I cannot extract the text content from it to add to the Broccoli recipe app." }`
- **Result**: Task completed with failure status.
- **Assessment**: Correct decision. The agent accurately identified the root limitation (no vision/OCR capability) and provided a clear, honest explanation rather than hallucinating recipe content.

## Failure Analysis
1. **Missing capability (blocking)**: The agent has no vision/screenshot interpretation capability in this configuration (`screenshot_attached: false`). The a11y tree for an ImageView contains only the filename and UI bounds, not the pixel content. Without OCR or vision-enabled LLM input, extracting text from an image is impossible.
2. **File not accessible via shell**: The `recipes.jpg` file was visible in Gallery but not discoverable via `find /sdcard`. This is likely due to Android's scoped storage model where media files may be indexed by MediaStore but not directly accessible at predictable filesystem paths (e.g., stored in `/data/media/` or accessed via content URIs).
3. **Policy rejection without feedback (minor)**: Turns 9 and 10 were wasted because shell commands were silently policy-rejected. The agent received no feedback about why the tools were dropped, so it could not adapt its strategy. This is an anti-loop system issue -- the agent should receive an explicit "tool rejected: repetitive pattern" message.
4. **Wasted exploration (minor)**: Turns 3, 5, 7, 8, 11-15 were various attempts to work around the missing vision capability (scrolling, shell searches, Properties, Edit). While creative, none could have succeeded given the fundamental capability gap.

## What Worked
- **App name recovery** (Turn 1-2): The agent correctly recovered from the "Simple Gallery Pro" not-found error by using the suggested "Gallery" name.
- **Image navigation** (Turns 3-6): The agent successfully navigated to the DCIM folder and opened `recipes.jpg` within 4 turns.
- **Accurate self-assessment** (Turn 16): The agent correctly identified the fundamental limitation ("cannot read image without vision mode") rather than guessing or hallucinating recipe content. This is the right behavior for an agent -- reporting inability rather than fabricating data.
- **Creative workaround attempts**: The agent tried multiple strategies (shell file search, image Properties metadata, Edit intent) before concluding the task was impossible. This shows good problem-solving even though none succeeded.

## Recommendations

### P0 -- Enable Vision/Screenshot Mode for Image-Reading Tasks
- When the task involves extracting text from images, enable `screenshot_attached: true` so the LLM receives the actual screenshot pixels. This would allow the model to read the recipe text directly from the Gallery image viewer.
- Alternatively, implement an OCR tool (e.g., `ocr_image { file_path }`) that the agent can invoke to extract text from image files on-device, independent of the LLM's vision capability.

### P1 -- Silent Policy Rejection Feedback
- When tool arbitration drops a tool call due to `POLICY_REJECTION`, inject a tool result message back to the agent explaining that the tool was rejected and why (e.g., "Shell command rejected: repetitive search pattern detected. Try a different approach."). Currently the agent receives no feedback and wastes subsequent turns retrying variants of the same rejected command.

### P2 -- Early Capability Detection
- Add a pre-flight check: if the task goal references reading content from an image/photo/picture and vision mode is disabled, the agent should immediately report the limitation rather than spending 16 turns exploring dead ends. This could be a system prompt instruction: "If the task requires reading visual content from images and you do not have vision/screenshot access, report this limitation immediately."

### P3 -- Scoped Storage Awareness
- The agent should be aware that on modern Android, media files may not be accessible at simple `/sdcard` paths. If `find /sdcard -name "file.jpg"` returns nothing but the file is visible in Gallery, the agent could try `content query --uri content://media/external/images/media` via shell to locate the file's actual path through MediaStore.
