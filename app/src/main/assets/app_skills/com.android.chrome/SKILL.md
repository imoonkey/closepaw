---
name: com.android.chrome
description: App-specific guidance for Chrome browser.
---

# Chrome Browser Skill

## Opening HTML Files from Files App
1. Open the Files app and navigate to Downloads
2. Use `long_press` (NOT single click) on the file row — single click may silently fail
3. When prompted to choose an app, select Chrome
4. If Chrome shows a first-run screen (sign-in, sync, etc.), dismiss all prompts quickly — tap "No thanks", "Skip", or the X button

## Chrome First-Run Dismissal
Chrome may show multiple first-run prompts on fresh installs:
- "Welcome to Chrome" → tap "Accept & continue"
- "Turn on sync?" → tap "No thanks"
- "Notifications" → tap "No thanks"
Dismiss these as fast as possible to save turns.

## Drawing Tasks (Canvas Pages)
When the page shows a canvas with target colors at the top and a color palette below:
1. **First, scroll down in Chrome** if the canvas or target colors are not fully visible — the agent UI overlay at the bottom may obscure content. Swipe up on the page to reveal more.
2. Look at the screenshot to identify the 3 target colors shown at the top of the page
3. For each target color, find and tap the matching color button in the palette below the canvas
4. After selecting a color, draw on the canvas using `swipe` gestures (swipe within the canvas area)
5. Repeat for all 3 target colors — each color must appear on the canvas
6. After drawing with all required colors, tap the Submit button
7. If you cannot see the target colors or canvas, try scrolling the page or using the accessibility tree to identify color elements

## Maze Tasks
When the page shows a grid maze with an X and directional buttons:
1. Use the screenshot to see the current maze layout, walls, and X position
2. Plan a path from X's position to the goal ($) in the bottom-right
3. Use the direction buttons (Up, Down, Left, Right) to move X
4. After each move, check the screenshot to verify X moved correctly
5. Continue until X reaches the goal cell
