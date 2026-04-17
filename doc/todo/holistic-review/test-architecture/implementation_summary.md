# Test Architecture — Implementation Summary

**Date:** 2026-04-16 to 2026-04-17
**Status:** DONE
**Design:** `doc/todo/holistic-review/test-architecture/final/improvement_plan.md`
**Verification:** `./gradlew test` passes. 3 codex review rounds (v1 REQUEST CHANGES → v2 REQUEST CHANGES → v3 APPROVE). Real-device QA: 2 PASS, 1 PARTIAL PASS, 1 SKIP.

## What was implemented

28 implementation tasks across 6 phases, landed as 30 commits from `ca787bc5` to `7c97c8e0`.

### Phase 1 — LLM contract boundary (6 tasks)
- Fixed `OpenAIErrorClassifier` false-positive bug (`message.contains("429")` / `"500"` → non-alphanumeric-boundary regex; `req_429abc`, `error500beta`, status `14291`/`5002` no longer misclassify).
- New tests: `CodexRequestBuilderTest`, `ChatCompletionInteropTest`, `ToolParameterExtractorTest`, `CodexResponseClientTest`, `ChatCompletionClientTest`. Extended: `CodexSseParserTest` (interleaved parallel tool-calls).

### Phase 2 — Orchestration seams (5 tasks)
- New: `TurnPlanningPhaseRunnerTest`, `TurnExecutionPhaseRunnerTest`, `SessionCoordinatorTest`, `SessionCheckpointCoordinatorTest`, `AgentServiceEventHandlerTest`.
- Production fix (drive-by): `SessionCoordinator.drainLocked` used `removeFirst()` (JDK 21 API) → `removeAt(0)`. Was crashing on JDK 17 Android test runtime with `NoSuchMethodError`.

### Phase 3 — Safety tool boundaries (3 tasks)
- New: `AskUserToolTest`, `ShellToolExecutionTest`. Extended: `TypeExecutorTest` (success/failure paths beyond cancellation).

### Phase 4 — Onboarding/auth (5 tasks)
- New: `OnboardingViewModelTest`, `OnboardingStoreTest`, `PermissionStateMonitorTest`, `HttpLlmCredentialValidatorTest`, `OpenAIOAuthTest`.
- Production refactor: extracted `PermissionStateMonitor.deriveRepairModel(...)` as pure companion fn so tests exercise pure logic instead of spying on Android probes.
- Added test dep `com.squareup.okhttp3:mockwebserver:5.2.1` (matches existing okhttp 5.x).

### Phase 5 — Chat/history state (4 tasks)
- New: `ChatEventReducerTest`, `MessageConverterTest`, `ChatSessionHistoryControllerTest`, `ChatViewModelTest` (coordination only, not helper re-tests).

### Phase 6 — Virtual Display + trace (5 tasks)
- New: `VirtualDisplayViewerTouchHandlerTest`, `VirtualDisplaySurfaceControllerTest`, `VirtualDisplayCaptureCoordinatorTest`, `AgentTraceArtifactsTest`. Extended: `FileTraceRecorderTest`.

## Key decisions / non-obvious notes

- **Parallel orchestration via `multmux`**: 28 tasks dispatched in 3 batches (Phase 1, Phase 2-4 partial, Phase 5-6). Gradle daemon contention and one rogue worker's `mv onboarding /tmp/onboarding_bak` required manual cleanup mid-run.
- **`removeFirst()` bug latent in prod**: only surfaced because new `SessionCoordinatorTest` exercised the drain path — `submit()`/`enqueue()` alone never touched it.
- **Slow-test budget kept tight**: codex v2 flagged 3 tests totaling 46s (`ChatCompletionClientTest` retry loop, `HttpLlmCredentialValidatorTest` disconnect timeout, `ShellToolExecutionTest` `sleep 15`). Fixed via constructor-injected timeouts + retry-loop short-circuit — 46s → 3.8s, all defaults preserved for production.
- **Deferred**: codex Low finding on reflection-heavy white-box tests in `ChatCompletionClientTest` / `CodexResponseClientTest` / `ChatViewModelTest`. Non-blocking; revisit if those private seams churn.
- **QA scenario 4 (airplane-mode transient error) SKIPPED** per operator directive — toggling severs the adb control channel. Unit-test coverage of `TransientException` retry path stands in.

## Artifacts
- Design: `doc/todo/holistic-review/test-architecture/final/improvement_plan.md`
- Reviews: `codex_review.md` (v1), `codex_review_v2.md`, `codex_review_v3.md` (APPROVE)
- QA: `qa_report.md` + `qa_screenshots/` + `debug-output/run_20260416_22*`
