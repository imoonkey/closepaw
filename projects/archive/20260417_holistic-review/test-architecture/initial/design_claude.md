# Test Architecture Review

**Date**: 2026-04-08
**Scope**: 68 test files, ~10,293 lines across `app/src/test/`
**Production files**: ~180 Kotlin files across 14 modules

---

## Perspective A: Coverage Analysis

### Module Coverage Map

| Module | Prod Files | Test Files | Coverage Assessment |
|--------|-----------|------------|---------------------|
| agent/ | 24 | 12 | **STRONG** - Core loop, Turn, policies, sub-agent, error recovery all tested |
| tool/ | 24 (core+impl+action) | 17 | **STRONG** - All tools validated, action executors well tested |
| perception/ | 6 | 3 | **GOOD** - Perceptor, internals, screen summary tested |
| llm/ | 17 | 4 | **MODERATE** - Factory, catalog, LFM conversion tested; streaming clients untested |
| session/ | 12 | 7 | **GOOD** - AgentSession, state classes, checkpoint, services routing tested |
| history/ | 12 | 7 | **STRONG** - HistoryManager compression, recording, storage, converters all tested |
| memory/ | 3 | 2 | **COMPLETE** - MemoryStore and MemoryRecaller both covered |
| platform/ | 14 + 12 virtual | 2 | **LOW** - Only AppManager and NodeActionPerformer; virtual display untested |
| protocol/ | 21 | 0 (indirect) | **ACCEPTABLE** - Mostly data classes/enums; tested indirectly through consumers |
| ui/ | 28+ | 8 | **MODERATE** - Overlay model/state tested; Compose UI correctly untested |
| app/ | 10 | 2 | **MODERATE** - OverlayLocationPolicy and AppSettingsState tested |
| auth/ | 3 | 0 | **GAP** - Zero direct tests |
| trace/ | 11 | 0 (1 indirect) | **GAP** - Only tested via AgentTraceObservabilityTest |
| onboarding/ | 8 | 0 | **GAP** - Zero tests |
| model/ | 1 | 1 | **COMPLETE** |
| debug/ | 2 | 0 | **ACCEPTABLE** - Debug tooling, low risk |

### Critical Coverage Gaps (Risk-Ranked)

#### HIGH RISK - Missing tests for core error/retry paths

1. **CloudStreamRetryPolicy / CloudStreamRetryRunner** (`llm/`)
   - These control retry behavior for LLM API calls. Incorrect retry logic = wasted API credits, user-visible failures, or infinite loops.
   - Risk: A broken retry policy could cause the agent to fail silently on transient errors or burn through rate limits.
   - **Priority: HIGH**

2. **OpenAIErrorClassifier** (`llm/`)
   - Classifies HTTP errors (429, 500, etc.) to decide retry vs. fail.
   - Risk: Misclassification = retrying non-retryable errors or failing on retryable ones.
   - **Priority: HIGH**

3. **TurnExecutionPhaseRunner / TurnPlanningPhaseRunner** (`agent/`)
   - The two main phase runners for agent execution. Only action signature extraction is tested; the actual orchestration of perception -> LLM call -> tool execution is not unit tested.
   - Risk: Moderate. Covered by integration tests (AgentErrorRecoveryTest, LocalBackendTurnRoutingTest), but phase-level edge cases are not isolated.
   - **Priority: MEDIUM**

4. **AgentTurnRunner** (`agent/`)
   - Coordinates Turn with streaming, phase runners, and event emission. No dedicated test.
   - Risk: Moderate. Partially exercised through Agent integration tests.
   - **Priority: MEDIUM**

#### MEDIUM RISK - Untested modules with real logic

5. **CognitionTraceRedactor** (`trace/`)
   - Redacts PII (emails, tokens, API keys) from trace artifacts. Tested indirectly through AgentTraceObservabilityTest, but edge cases (partial redaction, nested content) are not isolated.
   - Risk: PII leakage in trace files.
   - **Priority: MEDIUM**

6. **MobileActionInvocation / UIActionInvocation** (`tool/impl/`, `tool/handlers/`)
   - The actual execution logic for mobile actions. Validation is tested (MobileActionToolTest) but the execution path (target resolution -> action dispatch -> observation building) is only integration tested through ClickExecutor/ScrollExecutor.
   - Risk: Moderate. Action executors provide good coverage of the dispatch chain.
   - **Priority: MEDIUM**

7. **TypeExecutor / SwipeExecutor** (`tool/action/`)
   - Click, scroll, and long-press executors all have dedicated tests. Type and swipe do not.
   - Risk: Type actions are the most fragile action type in practice (hint text contamination is tested at NodeActionPerformer level, but the full TypeExecutor chain is not).
   - **Priority: MEDIUM**

