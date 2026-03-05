# Swipe Action Eval Analysis - Round 20260219_124436

## Executive Summary

**29 swipe actions** across **7 tasks** in the eval run. All swipes succeeded at the tool
level (100% `tool_success`), but **19/29 (65.5%)** produced no screen change, hitting the
"screen content unchanged" warning. Swipe is the single largest source of wasted turns.

## Overall Stats

| Metric | Value |
|--------|-------|
| Total swipe actions | 29 |
| Tool-level success | 29/29 (100%) |
| Screen actually changed | 10/29 (34.5%) |
| Screen unchanged (boundary warning) | 19/29 (65.5%) |
| Tasks with swipes that succeeded | 3/7 (42.9%) |
| Tasks with swipes that failed | 4/7 (57.1%) |

## Per-Task Breakdown

### 1. ExpenseAddSingle (FAILED - MaxTurnsReached)

**Goal**: Scroll horizontal category RecyclerView to find "Health Care".

| Turn | Direction | Coords | Screen Changed? | Notes |
|------|-----------|--------|-----------------|-------|
| 5 | left (medium) | (253,822) -> (63,822) | NO | 190px swipe, too short |
| 6 | right (medium) | (63,822) -> (253,822) | YES | Reversed direction |
| 10 | right (medium) | (63,822) -> (253,822) | YES | Same coords again |
| 15 | right (medium) | (63,822) -> (253,822) | NO | Hit boundary |
| 16 | left (long) | (396,822) -> (63,822) | NO | 333px but still no effect |

**Root Cause**: Directional swipe origin resolves to RecyclerView center (~x=158), which is
near screen left edge. With `delta = distancePx / 2`, the symmetric spread from origin gets
**clamped by safe insets**, reducing effective motion to ~190px instead of intended ~389px.
The horizontal RecyclerView needs longer, full-width swipes but the algorithm can't deliver them.

### 2. FilesMoveFile (FAILED - Error)

**Goal**: Scroll vertical file list to find "Podcasts" folder.

| Turn | Direction | Coords | Screen Changed? | Notes |
|------|-----------|--------|-----------------|-------|
| 4 | down (medium) | (539,688) -> (539,1520) | NO | Swipe DOWN on a list = wrong direction to scroll down |
| 5 | up (long) | (539,1832) -> (539,376) | NO | 1456px jump, boundary hit |

**Root Cause**: Direction confusion. Agent says "Scroll down to find Podcasts" but uses
`direction="down"` which executes a **finger-down gesture** (swipe from top to bottom).
In our system `direction="up"` scrolls DOWN. The agent got confused by the counter-intuitive
direction mapping. Additionally, the list may already be showing all items.

### 3. RecipeAddSingleRecipe (FAILED - MaxTurnsReached)

**Goal**: Reveal hidden form fields (servings, prep time, ingredients, directions).

| Turn | Direction | Coords | Screen Changed? |
|------|-----------|--------|-----------------|
| 7 | up (medium) | (63,1312) -> (63,480) | NO |
| 8 | down (medium) | (63,480) -> (63,1312) | NO |
| 10 | explicit | (540,1200) -> (540,400) | NO |
| 11 | explicit | (540,1400) -> (540,200) | NO |
| 12 | explicit | (540,400) -> (540,1400) | NO |
| 20 | explicit | (540,1387) -> (540,400) | NO |

**ALL 8 swipes failed** - zero screen changes.

**Root Cause**: The form is likely **not scrollable** via gesture. The fields might require
tapping to navigate (e.g., click on a "more fields" button or tab through). Or a text input
has focus and the keyboard is open, intercepting gestures. The agent wasted 8 turns (turns
7-12, then again at turn 20) because it lacks a fallback strategy when scrolling fails
repeatedly.

**Missing**: No `AccessibilityNodeInfo.ACTION_SCROLL_FORWARD` fallback. A native a11y
scroll action on the scrollable parent would have worked where gesture dispatch failed.

