# ExpenseAddMultipleFromGallery — Cog-Tune Analysis

## Task
- **Goal**: Add the expenses from expenses.jpg in Simple Gallery Pro to pro expense.
- **Result**: FAIL (score=0.0, 30 turns)
- **Run ID**: aw_20260226_134903_ExpenseAddMultipleFromGallery_9_0

## Turn-by-Turn Summary

**Turns 1-2: Opening Gallery (slow start)**
- T1: open_app → Open Simple Gallery Pro
- T2: open_app → Open Gallery app (suggested alternative)

**Turns 3-6: Trying to read expenses.jpg (struggling)**
- T3: scratchpad ×6 → Store expense data from image (but image not yet open)
- T4-5: mobile_action → Scroll down to see full expenses.jpg
- T6: mobile_action → Swipe up to see third expense

**Turns 7-8: Navigate to image**
- T7: mobile_action → Click DCIM folder
- T8: mobile_action → Click expenses.jpg to view it

**Turns 9-25: Extracting expense data via scratchpad (extremely slow)**
- T9-15: scratchpad → Repeatedly storing first expense name "Dress" (7 turns!)
- T16: scratchpad → Store second expense name "Furnishing"
- T17-18: scratchpad → Store amounts ($436.35, $350.07)
- T19: scratchpad → Store first expense name again
- T20-25: scratchpad → Store categories and notes for all three expenses

**Turns 26-30: Opening Pro Expense and starting entry (ran out of turns)**
- T26: open_app → Open "Simple Expense" (wrong app name)
- T27: open_app → Open "Pro Expense"
- T28: mobile_action → Click FAB to add expense
- T29: mobile_action → Type "Dress" in Name field
- T30: mobile_action → Click Amount field

## Failure Analysis
- **Root Cause**: reasoning + tool_limitation
- **Description**: The agent wasted the vast majority of turns (17 out of 30) on scratchpad operations trying to extract expense data from expenses.jpg. Key issues:
  1. **Excessive scratchpad usage**: Agent used scratchpad 17+ times to store just 3 expenses' data, often re-storing the same field
  2. **Late app switch**: Didn't open Pro Expense until turn 27 (only 3 turns left)
  3. **Image reading difficulty**: In hybrid mode, the agent can see the screenshot but struggles to reliably extract structured data from an image of a table
  4. **Wrong app name**: Turn 26 tried "Simple Expense" instead of "Pro Expense"
- **Critical Turn**: Turn 9 — the agent should have extracted all expense data in a single scratchpad call instead of making 17 separate calls

## Suggested Improvements
1. **Scratchpad efficiency tip**: Add tip: "When reading data from images, extract ALL information in a single scratchpad call with structured format, not one field at a time."
2. **Task planning**: Agent should plan the workflow upfront: (1) read image → extract all data in one pass, (2) switch to expense app, (3) enter each expense. The current approach of incrementally reading data wastes too many turns.
3. **App name knowledge**: Add tip about the expense app being called "Pro Expense" (not "Simple Expense")
4. **Turn budget awareness**: Agent should be aware that multi-app tasks (read from one app, enter in another) require efficient use of turns — spend max ~10 turns on data extraction, save 20 for data entry.
