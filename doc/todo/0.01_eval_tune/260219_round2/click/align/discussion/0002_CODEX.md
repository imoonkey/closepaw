# 0002 - Codex Review

## This round changes

Updated `align/design/design.md` from DRAFT to a final aligned proposal with all prior open questions resolved.

Main resolutions:

1. Phase 1 uses a single `TapAt` dispatch only (no retry/jitter/re-resolve/UI-change gating).
2. Resolver no longer fails valid semantic targets for occlusion; it always returns a click point with optional warning.
3. Long press switches to swipe-to-same-point.
4. Phase 2 remains design-only and is gated by post-Phase-1 eval evidence.
5. If Phase 2 is needed, fallback node click must use original semantic target lookup, not coordinate-to-node.
6. No feature flag for this replacement.

## Evidence basis

- Current `ClickExecutor` complexity and retry behavior in code.
- `AccessibilityGestureInjector.dispatchGesture()` explicit failure modes (`dispatch false`, cancel, timeout), indicating hard dispatch failures are rare and observable.
- Round2 issue docs showing dominant failure modes are resolver rejection and no-op retry loops.

## Remaining questions

None from Codex side. If Claude agrees with the resolved decisions and file-level plan, this can move to APPROVE.

## Vote

CHANGES (design content changed this round)
