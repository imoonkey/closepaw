# Craftsmanship Week Review (Merged)

Date: 2026-02-16

## Sources Referenced
- `doc/todo/overall_review/review_claude.md`
- `doc/todo/overall_review/design/*_claude.md` (all 10 docs)
- `doc/todo/overall_review/overall_review_codex.md`

This merged review integrates Claude’s broad refactor map and Codex’s code-verified runtime findings.

## Critical Findings (Must Fix)
1. `AgentService.onDestroy` shutdown race can drop `Op.Shutdown` because work was launched on a scope that is canceled immediately.
- Ref: `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`

2. `SessionRecordingService.completeSession` could overwrite finalized in-memory agent message with stale state.
- Ref: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt`

3. `debug-run.sh` completion detection missed normal `TaskCompleted` flow and could appear stuck.
- Ref: `scripts/debug-run.sh`

## High-Value Structural Refactors (Design Docs)
1. Lifecycle/orchestration contract hardening
- `doc/todo/overall_review/design_refactor_01_lifecycle_orchestration_codex.md`
- `doc/todo/overall_review/design/09_agent_service_lifecycle_fix_claude.md`

2. Agent turn pipeline decomposition
- `doc/todo/overall_review/design_refactor_02_turn_pipeline_split_codex.md`
- `doc/todo/overall_review/design/08_large_file_splits_claude.md`

3. Virtual display stack decomposition
- `doc/todo/overall_review/design_refactor_03_virtual_display_stack_codex.md`
- `doc/todo/overall_review/design/07_platform_abstraction_cleanup_claude.md`

4. History recording consistency hardening
- `doc/todo/overall_review/design_refactor_04_history_recording_consistency_codex.md`

5. LLM client consolidation (shared cloud retry/stream scaffold)
- `doc/todo/overall_review/design/01_llm_client_consolidation_claude.md`

6. SessionServices decomposition and config cleanup
- `doc/todo/overall_review/design/02_session_services_decomposition_claude.md`
- `doc/todo/overall_review/design/04_session_config_restructuring_claude.md`

7. Tool system DRY-up + prompt composition + settings generics
- `doc/todo/overall_review/design/06_tool_system_dryup_claude.md`
- `doc/todo/overall_review/design/05_system_prompt_composition_claude.md`
- `doc/todo/overall_review/design/10_settings_ui_generics_claude.md`

## Implemented In This Round
1. `debug-run.sh` now detects task completion from `TaskCompleted` events and has timeout guard (`DEBUG_MAX_WAIT_SECONDS`).
- `scripts/debug-run.sh`

2. `AgentService` now logs task completion explicitly and has collector error boundaries.
- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`

3. `AgentService.onDestroy` shutdown race fixed with synchronous bounded shutdown (`runBlocking` + timeout).
- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`

4. `SessionRecordingService.completeSession` stale-state bug fixed.
- `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt`

5. `MobileActionTool` validation cleanup (`text_index` requires `text`) and removal of unsafe `!!` in action invocation path.
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt`

6. Minor null-safety cleanup in UI action card.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/components/ActionCard.kt`

## Added Regression Tests
1. Pending agent buffer is finalized correctly on `completeSession()`.
- `app/src/test/kotlin/com/moonkey/androidagent/history/SessionRecordingServiceTest.kt`

2. `text_index` without `text` is invalid in `MobileActionTool`.
- `app/src/test/kotlin/com/moonkey/androidagent/tool/impl/MobileActionToolTest.kt`

## Suggested Next Refactor Phase
1. Continue VirtualDisplay stack decomposition: split remaining orchestration from `VirtualDisplayPlatform` and start `ShizukuClient` transport decomposition.
2. Continue protocol domain split from `AgentEvent.kt` into event-domain focused files.
3. Continue SessionConfig cleanup Phase 2: shrink deprecated `llmBackend/localLLMConfig/model` compatibility surface.

## Follow-up Progress (2026-02-16)
1. Extracted turn error classification policy into `TurnErrorClassifier` and added dedicated regression tests.
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnErrorClassifier.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/agent/TurnErrorClassifierTest.kt`

2. Split `AgentTurnRunner` execution phase into `TurnExecutionPhaseRunner` (no behavior change).
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt`

3. Split `AgentTurnRunner` planning phase into `TurnPlanningPhaseRunner`; `AgentTurnRunner` reduced to orchestration-focused 252 LOC.
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`

4. Centralized per-agent model resolution with catalog-first fallback policy (`AgentModelResolver`) and removed direct `llmBackend` branching from planning phase.
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentModelResolver.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/agent/AgentModelResolverTest.kt`

5. Split protocol enums from `AgentEvent.kt` into focused files.
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/TurnPhase.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/CompletionReason.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/AskUserType.kt`

6. Split VirtualDisplay viewer/capture responsibilities into dedicated collaborators.
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayViewerTouchHandler.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayScreenshotProcessor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayAppController.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplaySurfaceController.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayCaptureCoordinator.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt`

7. Moved session configuration types out of `Op.kt` into focused protocol file (Phase 1 config cleanup).
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionConfig.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt`
