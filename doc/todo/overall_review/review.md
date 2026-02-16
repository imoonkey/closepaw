# Craftsmanship Week Final Summary

Date: 2026-02-16
Status: final summary (living doc)

## 1) Scope & Goals
本轮目标：系统性提升代码底座质量，强调 KISS、可读性、可维护性、可扩展性，并持续收敛历史兼容路径。

核心原则：
- 先修 P0/P1 稳定性与架构阻塞点，再做结构化重构。
- 每个重构块独立提交（small batch, reversible）。
- 每个阶段都做编译/测试/静态检查验证。

## 2) Design Inputs Referenced
本总结基于以下文档做汇总与执行：

### 2.1 Review Inputs
- `doc/todo/overall_review/review_claude.md`
- `doc/todo/overall_review/overall_review_codex.md`

### 2.2 Claude Design Docs (10)
- `doc/todo/overall_review/design/01_llm_client_consolidation_claude.md`
- `doc/todo/overall_review/design/02_session_services_decomposition_claude.md`
- `doc/todo/overall_review/design/03_agent_event_domain_split_claude.md`
- `doc/todo/overall_review/design/04_session_config_restructuring_claude.md`
- `doc/todo/overall_review/design/05_system_prompt_composition_claude.md`
- `doc/todo/overall_review/design/06_tool_system_dryup_claude.md`
- `doc/todo/overall_review/design/07_platform_abstraction_cleanup_claude.md`
- `doc/todo/overall_review/design/08_large_file_splits_claude.md`
- `doc/todo/overall_review/design/09_agent_service_lifecycle_fix_claude.md`
- `doc/todo/overall_review/design/10_settings_ui_generics_claude.md`

### 2.3 Codex Design Docs (4)
- `doc/todo/overall_review/design_refactor_01_lifecycle_orchestration_codex.md`
- `doc/todo/overall_review/design_refactor_02_turn_pipeline_split_codex.md`
- `doc/todo/overall_review/design_refactor_03_virtual_display_stack_codex.md`
- `doc/todo/overall_review/design_refactor_04_history_recording_consistency_codex.md`

## 3) Final Integrated Plan (In-place Status)

### Plan A: Runtime Correctness & Lifecycle Hardening
Status: `[x] completed`

- [x] Fix `AgentService` shutdown race / collector boundary
- [x] Fix `debug-run.sh` completion detection (`TaskCompleted` + timeout guard)
- [x] Fix history finalize consistency (`SessionRecordingService.completeSession`)

Key outputs:
- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt`
- `scripts/debug-run.sh`

---

### Plan B: Agent Turn Pipeline Decomposition
Status: `[x] completed`

- [x] Extract turn error policy: `TurnErrorClassifier`
- [x] Extract planning phase: `TurnPlanningPhaseRunner`
- [x] Extract execution phase: `TurnExecutionPhaseRunner`
- [x] Extract model resolution: `AgentModelResolver`

Key outputs:
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnErrorClassifier.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentModelResolver.kt`

---

### Plan C: Virtual Display / Shizuku Stack Decomposition
Status: `[x] completed`

- [x] Split viewer/capture/app/surface responsibilities
- [x] Split Shizuku client transport/runtime/activity concerns

Key outputs (representative):
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayCaptureCoordinator.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/ShizukuDisplayTransport.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/ShizukuInputTransport.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/ShizukuRuntimeGateway.kt`

---

### Plan D: Protocol/Config/Session Architecture Cleanup
Status: `[x] completed (for this phase)`

#### D1 SessionConfig Restructuring
- [x] Move config types out of `Op.kt` (Phase 1)
- [x] Introduce canonical `SessionLlmConfig`
- [x] Remove legacy top-level compatibility fields (`model`, `llmBackend`, `localLLMConfig`) from runtime path (Phase 2)

#### D2 SessionServices Decomposition
- [x] Extract LLM bootstrap (`SessionLlmBootstrapper`)
- [x] Extract tooling bootstrap (`SessionToolingBootstrapper`)
- [x] Extract history bootstrap (`SessionHistoryBootstrapper`)
- [x] Extract builder/summary helpers

#### D3 AgentEvent Domain Split
- [x] Split enums into focused files
- [x] Split event declarations into domain files (`*Events.kt`)
- [x] Reduce `AgentEvent.kt` to root interface

Key outputs (representative):
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionConfig.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionLlmBootstrapper.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/AgentEvent.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionLifecycleEvents.kt`

---

### Plan E: LLM Client Consolidation
Status: `[~] in progress`

- [x] Extract shared retry/backoff policy: `CloudLlmRetry`
- [x] Extract shared streaming retry/error decision policy: `CloudStreamRetryPolicy`
- [ ] Continue unifying request/stream lifecycle scaffold (avoid over-abstraction)

Key outputs:
- `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudLlmRetry.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryPolicy.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt`

---

### Plan F: Tool DRY-up + Prompt Composition + Settings Generics
Status: `[~] partially in progress`

#### F1 Tool DRY-up
- [x] Shared text-tool success helper: `textToolSuccess(...)`
- [x] Shared reason suffix helper: `appendReason(...)`
- [ ] Continue consolidating remaining tool description/observation boilerplate

#### F2 Prompt Composition
- [ ] Not started in this round

#### F3 Settings UI Generics
- [ ] Not started in this round

Key outputs (F1):
- `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolSpec.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/WriteTodosTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ScratchpadTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt`

## 4) What Is Done vs Not Done (Quick Snapshot)

### Done
- [x] P0 correctness fixes (lifecycle/history/debug-run)
- [x] Turn pipeline structural split
- [x] VirtualDisplay/Shizuku phase decomposition
- [x] SessionConfig + SessionServices + AgentEvent major restructuring phase

### Ongoing
- [~] LLM client consolidation (2 shared policies landed; further scaffold merge pending)
- [~] Tool DRY-up (helpers landed; broader cleanup pending)

### Not yet started
- [ ] Prompt composition consolidation (`05_system_prompt_composition_claude.md`)
- [ ] Settings dropdown generics (`10_settings_ui_generics_claude.md`)

## 5) Validation Snapshot
本轮多次执行并通过：
- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:lintDebug`
- `./gradlew :app:testDebugUnitTest --tests ...`（覆盖 session/agent/tool/llm 关键回归）

## 6) Execution Discipline
- 每个重构块已按要求独立 commit。
- `review.md` 现作为统一、可持续更新的 final summary 与状态看板。
