# Craftsmanship Week Round2: Code Review & Refactor Plan

Date: 2026-02-16  
Reviewer: Codex  
Status: draft

## Scope

Reviewed inputs:
- `doc/todo/overall_review/refactor_impl_summary.md`
- `doc/todo/overall_review/overall_review_codex.md`
- `doc/todo/overall_review/review_claude.md`
- `doc/todo/overall_review/design_refactor_01_lifecycle_orchestration_codex.md`
- `doc/todo/overall_review/design_refactor_02_turn_pipeline_split_codex.md`
- `doc/todo/overall_review/design_refactor_03_virtual_display_stack_codex.md`
- `doc/todo/overall_review/design_refactor_04_history_recording_consistency_codex.md`
- `doc/todo/overall_review/design/01_llm_client_consolidation.md`
- `doc/todo/overall_review/design/05_system_prompt_composition.md`
- `doc/todo/overall_review/design/06_tool_system_dryup.md`
- `doc/todo/overall_review/design/08_large_file_splits.md`
- `doc/todo/overall_review/design/10_settings_ui_generics.md`
- `doc/todo/2_lock_screen/lock_screen_design_codex.md`

Code reviewed:
- `app/src/main/kotlin`
- `app/src/test/kotlin`
- `scripts/debug-run.sh`

## Current Snapshot

- Kotlin main LOC: `26365`
- Remaining files over 400 LOC: `7`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt` (540)
  - `app/src/main/kotlin/com/moonkey/androidagent/trace/AgentTrace.kt` (507)
  - `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt` (477)
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt` (448)
  - `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt` (443)
  - `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt` (409)
  - `app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt` (402)

结论：Round1 把最危险的 lifecycle/history/VD 结构问题压下去了，但 Round2 仍需要处理「主线程构建路径 + 统一性/收敛性」这两类问题。

## Findings (Severity Ordered)

## Critical

- None found in this pass.

## High

1. Session 创建链路仍在主线程执行，且包含阻塞 I/O。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:386`
  - `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:58`
  - `app/src/main/kotlin/com/moonkey/androidagent/session/SessionLlmBootstrapper.kt:63`
  - `app/src/main/kotlin/com/moonkey/androidagent/session/SessionLlmBootstrapper.kt:68`
- Risk: 首次建会话时可能造成 UI 卡顿，弱设备上有 ANR 风险。
- Direction: 将 `AgentSession.create(...)` 包装到 `Dispatchers.Default/IO`，并把 model catalog 读取做缓存/懒加载。

2. Task 完成后的历史 finalize 责任分散在 Activity 与 Service 侧，语义存在漂移风险。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:119`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt:75`
- Risk: 在 MainActivity 不活跃或未来入口变化时，`completeSession()` 可能不会被调用，导致完成态元数据不一致。
- Direction: 把 session completion 的最终归档收敛到单一 owner（建议 `AgentSession` 或 `AgentServiceEventHandler` 统一触发）。

3. LLM cloud client 生命周期脚手架仍重复，后续变更容易不一致。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt:84`
  - `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:111`
- Risk: stream retry/failure 逻辑在两个客户端并行演进，回归概率高。
- Direction: 按 `01_llm_client_consolidation.md` 落地 `CloudLLMClient` 抽象层。

## Medium

1. Tool observation 构建路径仍多处并行，尚未完全 DRY。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ObservationBuilder.kt:13`
  - `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/UIActionInvocation.kt:75`
  - `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt:207`
- Risk: screenshot-only/hybrid 模式下行为细节可能逐步漂移。

2. System prompt 仍是 3 份大型字符串，且仍有 dead/commented prompt 片段。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt:18`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt:20`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt:21`
  - `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt:59`
- Risk: prompt 演进时高概率出现角色间 drift。

