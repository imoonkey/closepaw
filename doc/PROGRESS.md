# Changelog

## 2026-04-18: auth-setting-cleanup — F1/F2 device-QA defects fixed

**What changed:**
- F1: `OnboardingViewModel.resolveBaseUrl(entry)` now mirrors `LLMClientFactory.build()` — for `OPENAI_API` entries, `AppSettingsState.openaiBaseUrl` (debug-only intent override) wins over `entry.effectiveBaseUrl`. JVM test added in `OnboardingViewModelTest`.
- F2: `MainActivity.pendingSettingsDeepLink` state + `validateCloudKeysForSelectedModels()` now populate a `SettingsDeepLink(LLM_AUTH, missing.first().provider.mode)` before flipping `showSettings`. `MainActivityContent` accepts `initialSettingsDeepLink` and seeds its internal `pendingDeepLink` from it. Banner-tap path was already wired; gap was the pre-flight auto-open.
- Removed stale TODO in `SettingsDeepLink.kt`.
- `doc/main/app/settings.md`: documented Settings Deep-Link two-path convergence + onboarding base URL resolution rule.

**Why:**
- F1 blocked debug-build onboarding via OpenAI: validator hit `api.openai.com` with `gpt-5.4` mock IDs → HTTP 400 → "Provider configuration issue". Fix unblocks the proxy path the rest of the runtime already used.
- F2: pre-flight credential check was a separate code path from the banner-tap path; only the latter was deep-link-aware. Result: missing-credential auto-open landed on Settings home, requiring an extra navigation step.

