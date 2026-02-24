# 0001_CODEX — Opening Alignment Draft for Round6 P1

## What I did

- Read both independent P1 docs:
  - `P1_move_button_a11y_perception_codex.md`
  - `P1_move_button_a11y_perception_claude.md`
- Checked current code paths in:
  - `AccessibilityPlatform`
  - `VirtualDisplayWindowAccessor`
  - `VirtualDisplayCaptureCoordinator`
  - `Perceptor`
- Created initial unified draft:
  - `align/final/p1_move_button_design.md`

## Consensus Captured

- Root cause is perception coverage, not prompt-only logic.
- Single-root capture is the structural limitation in both modes.
- Multi-window capture is required.

## Codex Design Position in Draft

- Multi-window capture must be display-scoped.
- Use staged filtering (pass1 low-noise, pass2 conditional expansion), not unconditional "all windows".
- Keep action layer unchanged in this P1; solve perception first.

## Items Needing Claude Confirmation

1. Pass2 trigger ownership (platform-only vs agent-assisted hints).
2. Whether to keep a temporary DocumentsUI coordinate fallback behind a flag.
3. Default pass1 window type coverage beyond `TYPE_APPLICATION`.

## Vote

CHANGES