8. **HistoryConfig** (`history/`)
   - Configuration for compression parameters. Tested indirectly through HistoryManagerTest, but the config defaults and validation are not isolated.
   - **Priority: LOW**

#### LOW RISK - Missing tests for peripheral code

9. **auth/ module** (3 files)
   - OAuthCredentialStore, OpenAIOAuth, OpenAiSignIn. All Android-specific (EncryptedSharedPreferences, WebView). Hard to unit test without Robolectric. OAuth token flow is implicitly tested through AppSettingsStateTest which tests the `buildApiKeys` integration point.
   - Risk: Low for auth storage bugs; high-value path protected at integration level.
   - **Priority: LOW**

10. **onboarding/ module** (8 files)
    - ViewModel, state, store, demo controller. Mostly UI orchestration. HttpLlmCredentialValidator makes network calls. OnboardingState is a simple state machine.
    - Risk: Low. User-facing but not safety-critical.
    - **Priority: LOW**

11. **platform/virtualdisplay/** (12 files)
    - All Shizuku/AIDL code. Cannot be unit tested without the actual device/service. Appropriate to test through instrumented tests.
    - Risk: Acceptable to not unit test.
    - **Priority: SKIP**

12. **LLM streaming clients** (OpenAIResponseClient, ChatCompletionClient, CodexResponseClient, CodexSseParser)
    - Network clients that wrap SDK calls. Minimal testable logic beyond what the SDKs provide.
    - Risk: Low. Factory and catalog tests cover the construction paths.
    - **Priority: LOW**

### Well-Covered Critical Paths

These high-value areas have strong test coverage:
- **Policy engine** (PolicyEngineTest): Blocked/cautious/normal tiers, escape actions, all approval modes
- **Tool arbitration** (TurnToolPolicyTest): Cognitive vs. screen tools, completion deferral, ordering
- **Loop detection** (LoopDetectionPolicyTest): Screen similarity, Jaccard threshold, window-based detection
- **Tool router lifecycle** (ToolRouterTest): Approval flow, timeout, concurrent tracking, cancellation cleanup
- **History compression** (HistoryManagerTest): All P0 invariants documented and tested
- **Agent error recovery** (AgentErrorRecoveryTest): DNS failure, timeout, context length
- **Turn tool filtering** (TurnToolFilteringTest): Inline tool call recovery, allowlist enforcement, streaming suppression
- **Sub-agent lifecycle** (SubAgentRunnerTest): Success, timeout, complete_task forwarding, step limit narrative
- **Click executor** (ClickExecutorTest): Node click, gesture fallback, text promotion, hotspot selection, out-of-bounds, verification
- **Node action performer** (NodeActionPerformerTest): Resource recycling, hint mismatch guard, text entry with hint contamination
- **Prompt builder** (PromptBuilderTest): Memory, observation, function call history, recalled memory/app skill injection

---

## Perspective B: Test Quality Analysis

### Quality Strengths

1. **Behavioral testing over implementation testing**: Tests overwhelmingly test behavior ("click with element_index uses node click as primary") rather than implementation details. This is exactly right.

2. **Descriptive test names**: Backtick-style Kotlin test names are consistently descriptive. Examples: `blocked app denied even in auto_approve mode`, `run recovers inline tool call from text payload`, `P0 compress never removes USER_INTENT messages`.

3. **Appropriate use of fakes over mocks**: The test infrastructure uses `FakeAndroidPlatform`, `RecordingPlatform`, and scripted LLM clients instead of heavy mocking. This makes tests more readable and less fragile. Only `NodeActionPerformerTest` uses mockk extensively, which is appropriate for testing accessibility node interactions.

4. **Edge case coverage**: Tests cover non-obvious edge cases well:
   - Inline tool call recovery from malformed text (TurnToolFilteringTest)
   - Hint text contamination in text entry (NodeActionPerformerTest)
   - Child hotspot selection vs. container center (ClickExecutorTest)
   - Path traversal rejection in package names (MemoryStoreTest, AssetAppSkillRepositoryTest)
   - Text promotion to containing clickable row (ClickExecutorTest)
   - Duplicate description indexing (PerceptorTest)

5. **Clean test structure**: Tests follow arrange-act-assert consistently. Helper methods are well-named. TemporaryFolder rules are used for filesystem tests.

6. **Coroutine testing done correctly**: All async tests use `runTest` with `TestScope`, `advanceTimeBy`, and `advanceUntilIdle`. No real delays or Thread.sleep.

### Quality Issues

#### Issue 1: Duplicate RecordingPlatform implementations
**Severity: MINOR (maintenance burden)**

Three separate `RecordingPlatform` implementations exist in:
- `ClickExecutorTest.kt` (line 558)
- `ScrollExecutorTest.kt` (line 98)
- `LongPressExecutorTest.kt` (line 228)

All three are nearly identical. They should be consolidated into `TestFixtures.kt`.

#### Issue 2: Duplicate FakeLLMClient implementations
**Severity: MINOR (maintenance burden)**

At least 7 distinct fake LLM client classes across test files:
- `FakeTestLLMClient` in AgentModelResolverTest
- `CapturingTurnLLMClient` in TurnToolFilteringTest
- `LocalBackendTestLLMClient` in LocalBackendTurnRoutingTest
- `NoopLLMClient` in AgentTraceObservabilityTest
- `AgentErrorTestLLMClient` in AgentErrorRecoveryTest
- `SubAgentTestLLMClient` / `ScriptedSubAgentLLMClient` in SubAgentRunnerTest
- `SessionTestLLMClient` / `FailingStreamingSessionTestLLMClient` in AgentSessionTest

Some serve different purposes (capturing, scripted, failing), but a shared `TestLLMClientBuilder` or configurable fake would reduce boilerplate.

#### Issue 3: Mixed assertion libraries
**Severity: TRIVIAL**

Most tests use Google Truth (`assertThat`), but `LLMClientFactoryTest` and `ModelCatalogTest` use JUnit4 assertions (`assertEquals`, `assertTrue`). Not a functional issue, but inconsistent.

#### Issue 4: `buildServices()` helper duplication
**Severity: MINOR**

Multiple test files define their own `buildServices()` function that constructs a `SessionServices` with nearly identical boilerplate:
- `AgentErrorRecoveryTest.buildServices()`
- `AgentTraceObservabilityTest.buildServices()`
- `SubAgentRunnerTest.buildServices()`
- `LocalBackendTurnRoutingTest` (inline)
- `AgentSessionTest.buildSession()`

A shared `TestSessionServices` builder would reduce ~150 lines of duplication and make it harder for configurations to drift.

#### Issue 5: Some tests verify structure rather than behavior
**Severity: TRIVIAL**

A few tests in `AgentDefTest` verify exact tool lists (`containsExactly("mobile_action", "system_button", ...)`) and exact prompt substrings. These will break on any tool addition. However, these serve as "snapshot" tests documenting the agent definitions, which is a legitimate purpose.

#### Issue 6: Potential flaky test
**Severity: LOW**

`SubAgentRunnerTest.runner returns timeout when child exceeds timeout` uses real timing with `delayMs = 200` and `timeoutMs = 10`. Under `runTest` the virtual time dispatcher should handle this correctly, but the tight margin (10ms vs 200ms) is worth noting. Currently implemented correctly with `runTest`.

### Tests That Should NOT Be Added

Per KISS principle, these are correctly absent:
- Compose UI rendering tests (covered by visual QA via `/ux-visual-debug`)
- Virtual display platform tests (requires real device/Shizuku)
- LLM client network integration tests (mocked at factory level)
- Protocol event data class tests (pure data, no logic)
- Theme/Color/Shape tests (pure constants)

---

## Synthesis

### Overall Assessment: STRONG with specific gaps

The test suite is well-designed. It focuses on the right things: agent loop behavior, tool validation/execution, policy decisions, action dispatch chains, and history management. The architectural decision to use fakes over mocks results in tests that are both readable and resilient to refactoring.

### Key Strengths
1. Core agent loop thoroughly tested from multiple angles (Turn, Agent, SubAgent, Session)
2. Action execution chain has excellent coverage with multi-channel fallback verification
3. Policy engine tested exhaustively across all tiers and approval modes
4. History compression has documented P0 invariants with dedicated tests
5. Test infrastructure (FakeAndroidPlatform, TestFixtures) enables clean test setup

### Key Gaps
1. Retry/error classification for LLM API calls (CloudStreamRetryPolicy, OpenAIErrorClassifier) -- highest risk gap
2. CognitionTraceRedactor edge cases (PII leakage risk)
3. TypeExecutor/SwipeExecutor action chains
4. Test infrastructure duplication (RecordingPlatform, LLMClient fakes, buildServices helpers)

### Coverage Metrics Estimate
- **Critical path coverage**: ~85% (agent loop, tools, policies, actions)
- **Module coverage by file count**: 68 test files / ~180 prod files = 38% (appropriate -- many prod files are data classes, UI, or platform-specific)
- **Behavioral coverage of testable logic**: ~75-80%