### 4. SystemBluetoothTurnOnVerify (SUCCESS)

| Turn | Direction | Coords | Screen Changed? |
|------|-----------|--------|-----------------|
| 8 | up (short) | (539,1260) -> (539,948) | NO |

Single exploratory swipe, didn't change content but agent found Bluetooth toggle anyway.

### 5. SystemBrightnessMaxVerify (SUCCESS)

| Turn | Direction | Coords | Screen Changed? |
|------|-----------|--------|-----------------|
| 2 | up (medium) | (540,1905) -> (540,1073) | YES |

Clean single scroll down in Settings. Worked perfectly.

### 6. SystemBrightnessMinVerify (SUCCESS - 10 swipes, very wasteful)

| Turn | Type | Coords | Screen Changed? | Notes |
|------|------|--------|-----------------|-------|
| 1 | explicit | (540,50) -> (540,300) | YES | Pull down notification shade |
| 2 | explicit | (540,10) -> (540,500) | YES | Expand shade further |
| 3 | explicit | (540,400) -> (540,900) | YES | Expand QS panel |
| 5 | explicit | (540,420) -> (42,420) | NO | Slider drag - missed thumb |
| 6 | explicit | (900,420) -> (100,420) | NO | Slider drag - still missed |
| 7 | explicit | (1038,420) -> (42,420) | YES | Finally found right starting coord |
| 9 | explicit | (540,50) -> (540,800) | YES | Re-open shade |
| 10 | explicit | (540,463) -> (540,900) | YES | Expand QS |
| 11 | explicit | (540,500) -> (540,1000) | NO | Already expanded |
| 12 | explicit | (540,284) -> (540,800) | NO | Already expanded |

**Key insight**: Slider drags (turns 5-7) show the agent needs to start from the slider
thumb position, not arbitrary coordinates. Turns 5-6 started at wrong x-position and
failed. Turn 7 used the full track width (1038 -> 42) and succeeded.

### 7. SystemWifiTurnOffVerify (FAILED)

| Turn | Direction | Coords | Screen Changed? |
|------|-----------|--------|-----------------|
| 6 | up (short) | (539,1260) -> (539,948) | NO |
| 10 | down (short) | (539,948) -> (539,1260) | YES |

Agent claimed GoalAchieved but scripted check reported failure. Swipe issues not the
primary failure cause here.

## Critical Failure Patterns

### Pattern 1: Edge-Clamped Directional Swipes
When the swipe origin resolves near a screen edge (e.g., RecyclerView positioned on left),
the symmetric `origin +/- delta` computation gets clamped by safe insets, dramatically
reducing the effective swipe distance. This is the **#1 technical issue**.

### Pattern 2: No Scroll-Action Fallback
The swipe is gesture-only with no `ACTION_SCROLL_FORWARD/BACKWARD` fallback. When a
container doesn't respond to gesture-based scrolling (keyboard open, custom views, etc.),
there's no alternative. Click has a node-action + gesture fallback chain; swipe has none.

### Pattern 3: Repeated Futile Swipes
Agent doesn't escalate strategy after consecutive "unchanged" warnings. RecipeAddSingleRecipe
attempted 8 swipes (turns 7-20), all unchanged. There's no mechanism to:
- Cap retries on failing swipes
- Suggest alternative navigation
- Abandon scroll and try tap-based navigation

### Pattern 4: Direction Confusion in Prompts
FilesMoveFile agent said "Scroll down" but used `direction="down"` which is a DOWN gesture
(finger moves down, content moves UP). The counter-intuitive mapping `direction="up"` =
scroll DOWN is a persistent confusion source.

### Pattern 5: Slider Drag Imprecision
Brightness slider needed exact thumb-position start coordinates. The agent guessed wrong
twice before finding the right starting position. No mechanism to identify slider thumb
bounds from a11y tree.
