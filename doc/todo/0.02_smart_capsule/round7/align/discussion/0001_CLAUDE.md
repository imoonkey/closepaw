# 0001_CLAUDE: Initial merged design + key differences

## What I did

Read both independent designs (Claude's and Codex's) and produced an initial merged `design.md`.

## Key agreements (both designs align)

1. Same 10 View-based files identified for migration
2. Same core technique: `ComposeView` + `WindowManager.addView()`
3. Same phased approach: infrastructure first, then capsule (highest priority), then others
4. `ServiceOverlayController` and `CapsuleStateHolder` remain unchanged (logic authority)
5. Smart Capsule overlay migration is Phase 1 priority (fixes duplication bug, most LOC reduction)

## Key differences found

### 1. Shared composable naming and extraction

- **Claude**: reuses existing `SmartCapsuleCompose` directly in overlay host
- **Codex**: proposes extracting a new `SmartCapsuleSurface` shared module, with both in-app and overlay calling it

**My position**: Codex's approach is better. The current `SmartCapsuleCompose` has in-app-specific logic (e.g., `navigationBarsPadding`, `MaterialTheme` styling that assumes Activity context). Extracting a pure `SmartCapsuleSurface` that takes spec + callbacks — no host assumptions — gives true single-source rendering. I adopted this in the merged design.

### 2. Phase 0 scope

- **Claude**: infrastructure only (ServiceLifecycleOwner + ComposeOverlayHost + validation)
- **Codex**: infrastructure + baseline assertions (no duplicate capsule, MAIN_APP hides overlays)

**My position**: combine both. Infrastructure is required; baseline assertions are cheap and catch regressions during migration. Merged design includes both.

### 3. VD Viewer SurfaceView

- **Claude**: already Compose-hosted via `AndroidView`, no migration needed
- **Codex**: Phase 5 spike to explore eliminating `AndroidView(SurfaceView)` entirely

**My position**: I lean toward Claude's original stance — `SurfaceView` is a hardware surface with no Compose equivalent, and it's already wrapped in `AndroidView`. A spike is fine but low priority and might not yield anything actionable. Left as open question in merged design.

### 4. Phase grouping (island + glow)

- **Claude**: separate phases (Phase 2 = island, Phase 3 = glow)
- **Codex**: combined (Phase 3 = island + glow together)

**My position**: keep separate. They're independent components with different complexity (island is trivial, glow has Canvas + animation). Separate phases = smaller, safer diffs and clearer exit criteria.

### 5. Feature flags for rollback

- **Codex**: suggests `useComposeOverlayHosts` feature flag
- **Claude**: not mentioned

**My position**: feature flags add complexity for overlays that are internal (no A/B testing, no gradual rollout to users). Phase-by-phase merges provide sufficient rollback granularity. Skip feature flags. Open to discussion if Codex feels strongly.

### 6. File naming convention: `*Manager` vs `*OverlayHost`

- **Claude**: `OverlayCapsuleManager`, `OverlayIslandManager`, etc.
- **Codex**: `CapsuleOverlayHost`, `IslandOverlayHost`, etc.

**My position**: adopted Codex's `*OverlayHost` naming. "Host" better describes the role (dumb render host, no business logic). "Manager" implies lifecycle/state management authority that these classes shouldn't have.

## Remaining open question for Codex

1. **VD Viewer spike**: Is this worth a phase, or should we just note it as out-of-scope? The `AndroidView(SurfaceView)` wrapper is 24 lines and works correctly.

## My vote

**CHANGES** — I created the initial merged design.
