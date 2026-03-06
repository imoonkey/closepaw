# Browser Click Execution Analysis

**Scope**: `BrowserDraw` and `BrowserMultiply` from eval run `20260306_003753`

**Question**: Did some `click` turns report success even though the UI did not actually respond?

## Short Answer

Yes, but the evidence points to a **narrow Files-row click false-success pattern**, not a broad click regression.

The strongest signal is the same one in both tasks:
- A `click` on the visible `task.html` row in Files reports `Success: Clicked ... via node_action_click`
- The immediate screenshot and post-action screen stay effectively unchanged
- A later `long_press` on the **same target** does work and enters selection mode

That is a real execution-layer smell: the click is being accepted by the platform, but it is not producing the intended semantic effect of opening the file.

At the same time, several other "failed clicks" in these two runs are **not** good evidence of executor regression:
- `double_click` is simply unsupported and failed validation
- some Chrome/chooser transitions were real, but post-action a11y collapsed and failed to reflect them
- Chrome first-run buttons were visually confounded by the agent/takeover overlay near the bottom of the screen

## Method

I compared, for both tasks:
- `tool_call_args`
- `tool_result`
- `tool_observation_screen`
- paired screenshots before/after suspicious turns

The goal was to separate:
1. **true false-success**: tool says click succeeded, but the screen did not respond
2. **observation mismatch**: click likely worked, but post-action a11y/sanitized screen did not capture the new state
3. **non-execution issues**: unsupported action or overlay-obscured target

## High-Confidence Findings

### 1. Files row single-click is suspicious in both tasks

#### BrowserDraw turn 2

- Tool call: `click(element_index=17)`
- Target node: `task.html | 6.50 kB | 00:42`, `CardView`, `clickable=true`, center `(296,978)`
- Tool result: `Success: Clicked (296,978) via node_action_click`
- Evidence of no effect:
  - screenshot before and after are visually unchanged
  - post-action `tool_observation_screen` matches the same Files screen
- Follow-up evidence:
  - later `long_press(element_index=17)` does change state and enters `1 selected`

#### BrowserMultiply turn 2

- Tool call: `click(element_index=17)`
- Target node: `task.html | 00:45, 2.23 kB, HTML document`, `LinearLayout`, `clickable=true`, center `(540,763)`
- Tool result: `Success: Clicked (540,763) via node_action_click`
- Evidence of no effect:
  - screenshot before and after are visually unchanged
  - post-action `tool_observation_screen` stays on the same Files screen
- Follow-up evidence:
  - later `long_press(element_index=17)` succeeds and enters `1 selected`

**Interpretation**:

This is the strongest evidence in the traces that a click was **accepted** but did **not** produce the expected app behavior. It is likely specific to the Files row/container open behavior, not a generic click failure across apps.

## Medium-Confidence Findings

### 2. BrowserDraw has additional Files click-no-effect turns

Two later BrowserDraw retries look like the same pattern:

- **Turn 18**: coordinate click on the left HTML icon
- **Turn 21**: coordinate click on the filename text area

Both keep the UI on the same Files list state with no useful transition. These are consistent with "tap accepted but file did not open", though the root cause may still be mixed with the developer overlay contamination in this run.

### 3. `double_click` is not a regression signal

In both tasks, turn 3 used `double_click` and failed validation:

- `Error: Validation failed: Unknown action: 'double_click'. Valid: click, long_press, scroll, swipe, type`

This is not a click executor regression. It is simply an unsupported action type being emitted by the model.

## Not Good Evidence Of Click Regression

### 4. BrowserMultiply turn 12 looked unchanged in a11y, but the screenshot shows it did work

- Tool call: click `task.html` again after clearing selection mode
- Post-action `tool_observation_screen` still looked like Files
- But the next screenshot shows Chrome loading `content://...`

**Interpretation**:

This turn is better explained as **post-action observation lag/collapse**, not as a no-op click. The click likely succeeded; the a11y snapshot just did not capture the transition correctly.

### 5. Open-with / chooser clicks are partially hidden by observation collapse

In `BrowserMultiply`, turns 6-8 go through:
- `Open with`
- `Chrome`
- Chrome first-run

The post-action a11y for some of these turns collapses to only a few status-bar nodes, which makes them look like no-ops if you only read `tool_observation_screen`. But the screenshot sequence shows actual progression into Chrome.

`BrowserDraw` has a similar problem around turns 8-10.

**Interpretation**:

These turns are not clean evidence of executor failure. They show that the observation layer is weak around chooser / Chrome transitions.

### 6. Chrome first-run button clicks are confounded by the takeover overlay

In `BrowserMultiply`, the first click on `Use without an account` does not advance. But the screenshot shows the bottom action area is visually entangled with the agent overlay/takeover UI. A later retry by text targeting succeeds.

`BrowserDraw` has the same problem, amplified by the developer overlay pollution in that run.

**Interpretation**:

These turns are better classified as **target obstruction / environment contamination**, not a core click regression.

## Overall Diagnosis

### Likely real execution issue

- **Files row/container click false-success**
- Scope observed here:
  - `BrowserDraw` turn 2
  - `BrowserMultiply` turn 2
  - likely BrowserDraw turns 18 and 21 as weaker variants

### Not enough evidence for global regression

I do **not** see evidence that clicks in general have regressed across the system. The traces fit a narrower picture:
- Files row open-on-click is unreliable
- chooser / Chrome transitions have poor post-action observability
- some bottom-screen clicks were confounded by overlays

## Practical Implications

### For debugging

The next clean reproduction should isolate the Files row behavior on a clean emulator state:
1. open Files to Downloads with `task.html` visible
2. compare:
   - adb tap on the row center
   - a11y node click on the row center
   - a11y gesture click on the same coordinates
   - long press on the row center
3. also compare tapping:
   - row container center
   - file icon
   - filename text

This will tell us whether the problem is:
- node-action click specifically
- the chosen target region
- Files app semantics for that row

### For instrumentation

Current traces show why tool-result text alone is not enough. A useful improvement would be to explicitly record click effectiveness as:
- action accepted
- screen changed / unchanged
- screenshot changed / unchanged

That would make these regressions much easier to spot.

## Bottom Line

If you are asking "did some clicks say success but not really click the thing?" the answer is:

- **Yes** for the Files `task.html` row click pattern
- **Probably no** for many of the other suspicious browser turns; those are more likely observation collapse or overlay interference

So there is something worth debugging in click execution, but it looks **localized**, not like a broad recent regression across every click target.
