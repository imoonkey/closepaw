# BrowserDraw — Cog-Tune Analysis

## Task
- **Goal**: Open the file task.html in Downloads in the file manager; when prompted open it with Chrome. Then create a drawing using the three colors shown at the top and hit submit.
- **Result**: FAIL (score=0.0, 30 turns)
- **Run ID**: aw_20260226_134903_BrowserDraw_2_0

## Turn-by-Turn Summary

**Turns 1-8: Navigation to task.html (successful but slow)**
- T1: open_app → Open Files app
- T2: mobile_action → Click task.html
- T3: mobile_action → Click "Just once" to open with Chrome
- T4: mobile_action → Proceed without Chrome account
- T5: wait → Wait for Chrome to load
- T6: mobile_action → Click "Use without an account" using coordinates
- T7: mobile_action → Click task.html again
- T8: mobile_action → Click "Just once" again

**Turns 9-30: Stuck trying to select colors and draw (looping)**
- T9: mobile_action → Select second color from palette
- T10-11: mobile_action → Select teal color from palette
- T12: scratchpad → Record three target colors
- T13-30: mobile_action → Repeatedly selecting teal color from palette (18 turns!)
- Agent keeps clicking the same color palette position over and over
- Never successfully draws on the canvas or switches colors
- Never clicks the Submit button

## Failure Analysis
- **Root Cause**: tool_limitation + reasoning
- **Description**: The BrowserDraw task requires drawing on an HTML canvas element, which is fundamentally challenging for accessibility-based interaction. Even with hybrid mode (screenshot + a11y), the agent:
  1. Cannot distinguish colors in the palette via accessibility tree alone (colors are visual)
  2. Gets stuck in a loop selecting the same color without ever performing a draw action on the canvas
  3. Never attempts to use swipe/drag actions to actually draw lines on the canvas
  4. The canvas element likely doesn't expose accessibility nodes for individual pixels/areas
- **Critical Turn**: Turn 12 — after using scratchpad to record colors, the agent should have started drawing but instead kept re-selecting the same color

## Suggested Improvements
1. **Canvas drawing tip**: Add tip: "For HTML canvas drawing tasks, use `swipe` actions on the canvas area to draw lines. Select a color first, then swipe across the canvas to draw. Repeat for each required color."
2. **Color selection strategy**: The agent needs screenshot-based perception to identify colors. Even in hybrid mode, the color palette buttons may not have text labels. Consider adding a tip about using coordinate-based clicks for unlabeled color buttons.
3. **This task may be fundamentally limited**: Drawing tasks require fine-grained visual perception and precise coordinate control that accessibility-only mode cannot support well. Hybrid mode helps but the agent still struggles with the draw-then-switch-color-then-draw workflow.
