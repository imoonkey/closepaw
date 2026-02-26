# ExpenseAddMultipleFromGallery - Cog-Tune Analysis

## Run Metadata
| Field | Value |
|---|---|
| Run ID | `aw_20260225_225734_ExpenseAddMultipleFromGallery_9_0` |
| Model | qwen3.5 |
| Goal | Add the expenses from expenses.jpg in Simple Gallery Pro to pro expense. |
| Completion | MaxTurnsReached |
| Task Status | **failure** |
| Turns (actual) | 30 (logcat) / 30 (runner-reported) |
| Duration | 189s |

## Root Cause
**Category: Cognition - Cross-App Data Extraction (Image-to-Text)**
The agent could not extract expense data from the expenses.jpg image. The fundamental challenge is that the agent operates primarily through accessibility tree perception, which cannot read text from images. The agent found the image file in Simple Gallery Pro but had no way to read its contents (expense data) since the image pixels are opaque to accessibility services. The agent spent 30 turns trying various approaches (swiping through gallery, checking properties, attempting to share) but never successfully extracted the expense information needed to enter it into Pro Expense.

## Turn-by-Turn Analysis (from logcat)
| Turn | Tool | Action | Target | Result |
|------|------|--------|--------|--------|
| 1 | open_app | open | Simple Gallery Pro | App not found |
| 2 | open_app | open | Gallery | Opened gallery app |
| 3 | mobile_action | click | DCIM folder (idx 11) | Browsed gallery |
| 4 | mobile_action | click | search field (idx 6) | Opened search |
| 5 | mobile_action | type | "expenses" (idx 6) | Searched for file |
| 6 | system_button | enter | -- | Executed search |
| 7 | mobile_action | click | first image (idx 10) | Opened an image |
| 8 | mobile_action | click | Properties (idx 9) | Checked image properties |
| 9 | mobile_action | click | OK (idx 16) | Dismissed properties |
| 10 | shell | find | /sdcard -name "expenses.jpg" | File not found |
| 11 | shell | ls | /sdcard/DCIM/ | Listed directory |
| 12-15 | mobile_action | swipe | left through images | Browsing images |
| 16 | mobile_action | click | back (idx 6) | Returned to gallery grid |
| 17-19 | mobile_action | swipe | left through images | More browsing |
| 20 | mobile_action | click | back (idx 6) | Back again |
| 21-22 | mobile_action | click | various | Toggle filename view |
| 23 | mobile_action | click | expenses.jpg (idx 10) | Found and opened expenses.jpg |
| 24 | mobile_action | long_press | image (idx 1) | Tried to interact with image |
| 25 | mobile_action | click | Share (idx 13) | Opened share menu |
| 26 | mobile_action | scroll | down (idx 5) | Scrolled share options |
| 27 | system_button | back | -- | Closed share menu |
| 28 | shell | find | /sdcard -name "*.jpg" | Listed all JPGs |
| 29 | mobile_action | click | More options (idx 10) | Tried more options |
| 30 | -- | MaxTurnsReached | -- | Out of turns |

## Key Observations
1. **Image content inaccessible**: The core issue is that accessibility-based perception cannot read text from images. The agent needs hybrid perception (screenshot + OCR) to extract expense data from an image file.
2. **App name mismatch**: The agent first tried "Simple Gallery Pro" which didn't match the installed app name. It recovered by opening "Gallery" instead.
3. **Long search phase**: Turns 3-22 (20 turns!) were spent finding and opening the correct image file, which could have been done more efficiently.
4. **No OCR capability**: The agent never attempted to use shell commands for OCR (e.g., `tesseract` or similar) to extract text from the image.
5. **1 tool failure**: One of the 30 tool calls failed, likely a scroll or interaction attempt.

## Recommendations
1. **Hybrid perception for image tasks**: Tasks requiring reading image content need screenshot-based perception with visual understanding. Add task-level perception mode configuration.
2. **Shell-based OCR**: Consider adding OCR capability via shell commands (like running `tesseract` on the device) as a fallback for extracting text from images.
3. **Efficient file finding**: Instead of browsing through gallery UI, use `shell find /sdcard -name "expenses.jpg"` as the FIRST step to locate the file, then navigate directly to it.
4. **Task feasibility detection**: Agent should recognize early when a task requires capabilities it doesn't have (reading image content) and either switch strategies or report inability.