**Key files:** `app/src/main/kotlin/ai/closepaw/onboarding/OnboardingViewModel.kt`, `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt`, `app/src/main/kotlin/ai/closepaw/app/MainActivityContent.kt`, `app/src/main/kotlin/ai/closepaw/ui/chat/SettingsDeepLink.kt`, `doc/todo/auth-setting-cleanup/qa_report.md`, `doc/main/app/settings.md`
**Verification:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug` green. Device EP0110MZ0BC101266W: S2 → HTTP 200 advances to step 5; S8 → sheet auto-opens on LLM Auth / API Key / OpenAI tab; end-to-end `gpt-5.4` proxy run completed in 2 turns (open_app → complete_task).
**Commit:** 97d5362c
**Next:** Re-run S1 (OAuth), S6/S7 (prior-build upgrade) when a baseline APK is available.
**Blockers:** None.

## 2026-04-17: qa_test — Compose UI behavior-guard layer bootstrapped (45 tests, 3 batches)

**What changed:**
- New instrumented test layer at `app/src/androidTest/kotlin/ai/closepaw/qa/`. AndroidJUnitRunner + Compose UI Test, `animationsDisabled=true`. 45 tests across:
  - Batch 1 Chat (17): Sanity, EmptyState, Header, BubbleAlignment, ThinkingState, StreamingCursor, ActionCard state icons, ActionCard expand.
  - Batch 2 SmartCapsule (15): Rendering (Hidden/Running/Takeover), Input (WaitingForInput field/send), Approval (WaitingForAction/WaitingForApproval), Lifecycle (Done auto-dismiss via real `CapsuleStateHolder.scheduleAutoHide`, Error, Stop-pending, Navigation).
  - Batch 3 Settings (13): Sheet nav + StateRestorationTester rotation, LLM Auth tab/OAuth/provider/model-canonicalization, AgentBehavior Pro/Basic, Permissions traces banner + Clear-Traces dialog.
- Minimal production touchpoints (5 testTag additions, all justified):
  - `ui/chat/components/ThinkingIndicator.kt` — `qa-thinking-indicator`
  - `ui/capsule/surface/SmartCapsuleSurfaceParts.kt` — `qa-capsule-input`
  - `ui/settings/PermissionsAdvancedSettingsPage.kt` — clear-traces dialog anchor
  - `ui/settings/LlmAuthSettingsPage.kt` — `qa-executor-model-dropdown` wrapper Box (Sign-In + API-Key Pro branches)
  - `ui/chat/components/MessageBubble.kt` — user/agent bubble container tags
- `app/build.gradle.kts`: `testInstrumentationRunner = AndroidJUnitRunner`, `testOptions.animationsDisabled = true`, `androidTestImplementation` for Compose UI Test + uiautomator + ext:junit.
- Design framing reframed in `doc/todo/qa_test/final/cn/design_kiss.md`: from "bug-driven" → "behavior-guard". Bug reports are one trigger to add new guards, not the only legitimate one. bootstrap_plan.md unchanged.

**Why:**
- The existing test layers (`app/src/test/` JVM units, `eval/` AndroidWorld benchmarks) didn't cover Compose UI behavior. Manual UX QA was the only safety net for chat / capsule / settings regressions.
- bootstrap by behavior inventory (not by waiting for bugs) up-front guards the high-value flows; bug reports later add point guards as needed.
- KISS rules: flat layout, no Robot/base classes/annotations, `org.junit.Assert` for verdicts (Kotlin built-in `assert(...)` is a silent no-op without `-ea`), `testTag` only when text/contentDescription unavailable.

**Key files:** `app/src/androidTest/kotlin/ai/closepaw/qa/**`, `app/build.gradle.kts`, `doc/todo/qa_test/final/cn/{design_kiss,bootstrap_plan}.md`, the 5 production files above.

**Verification:** `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=ai.closepaw.qa` → 45 tests green on device EP0110MZ0BC101266W. 3 codex review rounds (1 per batch + 1 fix-recheck on Batch 3) caught and resolved K11 fake auto-dismiss, S9 false-positive via shared section title, S11 vacuous dialog assertion, plus several smaller assertion-hygiene issues.

**Commit:** `3c4f586d..96024d91`

**Next:** C12/C13 (chat scroll FAB) deferred — needs full ChatScreen + lazy scroll state. Settings helper (`QaSettingsHelpers.kt`, 145 lines) could be slimmed once 3rd-repetition rule applies. Otherwise behavior-guard growth is event-driven (new behavior or bug fix → new test).

**Blockers:** None.

## 2026-04-16: protocol-communication — 4 fixes, codex APPROVE, 5/6 QA PASS

**What changed:**
- Split `CompletionReason` into `TaskOutcome` (GOAL_ACHIEVED / MAX_TURNS / TASK_IMPOSSIBLE / ERROR / USER_STOPPED) and `SessionEndReason` (USER_STOPPED / IDLE_TIMEOUT / INTERRUPTED). `TaskCompleted.outcome` and `SessionCompleted.reason` now carry the right shape; `SessionCompleted.result` (always null) removed. Impossible branches pruned in `AgentServiceEventHandler` and `CapsuleStateHolder.onSessionEnded`.
- `SessionRecordingService.completedNormally` now derives from `lastTaskOutcome` (cleared on `TaskStarted`, persisted in `SessionRuntimeSnapshot`, restored via `AgentSession.reload()`). `handleShutdown()` emits `TaskCompleted(USER_STOPPED)` for any in-flight task before `SessionCompleted`.
- `SessionCheckpointCoordinator` now round-trips `actionDelayMs`, `approvalMode`, `debugMode`, `traceEnabled`, `traceRunId`, `excludedTools` (previously silently dropped on reload — could change security posture).
- `AgentSession.handleApproval()` reordered: `toolRouter.resolveApproval()` gates allow-list mutation; unmatched/duplicate `Op.Approve` is logged and dropped without touching package allow-lists.
- Pruned dead event surface: `TodosUpdated`, `ScratchpadUpdated`, `ApprovalResolved` (no consumers), `StatusUpdate.emoji` field, `TurnStarted.phase` field, `ApprovalRequired.actionId` field.

**Why:**
- Double-design holistic review dimension 11 identified completion-semantics as a data-integrity bug (successful tasks recorded as failed when session later idled out) and approval validation as a security invariant (stale `Op.Approve` could mutate policy without matching a pending request).
- Checkpoint field loss silently changed runtime behavior after reload — `approvalMode: SMART` would become default, `traceEnabled` would reset.
- ~257 lines of dead event types were pure protocol overhead.

**Key files:** `protocol/TaskOutcome.kt`, `protocol/SessionEndReason.kt`, `protocol/TaskLifecycleEvents.kt`, `protocol/SessionLifecycleEvents.kt`, `session/AgentSession.kt`, `session/SessionCheckpointCoordinator.kt`, `history/SessionRecordingService.kt`, `history/model/SessionRuntimeSnapshot.kt`, `app/AgentServiceEventHandler.kt`, `ui/overlay/CapsuleStateHolder.kt`, `protocol/AgentEventDispatcher.kt`
**Verification:** `./gradlew assembleDebug test lint` pass. 2 codex review rounds: v1 REQUEST CHANGES (1 High `lastTaskOutcome` lifecycle, 2 Medium test-coverage gaps → fixed in `236dfbf3`), v2 **APPROVE**. Real-device QA on device EP0110MZ0BC: 5/6 PASS, 1 SKIPPED (stale `Op.Approve` not externally triggerable, verified by code review + unit tests). No crashes. Evidence in `doc/todo/holistic-review/protocol-communication/final/qa_evidence/`.
**Commit:** `9f7ddf72..682286b0` (12 commits)
**Next:** dead-code-overabstraction (parallel milestone, already closed in same window).
**Blockers:** None.

## 2026-04-16: dead-code-overabstraction — 4 phases, ~600 lines deleted, codex APPROVE

**What changed:**
- Phase 1 (safe deletions): removed 3 dead files (`StatusUtils.kt`, `SessionServicesSummaryFormatter.kt`, stray `.DS_Store`), 9 dead methods (`SessionServices.getSummary/updateApprovalMode`, `AppClassifier.addUserOverride`, 4 tool-result helpers, `ToolSpec.toFunctionSchema`, `ActionResult.isSuccess`), 3 dead composables (`ApiKeysSection`, `BackendSelector`, `SettingsDropdownOptionWithDescription`), dead `refreshOAuthToken()` in `OpenAiSignIn`, and dead `ScreenSnapshotDebug.captureQualityPath` field.
- Phase 2 (API surface): dropped dead `OnboardingViewModel.context` and `DefaultOnboardingDemoController.modelCatalog` constructor params; shrank `SessionHistoryManager` (made `loadSessionByFileName` private; deleted `deleteSessionByFileName`, `getMostRecentSession`, `hasActiveSession`, `endSession`, unused `scope` field); removed dead `data` field + writers from `ToolCallResult.Success` / `ToolExecutionResult.Success`.
- Phase 3 (onboarding): collapsed single-impl interface — deleted `OnboardingDemoController` interface, promoted `DefaultOnboardingDemoController` → concrete `OnboardingDemoController`, switched `OnboardingViewModel` from nullable late-assigned field to constructor injection.
- Phase 4 (delegate_task): removed `agent_name` parameter + validation + lookup from `DelegateTaskTool` (registry now always resolves to the single executor role); `AgentDefRegistry` + `AgentRoleDef` retained for real role resolution.

**Why:**
- Double-design holistic review dimension 12 identified these as zero-behavior-change safe deletions. Targets were confirmed dead by cross-file `rg` in both `app/src/main` and `app/src/test`. Speculative interface + two-phase injection in onboarding had one implementation and one call-site — the abstraction had no consumers.
- `delegate_task` exposed a fake multi-agent choice (`agent_name`) but the registry only resolved to executor, so the LLM was burning tokens on a no-op parameter.

**Key files:** `session/SessionServices.kt`, `tool/AppClassifier.kt`, `tool/ToolCallResult.kt`, `tool/ToolSpec.kt`, `tool/ToolRouter.kt`, `tool/impl/DelegateTaskTool.kt`, `platform/ActionResult.kt`, `platform/AccessibilityPlatform.kt`, `ui/settings/ApiKeyFields.kt`, `ui/settings/SettingsDropdowns.kt`, `auth/OpenAiSignIn.kt`, `onboarding/OnboardingViewModel.kt`, `onboarding/OnboardingDemoController.kt`, `app/MainActivity.kt`, `history/SessionHistoryManager.kt`
**Verification:** `./gradlew assembleDebug test` pass; `./gradlew lint` pre-existing-baseline only (2 `NewApi` errors in untouched `ServiceOverlayController.kt` — later patched in `29793c26`). Codex review: **APPROVE** (zero Critical/High/Medium; one Low observation that `delegate_task` no longer rejects a stray `agent_name` at runtime — deliberate, schema drops the field). Real-device QA on device EP0110MZ0BC: 6/6 scenarios PASS (onboarding fresh-install, settings UI, PRO delegation without `agent_name`, session history, normal single-turn, logcat crash check). Evidence in `doc/todo/holistic-review/dead-code-overabstraction/qa_evidence/`.
**Commit:** `43665d44..65897e0f` (7 commits)
**Next:** None for dimension 12 — deferred items (`AgentError.kt`, `LlmCredentialValidator`, `AgentEventDomains` marker interfaces, `ToolRouterContext` flatten) intentionally left, documented in `final/improvement_plan.md`. `AgentError.kt` separately removed during error-resilience work (commit `799336d3`).
**Blockers:** None.

## 2026-04-17: test-architecture — 28 unit-test tasks landed, codex APPROVE

**What changed:**
- Added 26 new test classes + extended 2 across 6 phases (LLM contract, orchestration seams, safety tools, onboarding/auth, chat/history, VD+trace). Test count: 833 → ~920.
- Fixed live bug: `OpenAIErrorClassifier` was matching `429`/`500` as substrings (status `14291` classified as rate-limit). Replaced `message.contains("429")` with non-alphanumeric-boundary regex that also rejects letter-adjacent tokens like `req_429abc`.
- Fixed latent JDK-21-API-on-JDK-17 crash: `SessionCoordinator.drainLocked()` used `List.removeFirst()` → `removeAt(0)`. Was never exercised before new `SessionCoordinatorTest` hit the drain path.
- Extracted `PermissionStateMonitor.deriveRepairModel(...)` as a pure companion fn so tests exercise pure logic instead of spying on the Android probes.
- Made production timeouts injectable (`ShellTool(timeoutSeconds)`, `HttpLlmCredentialValidator(connectTimeoutMs, readTimeoutMs)`) so tests can use short values; production defaults unchanged.
- Added test dep `com.squareup.okhttp3:mockwebserver:5.2.1` matching the existing okhttp 5.x on the main classpath.

**Why:**
- Double-design holistic review (2026-04-08) identified that the LLM contract boundary, orchestration seams, and onboarding/auth had near-zero direct test coverage — regressions were cheap to introduce and invisible until runtime.
- The classifier bug shipped because tests preserved it as a `KNOWN BUG` marker; plan required both fixing the code and removing the marker.
- Codex v2 review flagged 3 tests totaling 46s of real-time wait (`sleep 15`, `DISCONNECT_AT_START`, full retry backoff loop). Unit suite time budget matters; constructor injection was the right shape.

**Key files:** `app/src/main/kotlin/ai/closepaw/llm/OpenAIErrorClassifier.kt`, `.../session/SessionCoordinator.kt`, `.../onboarding/PermissionStateMonitor.kt`, `.../onboarding/HttpLlmCredentialValidator.kt`, `.../tool/impl/ShellTool.kt`, `app/build.gradle.kts`, 26 new test files under `app/src/test/kotlin/ai/closepaw/`
**Verification:** `./gradlew :app:testDebugUnitTest` passes. 3 codex review rounds: v1 REQUEST CHANGES (3 Medium → fixed in `301df1d5`), v2 REQUEST CHANGES (1 Medium slow tests → fixed in `6c3ee6fb`), v3 **APPROVE**. Real-device QA (device EP0110MZ0BC): S1 classifier fail-fast PASS, S2 multi-turn GOAL_ACHIEVED PASS, S3 provider routing PARTIAL PASS (chat-API routed, upstream stream issue unrelated), S4 airplane-mode SKIP per operator. No crashes/ANRs.
**Commit:** `ca787bc5..7c97c8e0` (30 commits)
**Next:** Backlog items if they ever become load-bearing (`CloudLlmRetryTest`, `SessionLlmBootstrapperTest` extend, `LlmInputItemsTraceSerializerTest`).
**Blockers:** None.

## 2026-04-17: tool-system-design — backfilled 5 tasks as done (no code change)

**What changed:**
- Verified all 5 `tsd-*` tasks were already implemented in earlier commits; marked parent `tool-system-design` and children `done` in `doc/todo/tasks.json`.
- No code changes — this was pure task-state bookkeeping.

**Why:** Tasks had been implemented during tool-system design work but never transitioned from `ready`/`running`. Spotted during test-architecture milestone close-out.

**Key files:** `doc/todo/tasks.json`
**Verification:** `appClassifier` threading, `ToolName.AskUser`/`Shell`, `ActionResult.Cancelled` mapping, `BLOCKED_COMMANDS` + truncation indicator, and absent `detectScrollBoundary` / `MobileActionName.Back|Home` branches all confirmed via grep. Tests green.
**Commit:** `7c97c8e0`
**Blockers:** None.

## 2026-04-16: perf-resources — 10 perf fixes, R8 enabled, real-device QA PASS

**What changed:**
- Hot-path O(n²) → O(n): `HistoryManager.compress()` delta-tracks tokens; `PerceptorInternals.applyTruncation()` uses `HashSet` dedup; `enrichEmptyTextElements()` sorts text sources by top + binary-searches per candidate while preserving candidate-order in joined output.
- `Perceptor.snapshot()` collapsed from two root traversals to one; per-pool counters (`PoolCounters`) keep interactive / non-interactive caps at `2 × maxElements` each so `applyTruncation` still gets a full score-based pool.
- `FileTraceRecorder`: `WriteOp.Flush` now actually calls `writer.flush()`; `AppendLine` stops flushing per event (BufferedWriter batches, close flushes).
- Streaming clients: `LlmLogger.isVerboseEnabled` gates `StringBuilder` / tool-call accumulators. `OpenAIResponseClient` + `ChatCompletionClient` now hold `AtomicReference<AutoCloseable>` to the active stream and cancel it from `awaitClose` (mirrors `CodexResponseClient`).
- `BitmapUtils.compressJpeg()` pre-sizes BAOS from pixel count; `AccessibilityScreenshotCapturer.compressScreenshot()` moves bitmap recycling into outer `finally` for exception safety.
- `app/proguard-rules.pro` added; release build enables `isMinifyEnabled=true` + `isShrinkResources=true`. Keep rules cover kotlinx.serialization, Shizuku AIDL, `android.hardware.display.IVirtualDisplayCallback` (and `IDisplayManager` / `VirtualDisplayConfig` stubs — review caught R8 renaming these to `e.a`), OpenAI SDK + Jackson reflection, Leap SDK JNI, HiddenApiBypass, Compose runtime, app entry points.
- `doc/dev/development.md`: new "Debug vs Release APK — always debug unless shipping" section plus two release-specific troubleshooting rows.

**Why:**
- Double-design review (Claude + Codex, revalidated 2026-04-16) identified the hotspots. Heaviest impact was release APK size and per-capture perception cost.
- R8 was simply never turned on; 74% of the APK was dead code.
- Trace `flush()` was a silent no-op masked by per-line flushing — removing the per-line flush without co-fixing `Flush` would have lost data.

**Key files:** `app/build.gradle.kts`, `app/proguard-rules.pro`, `history/HistoryManager.kt`, `perception/Perceptor.kt`, `perception/PerceptorInternals.kt`, `trace/FileTraceRecorder.kt`, `llm/LlmLogger.kt`, `llm/CodexResponseClient.kt`, `llm/OpenAIResponseClient.kt`, `llm/ChatCompletionClient.kt`, `platform/BitmapUtils.kt`, `platform/AccessibilityScreenshotCapturer.kt`, `doc/dev/development.md`
**Verification:** `./gradlew test`, `./gradlew assembleDebug`, `./gradlew assembleRelease` all pass. New unit tests: `BitmapUtilsTest`, `FileTraceRecorderTest`, two `HistoryManagerTest`, two `PerceptorInternalsTest` (1000-candidate scalability + out-of-order enrichment). Codex review saved to `doc/todo/holistic-review/performance-resources/codex_review.md` — 2 high + 1 medium fixed, 1 medium deferred (SDK limitation on pre-publication stream cancel). Real-device QA (device EP0110MZ0BC, 5 scenarios, all PASS) — `qa_report.md`. Release APK 96 MB → 25 MB (−74%).
**Commit:** 12b3e403..b0509f75 (14 commits)
**Next:** Before shipping a public release APK, run a full LLM tool-call on the signed release build to cover the `OpenAIResponseClient` / `ChatCompletionClient` R8 paths that QA (d) could not exercise without credentials.
**Blockers:** None.

## 2026-04-16: UI/UX Quality Improvement — 6 Phases Complete

**What changed:**
- Phase 1: Capsule composition correctness — removed composition-time side effects (`previousModeState`, input clearing), callers provide `previousMode`, clearing via `LaunchedEffect`
- Phase 2: Settings state hoisting — `rememberSaveable` for page/tab/provider, decoupled tab exploration from backend mutations
- Phase 3: Chat scroll — intent-based `followMode` with `programmaticScroll` guard, content-aware `scrollKey` (text + action card state), scroll-to-bottom FAB, removed `SimpleDateFormat` and double rotation animation
- Phase 4: Destructive action confirmation dialogs for session delete, Clear Traces, Clear Session History
- Phase 5: Accessibility — `IconButton` for onboarding back, `contentDescription` on capsule nav buttons, theme tokens + `Role.Button` on status island
- Phase 6: Overlay state unification — removed duplicate flows from `CapsuleOverlayHost`, added `hasIsland` to `CapsuleStateHolder`, `ServiceOverlayController` writes to stateHolder only

**Why:**
- State ownership drift was the primary quality problem — composition-time state writes, initialize-once patterns, duplicate state flows
- Chat scroll was broken for streaming (only tracked message count, not content growth)
- Three destructive actions lacked confirmation

**Key files:** `SmartCapsuleSurface.kt`, `SmartCapsuleCompose.kt`, `ChatScreen.kt`, `MessageBubble.kt`, `ActionCard.kt`, `SettingsSheet.kt`, `LlmAuthSettingsPage.kt`, `PermissionsAdvancedSettingsPage.kt`, `NavigationDrawer.kt`, `OnboardingShell.kt`, `StatusIslandCompose.kt`, `SmartCapsuleSurfaceParts.kt`, `CapsuleOverlayHost.kt`, `CapsuleStateHolder.kt`, `ServiceOverlayController.kt`
**Verification:** `./gradlew assembleDebug` + `./gradlew test` pass. Codex code review. Human on-device QA (capsule transitions, chat scroll, multi-turn regression). 4 scroll bugs found and fixed during QA.
**Commit:** d9be858a..ce30041c (7 commits)
**Next:** Error resilience and performance-resources task trees
**Blockers:** None

## 2026-04-10: LLM Integration Phases 5+6 — Local Semantics + Deduplication

**What changed:**
- Phase 5: `LocalLlmSemantics` object declaring 4 Leap backend limitations, cross-referenced at each occurrence in `LFMLLMClient`
- Phase 6: `ToolParameterExtractor` merging duplicate tool parameter extraction from `CodexRequestBuilder` and `LeapToolSchemaAdapter`

**Why:**
- Final phases of LLM integration holistic review — make implicit lossiness explicit, reduce code duplication

**Key files:** `LFMLLMClient.kt`, `ToolParameterExtractor.kt`, `CodexRequestBuilder.kt`, `LeapFunctionInterop.kt`
**Verification:** `./gradlew test` passed
**Commit:** 3d6b52ae, 5003fc5c
**Next:** All 6 phases complete — LLM integration holistic review done
**Blockers:** None

## 2026-04-10: LLM Integration Phase 4 — Extract Shared Helpers

**What changed:**
- Extracted `StreamRetryRunResult.closeFlow()` — identical post-retry epilogue block from 3 streaming clients into a single method on the result data class
- Streaming loop internals intentionally NOT extracted (fundamentally different event sources)

**Why:**
- Phase 4 of LLM integration holistic review — reduce duplication without over-engineering

**Key files:** `CloudStreamRetryRunner.kt`, `OpenAIResponseClient.kt`, `CodexResponseClient.kt`, `ChatCompletionClient.kt`
**Verification:** `./gradlew test` and `./gradlew assembleDebug` passed
**Commit:** 73916643
**Next:** Phase 5 — Declare local capability gaps
**Blockers:** None

## 2026-04-10: LLM Integration Phase 3 — Classification, SSL, Cancellation

**What changed:**
- 3.1: OpenAIErrorClassifier hardened with typed SDK exception fast-paths (Retry-After header extracted from SDK RateLimitException), domain exception preservation, string fallback last
- 3.2: InsecureSslConfig gated behind `BuildConfig.INSECURE_SSL_FOR_EVAL` (default false); eval runner passes `-PinsecureSslForEval=true`
- 3.3: CodexResponseClient stores OkHttp Call, cancels from awaitClose registered before streamWithRetry via launch{}

**Why:**
- Phase 3 of LLM integration holistic review — eliminates fragile heuristics, narrows SSL bypass surface, prevents 120s hang on flow cancellation

**Key files:** `OpenAIErrorClassifier.kt`, `InsecureSslConfig.kt`, `CodexResponseClient.kt`, `build.gradle.kts`, `runner_preflight.py`
**Verification:** `./gradlew test` and `./gradlew assembleDebug` passed
**Commit:** 913b5086..855d9fc4
**Next:** Phase 4 — Extract shared Responses helpers
**Blockers:** None

## 2026-04-10: LLM Integration Phase 2 — P0 Streaming Correctness

**What changed:**
- 2.1: Domain exceptions (RateLimitException/TransientException) preserved in streamWithRetry — no longer reclassified
- 2.2: Created event no longer blocks retry — only TextDelta/ToolCallDone set emittedEvent
- 2.3: response.incomplete → Failed with incomplete_reason; streaming loop breaks on Failed
- 2.4: ChatCompletionClient tracks sawFinishReason, throws TransientException if missing
- 2.5: Stream-ended-without-completion now throws TransientException (retryable)
- 2.6: MessageContentExtractor deleted, typed ChatCompletionInterop.extractStringContent used
- Review fix: CodexResponseClient streaming breaks immediately on Failed event

**Why:**
- Phase 2 of LLM integration holistic review — eliminates silent truncation, lost retries, garbage Leap input

**Key files:** `CloudStreamRetryRunner.kt`, `CodexResponseClient.kt`, `CodexSseParser.kt`, `ChatCompletionClient.kt`, `OpenAIResponseClient.kt`, `ChatCompletionInterop.kt`, `LFMLLMClient.kt`, `LlmLogger.kt`, `LlmInputItemsTraceSerializer.kt`
**Verification:** `./gradlew test` and `./gradlew assembleDebug` passed
**Commit:** 2a0c6b2e..6c821852
**Next:** Phase 3 — Harden error classification, SSL, cancellation
**Blockers:** None

## 2026-04-10: LLM Integration Phase 1 — Streaming/Retry Tests

**What changed:**
- Added 4 test classes (62 tests) covering streaming/retry system: `OpenAIErrorClassifierTest`, `CloudStreamRetryPolicyTest`, `CloudStreamRetryRunnerTest`, `CodexSseParserTest`
- 6 KNOWN BUG tests capture current broken behavior (will flip when fixes land): false-positive substring matching in classifier, domain exception reclassification, Created event blocking retry, response.incomplete treated as success
- Virtual-time assertions prove backoff timing and retryAfterMs loss

**Why:**
- Phase 1 prerequisite for LLM integration holistic review — tests must lock down current behavior before correctness fixes in Phase 2

**Key files:** `app/src/test/kotlin/ai/closepaw/llm/CloudStreamRetry{Runner,Policy}Test.kt`, `CodexSseParserTest.kt`, `OpenAIErrorClassifierTest.kt`
**Verification:** `./gradlew test` passed
**Commit:** 1391287e..11d76d0f
**Next:** Phase 2 — Fix P0 streaming correctness (5 items + MessageContentExtractor bug)
**Blockers:** None

## 2026-04-10: Tool System Design Improvements (5 phases)

**What changed:**
- Phase 0: Observation masking gap — `appClassifier` threaded through `PostActionAnalysis` and all executors so BLOCKED-app post-action observations are masked; `open_app` checks destination tier before launch (denied for BLOCKED apps)
- Phase 1a: ToolName metadata — `ask_user` and `shell` added to `ToolName` enum with `isScreenChanging=false`; previously parsed as `Unknown(isScreenChanging=true)`, causing false approval prompts and spurious `complete_task` drops
- Phase 3: Shell hardening — metacharacter rejection (`;|&`><$\n\r`), expanded blocklist (`env`, `xargs`, `find` added to existing `am/pm/reboot/su`), truncation indicator when output exceeds `MAX_OUTPUT_CHARS`
- Phase 2: Action runtime normalization — `SwipeExecutor` returns `Cancelled` (not `Failed`) on system cancellation; `TypeExecutor` explicit `Cancelled` handling at each attempt; `ScrollExecutor` fails immediately for unresolvable explicit targets; `PointActionExecutorCore` retarget observability (diagnostic note in warnings)
- Phase 5: Dead code cleanup — removed `UiChangeDetector.detectScrollBoundary()`, removed `UIActionInvocation.detectScrollBoundary()`, removed `MobileActionName.Back/Home` from `PolicyEngine.isEscape()` (unreachable path), deleted `DataQueryInvocation.kt` (zero callers), removed duplicate `OpenAppTool` companion constants, `SystemButtonTool` unreachable else branch now throws

**Why:**
- Holistic tool-system-design review (Claude + Codex double-blind validation) found observation masking gap, metadata misclassification, shell bypass vectors, inconsistent cancellation handling, and accumulated dead code

**Key files:** `PostActionAnalysis.kt`, `ObservationBuilder.kt`, `ToolName.kt`, `PolicyEngine.kt`, `ShellTool.kt`, `OpenAppTool.kt`, `PointActionExecutorCore.kt`, `ClickExecutor.kt`, `LongPressExecutor.kt`, `TypeExecutor.kt`, `ScrollExecutor.kt`, `SwipeExecutor.kt`, `MobileActionInvocation.kt`, `UIActionInvocation.kt`, `UiChangeDetector.kt`, `SystemButtonTool.kt`
**Verification:** `./gradlew assembleDebug test` passes
**Commit:** 98e2d907..9d07973a (6 commits)
**Next:** None — tool-system-design complete
**Blockers:** None

## 2026-04-10: Platform Robustness Hardening (8 phases + 8 follow-up fixes)

**What changed:**
- P1: VD lifecycle serialization — `VdLifecycleArbiter` state machine (Stopped/Running/Broken), lifecycle mutex with preDrainState, Running lease for ops, start() rollback, binder death → Broken + clearCachedProxies
- P2: Bounded callback waits — shared `boundedCallback()` helper with timeout (5s a11y, 3s PixelCopy) and invokeOnCancellation; late-callback HardwareBuffer cleanup
- P3: Gesture cancellation safety — best-effort ACTION_CANCEL on interrupted gestures, MOVE failure fails gesture
- P4: Window selection coherence — layer-ordered topmost window for actions/privacy/screenshot on both platforms
- P5: Real display metrics — WindowManager.maximumWindowMetrics instead of app content metrics
- P6: Boundary correctness — CancellationException rethrown, Perceptor off Main, truthful app launch, surface replacement
- P7: Resource cleanup — window recycling, debug screenshot retention cap, dead code removal
- P8: Regression tests for VdLifecycleArbiter and BoundedCallback
- Follow-up: arbiter admission race fix (preDrainState), PixelCopy bitmap safety, HardwareBuffer leak, shell input fallback (`input -d`), setDisplayId round-trip verification, VD overlay approval visibility, debug-only exported viewer
- Codex final review: Draining state (keeps resources for in-flight ops during stop), start-from-Broken cleanup, PixelCopy timeout/failure split, isActive race removal, force capsule for attention modes, PixelCopy counter reset on surface replacement

**Why:**
- Holistic review found unbounded callback waits, VD lifecycle races, stale binder proxies, window selection bugs, resource leaks, and silent failures at the platform boundary
- QA on real device uncovered HiddenApiBypass failure (void method false positive in setDisplayId), and VD overlay hiding approval dialogs

**Key files:** VdLifecycleArbiter.kt, VirtualDisplayPlatform.kt, BoundedCallback.kt, VirtualDisplayInputInjector.kt, VirtualDisplayCaptureCoordinator.kt, AccessibilityScreenshotCapturer.kt, AccessibilityPlatform.kt, VirtualDisplayWindowAccessor.kt, OverlayLocationPolicy.kt
**Verification:** `./gradlew assembleDebug test` passes; QA on P0110 (Android 16): a11y mode, VD mode, hybrid screenshots, PixelCopy/LIVE_PREVIEW, multi-session lifecycle, overlay approval all verified
**Commit:** ddb581d..d7f3e47 (8 commits)
**Next:** None — platform robustness complete
**Blockers:** None

## 2026-04-09: Agent Core Simplicity (9 tasks)

**What changed:**
- P0: Fixed action-signature derivation bug — signatures now derived from actually-executed tools, not pre-computed plan
- P1: Split ExecutorStepPolicy into isFinalTurn() + DelegationSummaryFormatter; removed dead WarnApproaching/narrativeSummaryOnLimit
- P1: Unified AgentDef + AgentDefinition into AgentRoleDef — one role model for top-level and delegated agents
- P2: Removed dead NavigationState fields (consecutiveScrollActions, recentActions, fingerprint, CRITICAL severity), PreTurnContext.appTier
- P3: Extracted TurnObservation as canonical per-turn screen payload, eliminating prompt/history temporal coupling
- P3: Consolidated all agent event emission through AgentEventDispatcher
- P3: Extracted shared ActionTarget decoder for ActionDescriptionFormatter + ActionSignature
- P4: Added TextRecovery telemetry to Turn.kt; named magic delay constants
- Codex review: fixed screenshot-only observation divergence, removed vestigial action-signature return path
- Codex final review: deleted orphaned ActionSignature.kt, fixed history-resume fail-open bug, fixed new-session event-collection race

**Why:**
- Double-design review (Claude + Codex) identified runtime invariant mismatch, duplicate role definitions, and accumulated dead code
- Owner corrected P0 scope: multi-action turns are intentional for form-filling; only the signature derivation bug needed fixing

**Key files:** agent/TurnExecutionPhaseRunner.kt, agent/AgentTurnRunner.kt, agent/cognition/policy/TurnBudget.kt, agent/definition/AgentRoleDef.kt, agent/cognition/prompt/TurnObservation.kt, agent/AgentEventDispatcher.kt, agent/ActionTarget.kt
**Verification:** `./gradlew test` passes (all 59 tasks), Codex code review completed
**Commit:** 55b597f..fd5060b (12 commits)
**Next:** None — ACS complete
**Blockers:** None

## 2026-04-08/09: Security & Privacy Hardening (10 tasks)

**What changed:**
- P0.1: Intent control plane lockdown — production ignores external security-sensitive extras, goal dispatch requires user confirmation
- P0.2: Capture-layer privacy gate — blocked-app masking moved before trace artifact writes; null-package = skip capture (fail-closed)
- P0.3: Fail-closed encrypted storage — no plaintext SharedPreferences fallback; legacy plaintext migration code deleted (pre-release, no backward compat)
- P0.4: Auth PII removed from logs (id_token claims, email, OAuth callback request line)
- P1.1: Password field suppression — `Perceptor` checks `isPassword`, replaces text with `[password]`
- P1.2: Shell blocklist reduced to `am/pm/reboot/su`; no metacharacter restriction, no path denylist (security theater with `sh -c`)
- P1.3: InsecureSslConfig moved to debug-only source set (compile-time guarantee)
- P1.4: AppClassifier fails closed on missing/corrupt/invalid-tier app_tiers.json
- P2.2: Data & Storage settings section (trace toggle, one-tap wipe)
- P2.3: Security regression tests (6 unit tests + 1 instrumentation test)
- Post-review fixes: capture gate null-package, remaining PII logs, legacy secret scrub, strict tier parsing, trace toggle plumbed to AgentService

**Why:**
- Holistic security review (Claude+Codex double-design) identified boundary placement and privilege composition issues
- Owner review simplified several items (editable-field suppression dropped, shell kept permissive, OAuth localhost hardening dropped as ineffective)

**Key files:** `MainActivityIntentApplier.kt`, `AppSettingsStore.kt`, `OAuthCredentialStore.kt`, `OpenAIOAuth.kt`, `Perceptor.kt`, `ShellTool.kt`, `InsecureSslConfig.kt` (debug/release split), `AppClassifier.kt`, `AccessibilityPlatform.kt`, `ObservationBuilder.kt`, `PermissionsAdvancedSettingsPage.kt`, `AgentService.kt`
**Verification:** `./gradlew test assembleDebug` pass; ADB smoke test 4/4 pass; Codex code review → 6 findings all addressed
**Commit:** `a3b4a60..92f1b79` (15 commits)
**Next:** `sec-app-tiers-expansion` (expand app_tiers.json to 100-1000 apps, design in progress)
**Blockers:** None

## 2026-04-07: Codex multi-turn fix + settings auth label

**What changed:**
- CodexRequestBuilder: assistant messages now use `"output_text"` content type (was `"input_text"` for all roles, causing HTTP 400 on multi-turn Codex conversations)
- SettingsHomePage: subtitle dynamically shows "OAuth" or "API key" based on `authMethod` (was hardcoded "API key")

**Why:**
- Codex API rejects `input_text` for assistant role content — only `output_text` and `refusal` are valid
- Settings label was misleading for OAuth users

**Key files:** `CodexRequestBuilder.kt`, `SettingsHomePage.kt`, `SettingsSheet.kt`
**Verification:** `./gradlew assembleDebug` pass; on-device (nubia M153) — multi-turn Codex conversation runs 7+ turns without HTTP 400; Settings correctly shows "GPT-5.4 · OAuth"
**Commit:** `59532da`
**Next:** None
**Blockers:** gpt-5.4 via CodexResponseClient sends malformed tool calls (multiple targeting methods) — model-side issue, not actionable in our code

## 2026-04-04: Settings page restructure

**What changed:**
- Two-level settings navigation: Home (3 nav rows) → sub-pages with AnimatedContent transitions
- LLM & Authentication sub-page: 3-tab structure (Sign In / API Key / Local)
  - Sign In: OpenAI OAuth account card + RESPONSE-only model selector
  - API Key: provider sub-selector (OpenAI/OpenRouter/Novita) + linked model/key
  - Local: local model selector + download status
- Agent Behavior sub-page: max turns, agent mode, perception mode
- Permissions & Advanced sub-page: a11y, overlay, debug toggle
- Split manual OpenAI API key from OAuth token (credential isolation)
- Shared OAuth suspend helpers (`auth/OpenAiSignIn.kt`) — reused by onboarding and settings
- Provider-linked model filtering (`ModelCatalog.modelsFor/preferredModelFor`)
- Model/executor canonicalization on provider switch
- OpenAI Auth Card with 4 states (SignedOut/InProgress/SignedIn/Error)
- Tab switching immediately persists backend/authMethod
- One-time migration for legacy credential split

**Why:**
- OAuth was invisible in settings — no post-onboarding management
- Flat layout didn't scale (8 sections, 26 parameters)
- Manual OpenAI key was destroyed on OAuth sign-in (overloaded single field)

**Key files:**
- New: `SettingsHomePage.kt`, `LlmAuthSettingsPage.kt`, `AgentBehaviorSettingsPage.kt`, `PermissionsAdvancedSettingsPage.kt`, `OpenAiAuthCard.kt`, `OpenAiSignIn.kt`
- Modified: `SettingsSheet.kt`, `AppSettingsState.kt`, `AppSettingsStore.kt`, `MainActivity.kt`, `MainActivityContent.kt`, `ModelCatalog.kt`, `OnboardingViewModel.kt`

**Design docs:** `doc/todo/settings_redesign/` (UX spec, double-design, aligned design)
**QA:** On-device (nubia M153) — Settings navigation PASS, API key path PASS, OAuth state display PASS
**Verification:** `./gradlew assembleDebug` + `./gradlew test` pass; Codex code review completed + fixes applied
**Commit range:** `dc8cd16..HEAD`
**Next:** On-device OAuth re-login E2E test (Sign In button flow)
**Blockers:** None

## 2026-04-02: CodexResponseClient for OAuth users

**What changed:**
- New `CodexResponseClient`: raw OkHttp + SSE client targeting `chatgpt.com/backend-api/codex/responses` for OAuth users
- New `CodexRequestBuilder`: serializes ResponseInputItem/FunctionTool to Codex-specific JSON format
- New `CodexSseParser`: SSE parsing with parallel-safe `ToolCallAccumulator` (map-keyed by output_index), normalizes Codex-specific events (`response.done` → `Completed`)
- `LLMClientFactory`: OAuth routing via `__AUTH_METHOD_OPENAI` signal in apiKeys map; `isOAuth()` detection for OPENAI provider + RESPONSE API
- `AppSettingsState`: new `authMethod` property, `buildApiKeys()` includes OAuth signal
- `MainActivity`: initializes `authMethod` from `OnboardingStore` at startup and after onboarding completion
- Added direct OkHttp dependency (`com.squareup.okhttp3:okhttp:4.12.0`)

**Why:**
- OAuth access tokens lack platform API scopes (`api.responses.write`, `model.request`), so they cannot use `api.openai.com`. The Codex endpoint at `chatgpt.com/backend-api` is the only working path for ChatGPT subscription users.

**Key files:** `llm/CodexResponseClient.kt`, `llm/CodexRequestBuilder.kt`, `llm/CodexSseParser.kt`, `llm/LLMClientFactory.kt`, `app/AppSettingsState.kt`, `app/MainActivity.kt`
**Design doc:** `doc/todo/openai_oauth/path_b_design.md`
**Verification:** `./gradlew assembleDebug` + `./gradlew test` pass; code review completed
**Commit:** `9693895`
**Next:** On-device E2E validation with real OAuth token
**Blockers:** None

## 2026-04-02: Onboarding wizard implementation

**What changed:**
- Full first-launch onboarding wizard: Accessibility → Overlay → Battery → API Key → Demo → Complete
- `OnboardingStore`: own prefs file, encrypted draft key, legacy user migration
- `OnboardingViewModel`: state machine with auto-advance, A11y polling, step persistence
- `PermissionStateMonitor`: reusable A11y/Overlay/Battery live checks
- `HttpLlmCredentialValidator`: direct HTTP validation with auth vs network error mapping
- `DefaultOnboardingDemoController`: throwaway AgentSession, "Open Settings" goal, 60s timeout
- `OnboardingScreen` + `OnboardingShell` + `OnboardingSteps`: full-screen step UI with progress bar
- `PermissionRepairCard`: post-onboarding in-chat repair for revoked permissions
- `MainActivity`: root routing (onboarding vs chat), eval bypass, onResume integration
- Code review fixes: JSON injection (JSONObject), encrypted draft safety, Dispatchers.Main callbacks, Mutex for demo session

**Why:**
- First-run experience was confusing — users hit permission failures with no guidance
- Wizard ensures all required setup (A11y, Overlay, API key) is complete before chat

**Key files:** `onboarding/` package (8 files), `ui/onboarding/` package (4 files), `MainActivity.kt`, `MainActivityContent.kt`
**Design docs:** `doc/todo/onboarding_wizard/ux_design.md`, `doc/todo/onboarding_wizard/eng_design.md`
**Verification:** `./gradlew assembleDebug` + `./gradlew test` pass; code review completed
**Commit range:** `080385b..6a869cb`
**Next:** On-device QA, unit tests for ViewModel state machine
**Blockers:** None

## 2026-04-01: Security hardening QA + network security config

**What changed:**
- Full QA of basic-security (5 items) and agent-security (KISS 4+1 layers) on physical device (nubia M153) with gpt-5.4 via Tailscale HTTPS
- QA results (all PASS):
  - EncryptedSharedPreferences: encrypted XML on device, plain prefs clean, migration works, corruption fallback in place
  - allowBackup=false, cleartext blocked: confirmed via package flags
  - InsecureSslConfig: gated behind BuildConfig.DEBUG (code review)
  - PolicyEngine: NORMAL apps → Allow (Settings navigation, 6 tool calls logged), CAUTIOUS → AskUser (WhatsApp, approval UI with Allow/Session/Always buttons displayed), BLOCKED → Deny (Robinhood, screen masked, memory write blocked)
  - Approval UI: three-tier buttons rendered correctly, 60s timeout → cancel works
  - Perception gate: BLOCKED app screen masked — LLM saw "⛔ Screen hidden" message
  - Memory gate: remember_experience on BLOCKED app → Error (blocked)
- Replaced `usesCleartextTraffic="false"` with `networkSecurityConfig` to support emulator: release blocks all cleartext, debug allows 10.0.2.2/localhost only
- Verified emulator cleartext works on remote desktop (emulator-5554, HTTP to cproxy via 10.0.2.2)
- Fixed Tailscale serve config: 443→workflow(5173), 8741→cproxy(18080)
- Updated development.md with Tailscale/cproxy/emulator documentation
- Renamed remote SSH user moonkey→qiguo, hostname qiguo-ld1→desktop across scripts and docs

**Why:**
- Close all QA gaps before moving on from Phase 1 security work
- Emulator path was broken by cleartext=false; networkSecurityConfig gives per-build granularity

**Key files:** `app/src/main/res/xml/network_security_config.xml`, `app/src/debug/res/xml/network_security_config.xml`, `AndroidManifest.xml`, `doc/dev/development.md`, `scripts/remote/`
**Verification:** `./gradlew assembleDebug` + `./gradlew test` pass; live QA on nubia M153 (gpt-5.4 via Tailscale); emulator smoke test on remote desktop
**Commit:** `e1790d5..1398d42`
**Next:** Pick up next Phase 1 task (onboarding-wizard) or priority.md #0 (prompt-tune)
**Blockers:** None

## 2026-03-24: Doc structure alignment with /init-all and /update-doc standards

**What changed:**
- Renamed `AIDEV.md` → `CLAUDE.md` as source of truth; `AGENTS.md`, `GEMINI.md`, `.cursorrules` now symlink to `CLAUDE.md`
- Renamed `.ai-dev/` → `.claude/` as source of truth directory; `.agents/`, `.codex/` now symlink to `.claude/`
- Rewrote `CLAUDE.md` from 106 → 46 lines following pointer convention (≤50 lines, no embedded architecture/patterns)
- Split 5 oversized doc/main/ files (>300 line limit):
  - `app/history.md` (550) → `app/history/{overview,persistence,runtime,models}.md` (316 total)
  - `ui/overlay.md` (443) → `ui/overlay.md` (144) + `ui/capsule/architecture.md` (116)
  - `protocol/protocol.md` (419) → `protocol/{overview,events,config}.md` (297 total)
  - `infra/platform.md` (410) → `infra/platform.md` (152) + `infra/virtual_display.md` (74)
  - `infra/llm.md` (366) → trimmed to 138 lines
- Trimmed `doc/dev/development.md` from 328 → 265 lines
- Fixed archive naming: `diff_review` → `260206_diff_review`, `future_features.md` → `260206_future_features/`
- Updated all `.ai-dev/` → `.claude/` references in skill files, sop files, and PROGRESS.md
- Updated all cross-references from deleted files to new split locations

**Why:**
- Align with `/init-all` (CLAUDE.md as authority, ≤50 lines pointer doc, symlink direction) and `/update-doc` (≤300 line docs, YYYYMMDD archive naming) standards

**Key files:** `CLAUDE.md`, `.claude/`, `doc/main/README.md`, `doc/main/app/history/`, `doc/main/protocol/`, `doc/main/infra/virtual_display.md`, `doc/main/ui/capsule/architecture.md`
**Verification:** All README.md cross-reference links verified
**Commit:** `6ee3fd6`
**Blockers:** None

## 2026-03-13: Memory V2 Implementation

**What changed:**
- Replaced the V1 memory layout with V2 scope-first files: `memory/user.md`, `memory/device.md`, and `memory/apps/<package>.md`.
- Added a shared `MemorySchema` and rewrote `MemoryStore` to write canonical fixed-section markdown with full timestamps.
- Simplified `MemoryRecaller` to deterministic full-file recall for user, device, and current app memory.
- Redesigned `remember_experience` around `scope + section` routing instead of V1 `category + [kind]`.
- Memory writes now propagate explicit success/failure so `remember_experience` and failure auto-retain do not silently report success on failed saves.
- Updated failure auto-retain to write app `Operational Notes`, refreshed prompt guidance, and added regression tests for store, recall, prompt ordering, and tool validation/execution.
- Updated the main memory doc and added an implementation plan for this task.

**Why:**
- Bring runtime behavior in line with the agreed Memory V2 design: KISS scope-first files, deterministic recall, and no extra session-log memory layer.

**Key files:** `app/src/main/kotlin/ai/closepaw/memory/MemorySchema.kt`, `app/src/main/kotlin/ai/closepaw/memory/MemoryStore.kt`, `app/src/main/kotlin/ai/closepaw/memory/MemoryRecaller.kt`, `app/src/main/kotlin/ai/closepaw/tool/impl/RememberExperienceTool.kt`, `app/src/main/kotlin/ai/closepaw/agent/Agent.kt`, `app/src/main/kotlin/ai/closepaw/agent/definition/StandaloneAgentDef.kt`, `doc/main/agent/memory.md`, `doc/todo/0.5_memory/memory_v2_implementation_plan.md`
**Verification:** `./gradlew assembleDebug testDebugUnitTest`, `./gradlew assembleDebug lint test`
**Commit:** `56aded7`
**Blockers:** None

## 2026-03-13: Memory V2 Design Note Capture

**What changed:**
- Added `doc/todo/0.5_memory/memory_v2_note.md` to preserve the agreed Memory V2 design discussion and final sketch.

**Why:**
- Preserve the current design consensus in-repo so implementation can proceed from one concrete source of truth instead of scattered chat context.

**Key files:** `doc/todo/0.5_memory/memory_v2_note.md`
**Commit:** `79b28a9`
**Blockers:** None

## 2026-03-13: Eval Config Overlay Loading

**What changed:**
- Eval config loading now always starts from `eval/config/default.yaml` and deep-merges any explicitly requested config on top.
- Parallel eval now uses the same merged config path as serial eval, so worker shard configs inherit default settings before device-specific overrides apply.

**Why:**
- Remove duplicated config copies, keep remote config minimal, and make config variants inherit new default settings automatically instead of drifting.

**Key files:** `eval/aw_bridge/runner.py`, `eval/aw_bridge/parallel_runner.py`, `eval/config/remote.yaml`
**Commit:** `2fbfeb2`
**Blockers:** None

## 2026-03-13: Eval Memory Hygiene

**What changed:**
- Eval bridge now clears `files/memory` before each task launch.
- Eval configs now exclude `remember_experience` by default so the memory tool is not exposed during eval.
- Updated eval and memory docs to document the clean-eval contract.
- Added regression tests for config loading and bridge cleanup/launch behavior.

**Why:**
- Prevent `RememberExperience` and persisted memory from contaminating eval runs while keeping the app runtime logic simple.

**Key files:** `eval/aw_bridge/native_agent_bridge.py`, `eval/aw_bridge/runner.py`, `eval/config/default.yaml`, `eval/config/remote.yaml`, `eval/config/gpt54_never_succeeded.yaml`, `eval/tests/test_native_agent_bridge.py`, `eval/tests/test_runner.py`, `doc/main/agent/memory.md`, `doc/main/eval/eval.md`, `eval/README.md`
**Verification:** `./gradlew assembleDebug test`, `./gradlew lint`, `python3 -m pytest eval/tests/test_native_agent_bridge.py eval/tests/test_runner.py eval/tests/test_runner_preflight_policy.py`
**Commit:** `7f18cdb`
**Next:** Keep future eval configs and task overrides aligned with the same clean-memory contract.
**Blockers:** None

## 2026-03-11: Remote Eval Worker Phase 2 & 3

**What changed:**
- Phase 2 (Dual Emulator): `provision.sh` now creates both `AndroidWorldAvd` and `AndroidWorldAvd2`. Runbook extended with dual-emulator baseline prep and parallel eval commands.
- Phase 3 (Operational Hardening): New `eval_tmux.sh` tmux wrapper for SSH-disconnect-safe eval. New `openai-proxy-tunnel.service` systemd unit with autossh auto-reconnect. `proxy_tunnel.sh` rewritten as service manager (install/start/stop/status/logs/manual). `provision.sh` now installs `autossh` and `tmux`.
- Updated `/cog-tune` and `/autotune` skills with remote eval commands and references.
- Added explicit git push/pull sync step to remote eval runbook.
- Remote smoke test: AVD2 created, autossh service verified active, proxy reachable. Required keychain env sourcing fix for passphrase-protected SSH keys.

**Why:**
- Enable parallel eval on remote to cut wall-clock time, and harden operations so long-running evals survive SSH disconnects and tunnel drops.

**Key files:** `scripts/remote/provision.sh`, `scripts/remote/eval_tmux.sh`, `scripts/remote/openai-proxy-tunnel.service`, `scripts/remote/proxy_tunnel.sh`, `doc/dev/remote_eval_worker.md`, `.claude/skills/autotune/SKILL.md`, `.claude/skills/cog-tune/SKILL.md`
**Verification:** `bash -n` on all scripts, remote smoke test (AVD2 created, autossh active, proxy ok)
**Commit:** `afa9713..ced92dc`
**Next:** Run dual-emulator baseline prep and parallel eval end-to-end on `desktop`. Ubuntu 22.04 upgrade deferred.
**Blockers:** None

## 2026-03-11: Remote Eval Worker Hardening and Validation

**What changed:**
- Hardened remote eval config loading so `android_world.adb_path` and emulator paths are expanded before use.
- Routed eval preflight ADB calls through the configured binary instead of assuming `adb` is on `PATH`.
- Pinned remote provisioning to emulator `32.1.15` from `emulator-linux_x64-10696886.zip` for Ubuntu 18.04 compatibility.
- Updated the remote worker docs to reflect the actual stale-checkout proxy failure mode and the validated rerun outcome.
- Re-ran the five previously failing app tasks on `desktop` after syncing the fixed bridge path; all five passed in `eval/results/20260311_102822`.

**Why:**
- Remove remote-only setup drift around ADB resolution, emulator versioning, and proxy routing so remote eval failures surface as real task behavior instead of infra noise.

**Key files:** `eval/aw_bridge/runner.py`, `eval/aw_bridge/runner_preflight.py`, `eval/tests/test_runner.py`, `scripts/remote/provision.sh`, `doc/dev/remote_eval_worker.md`, `doc/todo/remote_emulator/implementation_summary.md`
**Verification:** `eval/.venv/bin/python -m unittest eval.tests.test_runner eval.tests.test_runner_preflight_policy`, `bash -n scripts/remote/provision.sh`, `./gradlew assembleDebug lint test`, remote rerun `eval/results/20260311_102822` (`5/5` scripted success)
**Commit:** `76ee8ba`
**Next:** Harden remote worker operations (`autossh`/service wrapper, dual-emulator path) and investigate the `ExpenseAddSingle` agent-side verification gap.
**Blockers:** None

## 2026-03-11: Remote Emulator Eval Worker

**What changed:**
- Provisioned `desktop` (Ubuntu 18.04, i9-7900X, 62G RAM) as a remote Android eval worker with headless emulator.
- New `scripts/remote/provision.sh`: one-shot setup (JDK 17, Python 3.11, Android SDK, AVD).
- New `scripts/remote/proxy_tunnel.sh`: SSH tunnel helper for LLM proxy access.
- New `eval/config/remote.yaml`: remote-specific eval config with correct adb path.
- Modified `scripts/prepare_baseline.sh` and `scripts/eval_parallel.sh`: added `--headless` flag, venv Python preference, `~/android-sdk` emulator search path.
- Changed cproxy `proxy.js` bind from `127.0.0.1` to `0.0.0.0` so remote workers can connect directly.
- New `doc/dev/remote_eval_worker.md`: operational runbook.

**Why:**
- Move eval compute off the laptop onto a dedicated machine with more CPU/RAM and KVM support for faster x86_64 emulation.

**Key files:** `scripts/remote/provision.sh`, `scripts/remote/proxy_tunnel.sh`, `eval/config/remote.yaml`, `scripts/prepare_baseline.sh`, `scripts/eval_parallel.sh`, `doc/dev/remote_eval_worker.md`

**Design:** `doc/todo/remote_emulator/remote_emulator_eval_codex.md`

## 2026-03-11: Memory Auto-Retain Fallback Fix (`faf18ab`)

**What changed:**
- Auto-retain pitfall hook now tracks `lastKnownPackage` through the agent turn loop and uses it as fallback when `getCurrentPackageName()` returns null at failure time (e.g. a11y tree has 0 elements).
- Added logging for auto-retain writes.

**Why:**
- When a task fails with 0 accessibility elements, `getCurrentPackageName()` returned null, silently skipping the pitfall memory write. E2E testing on local emulator with gpt-5.4 revealed this gap.

**E2E verification:** Memory recall confirmed working — seeded `com.android.settings.md` was injected as `## Recalled Memory` into LLM prompt and visible to model.

**Key files:** `agent/Agent.kt`
**Commit:** `faf18ab`

## 2026-03-11: Cross-Session Memory System V1

**What changed:**
- New `memory/` package: `MemoryStore` (file I/O, entry caps, path traversal protection, atomic writes) and `MemoryRecaller` (elastic-budget recall per turn).
- New `RememberExperienceTool`: LLM-callable tool with `[workflow]`/`[pitfall]`/`[verification]` kind tags. Auto-allowed, cognitive tool (non-screen-changing).
- Recall injected into prompt between working memory and app skill via new `recalledMemory` param in `PromptBuilder.buildInputItems()`.
- Elastic budget: device 1KB + user_prefs 1.5KB + app gets remainder up to 3.5KB, total ≤6KB. Newest entries kept on truncation.
- Failure auto-retain hook in `Agent.kt`: when task fails and LLM never called `remember_experience`, auto-saves a `[pitfall]` entry.
- Storage: `<filesDir>/memory/apps/<package>.md`, `user_prefs.md`, `device.md`. Entry caps: 30/app, 20/user_prefs, 10/device.
- Added `ToolName.RememberExperience` variant, `StandaloneAgentDef` allowedTools + Long-Term Memory system prompt section.

**Why:**
- Let the agent learn from experience across sessions. App-specific quirks, pitfalls, and verification strategies persist as markdown and are recalled when the same app is foregrounded.

**Key files:** `memory/MemoryStore.kt`, `memory/MemoryRecaller.kt`, `tool/impl/RememberExperienceTool.kt`, `agent/Agent.kt`, `agent/TurnPlanningPhaseRunner.kt`, `agent/cognition/prompt/PromptBuilder.kt`, `agent/definition/StandaloneAgentDef.kt`, `session/SessionServices.kt`, `tool/ToolName.kt`

**Design:** `doc/todo/0.5_memory/final/design.md`

## 2026-03-10: OpenClaw Family Common Capability Analysis

**What changed:**
- Added a new comparative analysis doc at `doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md`.
- Distinguished core OpenClaw-family runtimes from adjacent ecosystem projects in `.reference/claws/`.
- Summarized the shared capability stack across OpenClaw variants: ingress surfaces, sessioned runtime, tools/execution, memory/workspace, autonomy/scheduling, ops, and security boundaries.
- Mapped platform boundaries into three buckets: desktop/cloud-specific strengths, Android-portable capabilities, and mobile-native advantages that desktop/cloud agents do not naturally own.

**Why:**
- Provide a clearer product and architecture frame for deciding what ClosePaw should absorb from OpenClaw-family systems versus what should be reinterpreted natively for a phone-first agent.

**Key files:** `doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md`, `doc/changelog.md`

## 2026-03-09: Perception High-Fidelity Capture and Text Targeting Alignment (`f23287d`)

**What changed:**
- `Perceptor` capture now keeps raw `text`, `description`, and `hintText` without capture-time whitespace normalization.
- Prompt `text` now reflects visible/accessibility text semantics only (`text -> description -> hintText`) and no longer falls back to `resourceId` suffix.
- Added downstream-only `normalizeForMatching()` and used it for `text_index` / `desc_index` grouping and `TargetResolver` text lookup.
- `TargetResolver` now prioritizes prompt-text semantics first, then falls back to `description` / `hintText` when needed.
- Added dedicated perception/targeting tests, a new `doc/main/infra/perception.md` SOTA doc, and a perception-specific design note at `doc/autotune/round_14/percetion_fidelity_codex.md`.

**Why:**
- Preserve accessibility observations as source-of-truth and avoid irreversible capture-time rewriting.
- Fix drift between what the model sees in prompt JSON and what `text` targeting can actually resolve.

**Key files:** `Perceptor.kt`, `PerceptorInternals.kt`, `TargetResolver.kt`, `PerceptorTest.kt`, `TargetResolverTest.kt`, `doc/main/infra/perception.md`, `doc/autotune/round_14/percetion_fidelity_codex.md`

## 2026-03-09: Autotune Rounds 10-14 — qwen3.5 Targeted Tuning (0→20/22)

**What changed:**
- System prompt (`StandaloneAgentDef.kt`): added cross-app destination rule (#10), strengthened Information mode anti-hallucination, expanded Completion section with file-ops verification + date verification + scratchpad cross-checking. Later softened anti-hallucination to defer to app skill guidance.
- Perception (`PerceptorInternals.kt:236`): changed `MULTI_NEWLINE` replacement from `"\n"` to `"\n\n"` to preserve paragraph breaks in a11y tree.
- Debug logging (`TurnPlanningPhaseRunner.kt:158`): added `Log.d` for app skill lookup results.
- New/updated app skills:
  - `com.simplemobiletools.calendar.pro` — NumberPicker scroll, 24h format, date verification
  - `code.name.monkey.retromusic` — Songs tab add flow
  - `org.tasks` — diff-based completion detection, overflow menu location, priority turn budget, date reasoning example, partial-answer guidance
  - `de.dennisguse.opentracks` — Edit-based activity type checking with smart name filtering
- Eval config (`default.yaml`): added `max_turns: 50` for SportsTracker tasks + TasksHighPriorityTasks.

**Why:**
- Targeted tuning of 22 tasks that failed in R8/R9 (qwen3.5 model). Improved from 0/22 to 20/22 (90.9%) across 5 rounds.

**Key files:** `StandaloneAgentDef.kt`, `PerceptorInternals.kt`, `TurnPlanningPhaseRunner.kt`, `app/src/main/assets/app_skills/`, `eval/config/default.yaml`

## 2026-03-07: Add `/prompt-tune` Skill

**What changed:**
- New skill at `.claude/skills/prompt-tune/` with `SKILL.md` and `references/ownership_model.md`.
- Encodes the three-layer ownership model from the Round 4 prompt refactor design: core system prompt → tool descriptions → app skills.
- Provides a 5-step workflow: classify ownership → read target → apply change → anti-pattern check → validate.
- Includes a decision tree for ownership classification and an anti-pattern table.
- Absorbed `llm_best_practices.md` content (was a dead reference under cog-tune).
- Updated `/cog-tune` SKILL.md: replaced "External best practices" section with "Related skills" pointing to `/prompt-tune`.

**Why:**
- Separates diagnosis (`/cog-tune`) from treatment (`/prompt-tune`). Cog-tune analyzes traces and classifies root causes; prompt-tune applies the actual prompt/tool-desc/app-skill changes with ownership guardrails.

**Key files:** `.claude/skills/prompt-tune/SKILL.md`, `.claude/skills/prompt-tune/references/ownership_model.md`, `.claude/skills/cog-tune/SKILL.md`

## 2026-03-07: Fix Unchanged-Fallback Double-Click (`2042beb`)

**What changed:**
- `PointActionExecutorCore.buildPointActionOutcome()` no longer treats `Unchanged` as channel failure. Returns `ActionOutcome.Success` with `verified=false` and warning instead.
- No automatic fallback to next channel (e.g. `gesture_tap`) when `node_action_click` succeeds but screen content stays the same.
- Updated `mobile_action.md` pipeline docs and "Accepted but unchanged" semantics.

**Why:**
- When a click succeeds but the screen content happens to stay the same (e.g. random number repeats), the old logic fell through to `gesture_tap` causing a spurious second click. Observed in BrowserMultiply A11Y T16: 4th button click repeated the same number → extra click overwrote the 5th number.

**Key files:** `PointActionExecutorCore.kt`, `ClickExecutorTest.kt`, `doc/main/infra/tool/mobile_action.md`

## 2026-03-06: Provider Base URL Override (Local Proxy Support)

**What changed:**
- Added `OPENAI_BASE_URL` support in `.env` to route OPENAI-provider models through a local proxy without modifying `llm_models.json`
- New `ModelCatalog.withBaseUrlOverrides()` applies provider-level base URL overrides at session bootstrap via `__BASE_URL_<PROVIDER>` convention in the `apiKeys` map
- Full intent chain: `.env` → `debug-run.sh` / eval runner → intent extra → `AppSettingsState` → `SessionLlmBootstrapper` → `ModelCatalog` → client creation
- Eval runner (`native_agent_bridge.py`) now forwards `openai_base_url` intent extra
- Enabled `android:usesCleartextTraffic="true"` in manifest for HTTP proxy connections

**Why:**
- Route gpt-5.4/gpt-5.2 through a local OpenAI-compatible proxy (e.g. for quota management) as a script-level config, not baked into the model catalog

**Key files:** `session/SessionLlmBootstrapper.kt`, `llm/ModelCatalog.kt`, `app/AppSettingsState.kt`, `app/MainActivityIntentPayload.kt`, `eval/aw_bridge/native_agent_bridge.py`, `eval/aw_bridge/runner.py`, `scripts/debug-run.sh`, `AndroidManifest.xml`

## 2026-03-06: BrowserMultiply Eval — Two New Click Issues

**What changed:**
- Documented two new click failure patterns from BrowserMultiply eval runs (A11Y: `20260306_230038`, VD: `20260306_232810`):
  1. **First-click-after-launch**: `node_action_click` on Files RecyclerView item returns `true` but UI unchanged after 1800ms. Same click succeeds after other interactions (long_press → context menu). Reproducible in both A11Y and VD modes. Root cause unknown.
  2. **Unchanged-fallback double-click**: When a click succeeds but screen content stays the same (e.g. random number repeats), `UiChangeDetector` sees `Unchanged` → executor falls through to `gesture_tap` → extra click. Caused BrowserMultiply to lose the 5th number.
- Planned fix for #2: treat `Unchanged` as warning only, not channel failure.

**Why:**
- Both issues cause BrowserMultiply to fail (MaxTurnsReached at 30). Issue #2 is actionable — removing the fallback-on-unchanged behavior avoids the double-click. Issue #1 is a deeper Files RecyclerView quirk that needs further investigation.

**Key files:** `doc/main/infra/tool/mobile_action.md`, eval results `eval/results/20260306_230038/`, `eval/results/20260306_232810/`

## 2026-03-06: VD Click Transport Experiment (`edb4acd`, `55976dd`)

**What changed:**
- Added Shizuku injection and display-id targeting to `DebugActionExecutor` and `action-test.sh` for isolated transport testing.
- Ran 2x2 agent-loop matrix (A11Y/VD × node_click-first/gesture_tap-first) plus 8 isolated action-debug tests and secondary display smoke test on Files RecyclerView.
- Updated `mobile_action.md` with corrected transport matrix — previous claim that VD `injectInputEvent` "works like real touch" was not supported by test data on this surface.

**Why:**
- Files RecyclerView is a known difficult surface where `dispatchGesture` false-succeeds. Needed to determine if Shizuku `injectInputEvent` was a viable alternative. Result: `node_action_click` is the only reliable channel for this surface (8/8). Shizuku false-succeeded (0/3), but this may be a test setup issue — most other app surfaces work fine with all transports. Current priority order `node_click → gesture_tap` confirmed correct.

**Key files:** `app/.../debug/DebugActionExecutor.kt`, `scripts/action-test.sh`, `doc/main/infra/tool/click_transport_experiment.md`, `doc/main/infra/tool/mobile_action.md`

## 2026-03-06: Harden Post-Action Change Detection (`5ee310a`)

**What changed:**
- Excluded `isFocused` from `UiChangeDetector` fingerprint — RecyclerView items gain focus on `ACTION_CLICK` without actually navigating, causing false-positive "Changed" verdicts.
- Extended `PostActionAnalysis` verify window from 800ms (300+500) to 1800ms (300+500+1000) with a third retry round for slow transitions like intent resolution.
- Documented `gesture_tap` false-success pattern on Files RecyclerView items: `dispatchGesture()` accepted but UI unchanged. This is a platform limitation, not a runtime bug.

**Why:**
- The two bugs compounded: the detector reported success on the first channel (node click + isFocused false positive), so the runtime never fell through to retry or fallback. With both fixes, node click now correctly retries and succeeds within the 1800ms window.

**Key files:** `app/.../tool/action/UiChangeDetector.kt`, `app/.../tool/action/PostActionAnalysis.kt`, `doc/main/infra/tool/mobile_action.md`

## 2026-03-06: Click Hotspot Selection Fix (`04618f3`)

**What changed:**
- `refinePointActionTarget()` now searches for the nearest actionable child within a promoted container instead of defaulting to `container.center`.
- Added `findBestActionableChild()` with 80% area threshold and distance-based scoring.
- Added diagnostic logging for target promotion decisions.

**Why:**
- Files app regression: `container.center` landed on a dead zone where `ACTION_CLICK` was accepted but had no effect. The icon hotspot worked. Fix generalizes to any compound row without app-specific workarounds.

**Key files:** `app/src/main/kotlin/ai/closepaw/tool/action/PointActionExecutorCore.kt`

## 2026-03-06: Local Parallel Eval Workflow (`68d1f88`..`456d3aa`)

**What changed:**
- Hardened `eval/aw_bridge/parallel_runner.py` so the supervisor owns one-time APK build/install, honors `runner.perform_bridge_setup`, and merges results back into `eval/results/<run_id>/`.
- Added `scripts/eval_parallel.sh` as the standard local 2-device entry point for `AndroidWorldAvd` (`emulator-5554`) and `AndroidWorldAvd2` (`emulator-5556`).
- Updated eval docs plus `/autotune` and `/cog-tune` guidance to use the standard result contract and the new local parallel workflow.

**Why:**
- Cut eval wall-clock time with a real local parallel path without creating a second result format or breaking downstream tooling such as `scoreboard.py` and eval analysis flows.

## 2026-03-06: Prompt Ownership Refactor (`02844a5`)

**What changed:**
- Added asset-backed app skills under `app/src/main/assets/app_skills/` and load them per turn from the current foreground package.
- Injected the active app skill into the prompt between Working Memory and Observation.
- Rewrote the standalone and planner system prompts around cross-tool policy instead of app/tool-specific appendices.
- Expanded tool descriptions so `mobile_action`, `open_app`, `shell`, and `complete_task` own their local semantics.

**Why:**
- Separate global behavior, tool semantics, and app-specific knowledge so tuning changes land in one clear owner layer instead of accumulating in one monolithic prompt.

**Key files:** `app/src/main/kotlin/ai/closepaw/agent/cognition/prompt/AppSkillRepository.kt`, `app/src/main/kotlin/ai/closepaw/agent/TurnPlanningPhaseRunner.kt`, `app/src/main/kotlin/ai/closepaw/agent/definition/StandaloneAgentDef.kt`, `app/src/main/kotlin/ai/closepaw/agent/definition/PlannerAgentDef.kt`, `app/src/main/assets/app_skills/`
