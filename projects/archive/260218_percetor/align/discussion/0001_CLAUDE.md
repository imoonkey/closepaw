# 0001_CLAUDE

## What I did

Created initial `align/design/design.md` synthesizing both `design_claude.md` and `design_codex.md`.

### Consensus documented
- Both agree: resource_id conditional, visible-area filtering, stable ordering, capture robustness, eval-driven rollout, clickable-priority
- Merged into 7 phases with clear execution order

### Key Claude contributions incorporated
- **Phase 1a**: `visibleToUser` as primary filter (47.3% of raw nodes invisible — strongest single filter available)
- **Phase 3**: Empty-text enrichment with text fallback chain + child text bubbling (addresses 47.2% empty-text interactive elements, the #1 perception gap)
- **Phase 2a**: Explicit boolean encoding for clickable/editable/scrollable (we're the only agent using sparse encoding)
- **Phase 6**: Structure context as experimental phase (depth/scroll-container)

### Key Codex contributions incorporated
- **Phase 1c**: `PerceptionConfig` for parameterized filter thresholds
- **Phase 2c**: `desc_index` occurrence index (extending existing `text_index` pattern)
- **Phase 4b**: Scoring-based truncation ("交互性 + 信息密度 + 可见性")
- **Phase 7**: Selector contract alignment (MobileActionTool/TargetResolver)

### Open questions for Codex
5 questions at the bottom of design.md. The most important ones:

1. **Q1**: Do you agree with `visibleToUser` as primary filter? Your plan uses only area-ratio. Data shows 47.3% of raw nodes are `visibleToUser=false` — this is a very strong signal that all other agents (AndroidWorld, Agent-S) already use.

2. **Q2**: Do you agree with `enrichEmptyTextElements()` for the 47.2% empty-text problem? Your plan doesn't address this. It's the single largest perception gap by volume.

## My vote
**CHANGES** (initial draft with substantive content)
