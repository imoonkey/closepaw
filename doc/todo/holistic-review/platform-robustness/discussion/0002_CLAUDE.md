# 0002 CLAUDE

The initial aligned draft is strong. One substantive edit to Phase 1; otherwise approving.

## Change made

**Phase 1 serialization granularity**: The original Phase 1 listed all operations (`captureScreen`, `performAction`, `start`, `stop`, etc.) under "one serialized owner," which could be read as putting everything behind a single mutex. I clarified:

- **Lifecycle transitions** (`start`, `stop`, `switchToLivePreview`, `switchToImageReader`, binder death) need exclusive access.
- **Operational calls** (`captureScreen`, `performAction`) verify state is `Running` and fail fast otherwise, but don't need to block each other.

Why it matters: a single mutex for everything would make capture and action mutually exclusive unnecessarily. The existing code comment at `VirtualDisplayPlatform.kt:148` already acknowledges that `stop()` is "not safe to call concurrently with captureScreen/performAction" — the serialization should protect lifecycle transitions from operations, not operations from each other.

In practice the agent loop is sequential today, so this won't cause a performance issue either way. But getting the locking model right in the design prevents future confusion.

## Notes (no changes needed)

1. **State machine scope**: The 6-state machine (`Stopped`, `Starting`, `Running(image_reader)`, `Running(live_preview)`, `Broken`, `Stopping`) is the right set. `Starting` and `Stopping` earn their existence by giving lifecycle transitions a place to live without half-valid public state.

2. **Cursor placement as test-gated**: Agreed. My original P0 was too aggressive without a failing test. Phase 8 handles this correctly.

3. **Phase 6 grab-bag**: Phase 6 bundles several unrelated fixes (fail-soft capture, truthful launch, surface replacement, shell fallback, scroll validation). This is fine — they're all "platform boundary correctness" and individually small. No restructuring needed.

4. **ImageReader startup leak**: Covered implicitly by Phase 7's "harden small cleanup edges" language. Good enough.

Vote: CHANGES (due to Phase 1 edit)