3. Settings dropdown 只做了 field-level 复用，未完成 type-level generic 收敛。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsDropdowns.kt:58`
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsDropdowns.kt:101`
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsDropdowns.kt:307`

4. 仍有 7 个文件超过 400 行规范上限。
- Evidence: current LOC scan.
- Risk: 修改成本和回归面继续偏大。

5. Lock-screen design 与当前代码结构存在文档漂移，直接实现会撞到接口不匹配。
- Evidence:
  - 设计文档仍引用 `SessionConfig` in `Op.kt`，而实际已拆到 `SessionConfig.kt`。
  - 文档提到 `Op.DeviceLockChanged`，当前 `Op` 中并不存在该事件。
- Direction: 实施前先做 design refresh。

## Low

1. 少量 `!!` 仍存在于关键路径，虽可控但可继续收敛。
- Evidence:
  - `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:167`
  - `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt:133`

## Design Alignment (Round1 -> Round2)

- A/B/C/D（lifecycle、turn pipeline、VD、protocol/config/session）: 已完成主阶段，可转维护态。
- E（LLM consolidation）: 仅完成 retry policy 抽取，核心 scaffold 未收敛。
- F（Tool DRY）: 完成少量 helper，尚未统一 observation/description。
- Prompt composition: 未开始。
- Settings generics: 未完成（仅部分基础组件抽取）。
- Lock-screen execution: 仍在设计阶段，且需先更新设计与当前代码架构对齐。

## Round2 Refactor Plan

### Phase 1: Runtime Correctness + Main-thread Safety (P1)

目标：先把用户可感知风险降到最低。

1. 异步化 session 构建链路
- 新建 `SessionLauncher`（或 `SessionCreationUseCase`）承接 `AgentSession.create(...)`。
- `MainActivity.ensureSessionAndSend` 仅做 UI 状态和调用，不直接承担重构建逻辑。
- `SessionLlmBootstrapper.loadModelCatalog` 做缓存（进程级）避免重复 asset I/O。

2. 收敛 task completion/recording finalize owner
- 统一 `TaskCompleted -> completeAgentMessage/completeSession` 触发路径。
- 明确“task terminal vs session terminal”的持久化语义并补注释。

验证:
- 单元测试：completion finalize 顺序 + session metadata 正确性。
- 手工：`./scripts/debug-run.sh` 连续任务场景下历史记录一致。

### Phase 2: LLM + Tool DRY Completion (P2)

目标：解决“做了一半”的重构，降低后续维护分叉。

1. 落地 `CloudLLMClient`
- 将两个 cloud 客户端的 retry/stream lifecycle 收敛到同一抽象。
- 保持 API-specific parsing 在子类。

2. Tool DRY 二阶段
- 把 `UIActionInvocation`/`OpenAppTool` observation 统一到 `ObservationBuilder`（或新的 `ObservationFactory`）。
- 收敛 description 组装入口，避免 per-tool 漂移。

验证:
- 增加/补强 llm streaming retry 行为测试（当前缺直接测试）。
- existing tool tests 全量通过。

### Phase 3: Prompt + Settings + Large-file Compliance (P2)

目标：提升可维护性和迭代速度。

1. Prompt composition
- 建 `PromptFragments + SystemPromptBuilder`。
- 清理 `StandaloneAgentDef` 注释遗留。

2. Settings generic
- 在保留现有视觉结构下引入 `SettingsDropdown<T>`，将同构逻辑收敛。

3. 大文件拆分优先顺序
- `MainActivity.kt`
- `AgentTrace.kt`
- `ChatViewModel.kt`
- `AgentSession.kt`
- `SessionRecordingService.kt`
- `HistoryManager.kt`

验证:
- UI smoke + 单测回归。
- 文件行数目标：新增文件 <300 行，旧文件全部 <=400 行。

### Phase 4 (Optional Track): Lock-screen Foundation

目标：在不解锁设备前提下，引入可控且 fail-closed 的锁屏执行基础。

前置:
- 先更新 `doc/todo/2_lock_screen/lock_screen_design_codex.md` 到当前架构（`SessionConfig.kt`、`Op` 定义、service/session ownership）。

实现建议:
- 先做 `PAUSE_WHEN_LOCKED`（低风险）
- 再做 `CONTINUE_ON_VIRTUAL_DISPLAY`（wake lock + liveness probe）

## Suggested Commit Batches

1. `refactor: move session creation off main thread`
2. `fix: unify task completion recording finalization`
3. `refactor: introduce cloud llm base scaffold`
4. `refactor: unify tool observation/description builders`
5. `refactor: compose system prompts from fragments`
6. `refactor: generic settings dropdown`
7. `refactor: split remaining >400 line files`
8. `docs: refresh lock-screen design against current architecture`

## Recommendation

`CHANGES_REQUESTED`（面向 round2）

原因：当前没有新的 P0 crash/data-loss，但仍有两个高优先级结构风险（主线程构建链路、completion finalize owner 分散）应先处理，再继续功能型重构。
