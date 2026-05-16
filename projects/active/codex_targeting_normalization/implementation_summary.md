# Codex Targeting Normalization — Implementation Summary

**Status:** Shipped. PR #41 merged to main at `81d74b71` on 2026-05-16.
**Design:** `design_codex.md`
**Review:** `impl_review_codex.md` (0 blockers, 3 majors auto-fixed, 0 deferred)
**Device QA:** `device_qa_notes.md` (6/6 scenarios PASS on Tailscale-attached device)

## What shipped

A semantic target may now carry an optional `coordinateHint`. Validation, resolution, and execution were aligned so that:

| Input | Outcome |
|---|---|
| `element_index` or `text` or `x/y` alone | unchanged |
| `element_index + x/y` or `text + x/y`, hint inside resolved bounds | execute semantic node action; hint is redundant evidence |
| `element_index + x/y` or `text + x/y`, hint outside resolved bounds | `Ambiguous` failure before dispatch — runtime refuses to guess |
| `element_index + x/y` (or `text + x/y`), semantic miss | coordinate fallback; gesture-only channels; warning surfaced to LLM |
| `element_index + text` (with or without x/y) | invalid — two semantic targets |
| `scroll` with semantic miss + hint | fail; scroll never uses coordinate fallback (area-based) |

Strict half-open containment for the in-bounds check (`left <= x < right && top <= y < bottom`), matching Android `Rect.contains`. No slop in v1.

## Files touched

| Layer | File |
|---|---|
| Target model | `app/src/main/kotlin/ai/closepaw/tool/action/Target.kt` |
| Resolver | `app/src/main/kotlin/ai/closepaw/tool/action/TargetResolver.kt` |
| Tool validation + parsing | `app/src/main/kotlin/ai/closepaw/tool/impl/MobileActionTool.kt` |
| Click/long-press dispatch | `app/src/main/kotlin/ai/closepaw/tool/action/PointActionExecutorCore.kt` |
| Type dispatch | `app/src/main/kotlin/ai/closepaw/tool/action/TypeExecutor.kt` |
| Scroll guard | `app/src/main/kotlin/ai/closepaw/tool/action/ScrollExecutor.kt` |
| Display ordering | `app/src/main/kotlin/ai/closepaw/agent/ActionDescriptionFormatter.kt` |
| QA infra | `app/src/main/kotlin/ai/closepaw/debug/MobileActionDebugRunner.kt`, `app/src/main/kotlin/ai/closepaw/app/AgentServiceReceiverHelpers.kt`, `scripts/mobile-action-test.sh` |
| Docs | `doc/main/infra/tool/mobile_action.md`, `doc/main/infra/tools.md`, `doc/dev/development.md` |

Tests added/extended: `MobileActionToolTest`, `TargetResolverTest`, `ClickExecutorTest`, `TypeExecutorTest`, `ScrollExecutorTest`, `ActionTargetTest`.

## Key decisions

- **Coord is hint, not primary.** Semantic stays canonical even when both are present. Rejected "coords as primary when present" because it skips the safer node-action path and regresses overlap handling.
- **Half-open containment, no slop.** Magic-number slop deferred unless eval traces show false ambiguity.
- **Scroll is the explicit exception.** Coordinate fallback is meaningless for an area-based op. `ScrollExecutor` rejects `coordinateFallback=true` outright.
- **Display matches runtime.** `ActionDescriptionFormatter` reordered so traces/approvals match what the executor will actually do — `element_index > text > bounds > x/y`.
- **Debug receiver for deterministic QA.** Added because LLM-driven runs cannot reliably emit failure shapes (out-of-bounds hints, semantic misses with valid coords). Same `MobileActionTool` → `TargetResolver` → executor → `AccessibilityPlatform` path as production; only the params source differs. `BuildConfig.DEBUG`-gated.

## Follow-ups

- Watch eval traces for false-ambiguity cases that would motivate slop.
- Once stable, archive `projects/active/codex_targeting_normalization/` to `projects/archive/YYYYMMDD_codex_targeting_normalization/` and archive the matching task tree via `update-tasks.py archive codex-targeting-normalization`.
