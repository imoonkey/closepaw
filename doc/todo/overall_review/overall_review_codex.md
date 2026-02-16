# Craftsmanship Week Overall Review (Codex)

Date: 2026-02-16
Scope reviewed: `app/src/main/kotlin`, `app/src/test/kotlin`, `scripts/debug-run.sh`

## Review Method
- Static scan across the full app tree (25836 LOC Kotlin) and all test files.
- Hotspot inspection for every file >400 LOC.
- Event/lifecycle path tracing for session/task completion and debug tooling.
- Risk scan for null-safety holes, threading/lifecycle hazards, and state consistency bugs.

## Findings (Severity-Ordered)

### Critical
1. `SessionRecordingService.completeSession()` can overwrite a just-finalized agent message with stale session state.
- Evidence: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:190` captures `session`, then `finalizeCurrentAgentMessage()` mutates `currentSession`, then `currentSession = session.copy(...)` writes stale data back at `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:205`.
- Risk: silent data loss / missing final agent output in persisted history.

### High
1. `debug-run.sh` completion detector misses normal task completion in current architecture.
- Evidence: detector only looks for `SessionCompleted`/`AgentService: Task completed` (`scripts/debug-run.sh:400`), while runtime emits `TaskCompleted` events (`AgentSession: Emitted event: TaskCompleted`, `AgentService: Received event: TaskCompleted` observed in `debug-output/run_20260216_130058/logcat_full.log`).
- Impact: script keeps running after task is done and appears hung until manual interrupt or later shutdown.

2. Session/task lifecycle semantics are split across three owners with drift risk.
- Evidence: task completion handled in `AgentSession` (`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:248`), service event handling in `AgentService` (`app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:339`), and persistence completion hook in `MainActivity` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:118`).
- Impact: behavior coupling is implicit and fragile (especially for replay/debug tooling and future UX changes).

3. Orchestration classes are monolithic and exceed local complexity limits.
- Hotspots: `AgentTurnRunner.kt` (788), `VirtualDisplayPlatform.kt` (645), `AgentService.kt` (572), `ShizukuClient.kt` (544), `MainActivity.kt` (536), `AgentTrace.kt` (507), `ChatViewModel.kt` (449), `AgentSession.kt` (443), `SessionRecordingService.kt` (410), `HistoryManager.kt` (402).
- Impact: high change cost, hard reasoning, elevated regression risk.

4. Main-thread-heavy session construction path can cause UI jank.
- Evidence: `MainActivity.ensureSessionAndSend()` builds full session in `lifecycleScope` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:385`), which calls `SessionServices.create()` and asset JSON load (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt:186`).
- Impact: startup latency and frame drops on weaker devices.

5. Validation bug in `MobileActionTool` leaves a dead guard and weakens contract clarity.
- Evidence: impossible condition `if (hasText && params.has("text_index") && !hasText)` at `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt:121`.
- Impact: invalid payload constraints are not enforced as intended; contract becomes ambiguous.

### Medium
1. Settings dropdown UI has substantial duplicated Compose scaffolding in `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsDropdowns.kt:51`.
2. Avoidable force unwrap in UI (`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/components/ActionCard.kt:120`).
3. `SessionServices.create()` has redundant null assertion on non-null context (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt:121`).
4. `HistoryManager` mixes multiple responsibilities (token budget, normalization, compression, rollback) in one >400 LOC class (`app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt:19`).

### Low
1. Sparse TODO/FIXME footprint is good, but several comments indicate deferred quality improvements (`Perceptor`, `PolicyEngine`, overlay permission check).
2. Tests are strong for core logic, weaker for lifecycle integration between `MainActivity`/`AgentService`/`AgentSession` and shell script behavior.

## Big Refactor Candidates (Design Docs)
1. Lifecycle & orchestration contract hardening.
- Design doc: `doc/todo/overall_review/design_refactor_01_lifecycle_orchestration_codex.md`

2. Agent turn pipeline decomposition.
- Design doc: `doc/todo/overall_review/design_refactor_02_turn_pipeline_split_codex.md`

3. Virtual display stack decomposition.
- Design doc: `doc/todo/overall_review/design_refactor_03_virtual_display_stack_codex.md`

4. History recording consistency hardening.
- Design doc: `doc/todo/overall_review/design_refactor_04_history_recording_consistency_codex.md`

## Small/Quick Cleanup Backlog
1. Remove force unwrap in `ActionCard`.
2. Fix dead branch in `MobileActionTool` validation + add tests.
3. Add explicit task-complete log marker in service/session for tooling.
4. Add `debug-run.sh` max-wait timeout guard to avoid infinite loops.

## Implementation Order (Started)
1. Fix completion signaling and `debug-run.sh` detection reliability.
2. Fix `SessionRecordingService` stale-state overwrite + test coverage.
3. Apply low-risk cleanup (`MobileActionTool` validation correctness and null-safety cleanup).
4. Run tests and verification.
