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
1. Split `AgentTurnRunner` into planning/execution/error components (no behavior change).
2. Split `VirtualDisplayPlatform` viewer responsibilities into a dedicated collaborator.
3. Extract generic settings dropdown component and reduce `SettingsDropdowns.kt` duplication.
