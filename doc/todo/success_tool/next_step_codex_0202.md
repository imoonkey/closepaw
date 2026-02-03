# Next Step Recommendation (2026-02-02)

## Recommendation: add a first-class `scroll` action (directional, element-aware)

### Why this is the next step
- The biggest remaining usability gap is semantic scrolling. We only expose raw `swipe(start,end)` so the model must handcraft coordinates each time, which is brittle and wastes context.
- ToolName already includes `scroll`, the visualizer has `showScrollAsSwipe`, and Perceptor emits `scrollable` plus bounds. That makes scroll a low-risk, high-impact addition.
- Reference systems (M3A/AutoDevice/Minitap) all provide a direction-based scroll helper with clear semantics; we’re missing that feature in the tool layer.

### Scope (single, high-impact improvement)
Implement `mobile_action` action `scroll` with `direction` and optional target selectors, implemented as a computed `UIAction.Swipe` under the hood.

### Proposed API
- `action`: `scroll`
- `direction`: enum `up|down|left|right` (required)
- Optional targeting (reuse multi-selector pattern): `element_index` or `resource_id` (+ `resource_id_index`) or `bounds` (`x1..y2`) or `x/y`.
- Optional: `duration_ms` (default 350-450ms)

### Semantics (align to M3A/AutoDevice)
- Direction is **reading direction**: `scroll down` means “see content below”, so the gesture should swipe **up**.
- Default scroll distance: 60-70% of the target height (or screen height if no target); clamp to reasonable min/max to avoid tiny/huge jumps.
- If a target selector is provided, the scroll origin should be the target’s center (or a safe inset point). Otherwise use screen center.

### Implementation sketch (fits current codebase)
- Add `ScrollActionHandler` in `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/mobileaction/`.
- Parse selectors via `MultiSelectorTargeting` (or a thin helper) to obtain a bounds rect or coordinates.
- Compute `(startX,startY,endX,endY)` from `direction` + target bounds or display size (`AndroidPlatform.getDisplayInfo()`).
- Execute via `UIAction.Swipe` (no new platform action needed). Existing clamping in `AccessibilityPlatform.performSwipe()` handles out-of-bounds.
- Update `MobileActionTool` schema to include `direction` and register the `scroll` action.
- Update prompt guidance (`Turn.kt`) to prefer `scroll` for list navigation and reserve `swipe` for small nudges or precision moves.

### Tool output (useful recovery data)
- Include in success output: selector used, origin bounds (if any), and computed start/end. This helps the model avoid repeating bad scrolls.
- On failure (target not found), return available scrollable indices (already in snapshot) to guide fallback.

### Minimal tests
- Direction semantics: `scroll down` -> endY < startY, `scroll up` -> endY > startY, etc.
- Targeted vs screen-based scroll: start/end computed from element bounds when selector resolves.
- Clamping sanity: computed points stay within screen bounds before clamping.

### Defer for later
- Overlap-aware tap and tool-level retries can follow after scroll is stable.
