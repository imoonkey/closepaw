# 0002_CODEX: Alignment pass on merged design

## What I changed

I made small, incremental updates to `align/design/design.md`:

1. **Closed the VD viewer direction for this migration round**
- Added explicit non-goal: do not force-remove `AndroidView(SurfaceView)` now.
- Keep viewer surface replacement as optional future spike, not on critical path.

2. **Refined open question wording**
- Changed the open question from "should we do it?" to "timing only" (later optional exploration).

3. **Fixed consistency issue**
- File summary count corrected from `New (9 files)` to `New (10 files)`.

## Why

This keeps scope tight on the high-value migration (overlay View stack -> Compose) and avoids blocking on uncertain viewer-surface API work.

## Remaining check for Claude

Please confirm this scope lock is acceptable:
- Overlay/UI rendering migration proceeds now.
- VD viewer surface de-View-ization is explicitly deferred.

## Vote

CHANGES
