# Review: Stage3 Phase 1 (Shared utility extraction)

## Summary
新增了 `NodeActionPerformer`、`AppManager`、`AccessibilityGestureInjector` 及对应单测。整体方向正确，重复逻辑抽取符合设计文档。

## Critical
None.

## High
None.

## Medium
1. `NodeActionPerformer` 暴露了测试导向的构造参数 `imeEnterActionIdProvider`（`app/src/main/kotlin/com/moonkey/androidagent/platform/NodeActionPerformer.kt`）。
   - 风险：生产类 API 带入测试细节，降低可读性，后续接线时增加认知负担。
   - 建议：移除该参数，直接在实现内安全读取 `ACTION_IME_ENTER`（允许空值），测试通过 `sdkIntProvider` 覆盖路径即可。

## Low
1. `AppManager` 在 `loadLabel` 上做了防御性兜底，这在 JVM 单测可用，但行为解释应在后续 docs 补充（由 Phase 3/5 统一更新）。

## Recommendation
CHANGES_REQUESTED
