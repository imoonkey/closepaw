# Platform Reorg Plan (Accessibility + Virtual Display)

Date: 2026-02-11
Status: Planned for implementation in current workstream

## Why Reorg

Both `AccessibilityPlatform` and `VirtualDisplayPlatform` have grown into large multi-purpose classes.
The behavior is mostly correct, but readability and maintainability are degrading.

This reorg aims to:
- keep behavior stable
- reduce class size and nesting
- align structure between both platform implementations
- make future bug fixes local and testable

## KISS Constraints

- No architecture astronauting
- No deep inheritance tree
- Prefer small focused collaborators with explicit names
- Keep external `AndroidPlatform` usage unchanged where possible

## Target Structure

### Virtual Display side

1) `VirtualDisplayPlatform`
- orchestrator only
- owns lifecycle and delegates work

2) `VirtualDisplayWindowAccessor`
- fetch windows by display
- return app window/root safely
- centralize logging policy

3) `VirtualDisplayNodeActionPerformer`
- click/long-click/set-text actions via accessibility nodes
- shared text-setting policy (including focus cleanup)

4) `VirtualDisplayInputInjector`
- tap/long-press/swipe/system button via Shizuku injection
- encapsulate event construction and display targeting

### Accessibility side (aligned shape)

1) Keep `AccessibilityPlatform` as orchestrator
2) Extract or align node action code into a focused performer with similar API shape
3) Keep gesture dispatch logic local to accessibility path

## Shared Concepts To Align

- Node action semantics:
  - `ClickNodeAt`
  - `LongClickNodeAt`
  - `SetTextOnNodeAt`
  - `SetTextOnFocused`

- Return contract:
  - clear `ActionResult.Success/Failure`
  - consistent failure reasons

- Resource handling:
  - recycle nodes/windows deterministically

## Incremental Steps

### Step A (safe extraction)
- Move helper methods from `VirtualDisplayPlatform` to new helper classes without changing behavior.
- Keep method names and return shapes the same.

### Step B (alignment)
- Extract analogous node action helper from `AccessibilityPlatform`.
- Normalize duplicated text action logic.

### Step C (cleanup)
- Remove dead utilities and duplicate logging.
- Keep docs in sync with actual structure.

## Non-goals (this pass)

- No new universal base class for all platforms.
- No major redesign of `AndroidPlatform` contract.
- No optimizer-heavy rewrites.

## Risks and Mitigation

1) Behavior drift during extraction
- Mitigation: move code first, then simplify; avoid logic rewrites in same step.

2) Hidden threading assumptions
- Mitigation: preserve current dispatcher boundaries until verified.

3) Over-refactor scope creep
- Mitigation: stop once readability and file size are acceptable.

## Definition of Done

- `VirtualDisplayPlatform` reduced to orchestrator-level size
- Node action logic isolated in dedicated classes
- Accessibility and VD implementations show aligned structure and naming
- No regression in debug run path for VD
