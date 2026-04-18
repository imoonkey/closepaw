package ai.closepaw.onboarding

import ai.closepaw.app.AppSettingsState
import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.OAuthTokens
import ai.closepaw.auth.OpenAiSignInResult
import ai.closepaw.auth.openAiSignIn
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Characterization tests for the ApiKey step FSM defined in
 * doc/main/state_machines/onboarding_apikey_step.md.
 *
 * Covers every transition (manual + OAuth) and every guard rejection. The VM
 * is constructed with permissions+earlier outcomes already satisfied so each
 * test starts on the ApiKey step with `OAuthReady` as the entry state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApiKeyStepStateTest {

    private lateinit var store: OnboardingStore
    private lateinit var settingsState: AppSettingsState
    private lateinit var permissionMonitor: PermissionStateMonitor
    private lateinit var authStore: AuthStore
    private lateinit var demoController: OnboardingDemoController
    private lateinit var server: MockWebServer
    private lateinit var catalog: ModelCatalog

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/v1").toString()
        val catalogJson = """
            {
              "oai-resp":{"display_name":"OAI","provider":"OPENAI_API","api":"response","model_id":"gpt-test","base_url":"$baseUrl"},
              "oai-codex":{"display_name":"Codex","provider":"OPENAI_CODEX","api":"response","model_id":"gpt-test"},
              "or-chat":{"display_name":"OR","provider":"OPENROUTER","api":"chat","model_id":"or-test","base_url":"$baseUrl"}
            }
        """.trimIndent()
        catalog = ModelCatalog.fromJson(catalogJson)

        store = mockk(relaxed = true)
        settingsState = mockk(relaxed = true)
        permissionMonitor = mockk(relaxed = true)
        authStore = mockk(relaxed = true)
        demoController = mockk(relaxed = true)

        every { store.loadOutcomes() } returns onApiKeyStepOutcomes()
        every { authStore.has(any()) } returns false
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        every { permissionMonitor.isBatteryOptimized() } returns true
        every { settingsState.openaiBaseUrl } returns ""
    }

    @After
    fun tearDown() {
        try { server.shutdown() } catch (_: Exception) {}
        unmockkAll()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun onApiKeyStepOutcomes(apiKey: StepOutcome = StepOutcome.Pending) = StepOutcomes(
        accessibility = StepOutcome.Done,
        overlay = StepOutcome.Done,
        battery = StepOutcome.Skipped,
        apiKey = apiKey,
        demo = StepOutcome.Pending,
    )

    private fun makeVm(scope: CoroutineScope) = OnboardingViewModel(
        store = store,
        settingsState = settingsState,
        modelCatalog = catalog,
        permissionMonitor = permissionMonitor,
        authStore = authStore,
        demoController = demoController,
        scope = scope,
    )

    private suspend fun drain(scope: CoroutineScope, testScope: TestScope) {
        repeat(20) {
            testScope.testScheduler.advanceUntilIdle()
            val children = scope.coroutineContext.job.children.toList()
            if (children.isEmpty()) {
                testScope.testScheduler.advanceUntilIdle()
                return
            }
            children.forEach { it.join() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ENTRY STATES
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `entry with OPENAI provider yields OAuthReady`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.selectedProvider).isEqualTo(OnboardingProvider.OPENAI_API)
        assertThat(vm.authMethod).isEqualTo(ApiKeyAuthMethod.OAUTH)
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.OAuthReady)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `selectProvider non-OPENAI switches to MANUAL Empty`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)

        vm.selectProvider(OnboardingProvider.OPENROUTER)
        assertThat(vm.authMethod).isEqualTo(ApiKeyAuthMethod.MANUAL)
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.Empty)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `selectProvider OPENAI from OPENROUTER returns to OAuthReady`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)

        vm.selectProvider(OnboardingProvider.OPENAI_API)
        assertThat(vm.authMethod).isEqualTo(ApiKeyAuthMethod.OAUTH)
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.OAuthReady)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `selectAuthMethod MANUAL resets to Empty`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)

        vm.selectAuthMethod(ApiKeyAuthMethod.MANUAL)
        assertThat(vm.authMethod).isEqualTo(ApiKeyAuthMethod.MANUAL)
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.Empty)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `selectAuthMethod OAUTH resets to OAuthReady`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectAuthMethod(ApiKeyAuthMethod.MANUAL)

        vm.selectAuthMethod(ApiKeyAuthMethod.OAUTH)
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.OAuthReady)
        scope.coroutineContext.job.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // MANUAL PATH TRANSITIONS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `Empty to Editing on non-blank key`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)

        vm.onApiKeyChanged("sk-x")
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.Editing("sk-x"))
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Editing to Empty on blank key`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-x")

        vm.onApiKeyChanged("")
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.Empty)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Editing to Empty on whitespace-only key (blank guard)`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-x")

        vm.onApiKeyChanged("   ")
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.Empty)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Editing to Validating to Valid on 200, writes credential and advances`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-good")

        vm.validateApiKey()
        drain(scope, this)

        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Done)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        coVerify { authStore.set(LLMProvider.OPENROUTER, AuthCredential.ApiKey("sk-good")) }
        verify { settingsState.updateModel("or-chat") }
        verify { store.saveOutcome(WizardStep.ApiKey, StepOutcome.Done) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Validating to Invalid on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-bad")

        vm.validateApiKey()
        drain(scope, this)

        val state = vm.stepState
        assertThat(state).isInstanceOf(ApiKeyStepState.Invalid::class.java)
        assertThat((state as ApiKeyStepState.Invalid).key).isEqualTo("sk-bad")
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Pending)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Validating to TransientError on 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-x")

        vm.validateApiKey()
        drain(scope, this)

        val state = vm.stepState
        assertThat(state).isInstanceOf(ApiKeyStepState.TransientError::class.java)
        assertThat((state as ApiKeyStepState.TransientError).key).isEqualTo("sk-x")
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Invalid to Editing on subsequent non-blank key change`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-bad")
        vm.validateApiKey(); drain(scope, this)
        assertThat(vm.stepState).isInstanceOf(ApiKeyStepState.Invalid::class.java)

        vm.onApiKeyChanged("sk-bad2")
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.Editing("sk-bad2"))
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Invalid to Empty on blank key change`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-bad")
        vm.validateApiKey(); drain(scope, this)

        vm.onApiKeyChanged("")
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.Empty)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Invalid to Validating on validateApiKey reusing stored key`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-bad")
        vm.validateApiKey(); drain(scope, this)
        assertThat(vm.stepState).isInstanceOf(ApiKeyStepState.Invalid::class.java)

        vm.validateApiKey(); drain(scope, this)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Done)
        coVerify { authStore.set(LLMProvider.OPENROUTER, AuthCredential.ApiKey("sk-bad")) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `TransientError to Validating via retryValidation`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-x")
        vm.validateApiKey(); drain(scope, this)
        assertThat(vm.stepState).isInstanceOf(ApiKeyStepState.TransientError::class.java)

        vm.retryValidation(); drain(scope, this)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Done)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `validateApiKey returns TransientError when no model in catalog for provider`() = runTest {
        // Build a catalog WITHOUT NOVITA, then drive the VM into selecting NOVITA. We do this
        // by calling selectProvider through a fresh VM whose catalog has no NOVITA entry.
        catalog = ModelCatalog.fromJson("""{"or":{"display_name":"OR","provider":"OPENROUTER","api":"chat","model_id":"m","base_url":"${server.url("/v1")}"}}""")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.NOVITA)
        vm.onApiKeyChanged("sk-x")

        vm.validateApiKey(); drain(scope, this)
        val state = vm.stepState
        assertThat(state).isInstanceOf(ApiKeyStepState.TransientError::class.java)
        assertThat((state as ApiKeyStepState.TransientError).message).contains("No model found")
        scope.coroutineContext.job.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // MANUAL PATH GUARD REJECTIONS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `validateApiKey from Empty is no-op`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)

        vm.validateApiKey(); drain(scope, this)
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.Empty)
        assertThat(server.requestCount).isEqualTo(0)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `validateApiKey after success auto-advance does not re-validate`() = runTest {
        // After Valid the VM auto-advances to Demo (stepState becomes DemoStepState.Ready),
        // so the `else -> return` branch in validateApiKey() short-circuits any further
        // validation attempt. We can't easily pause mid-Valid (delay(400) runs under
        // UnconfinedTestDispatcher), so this characterizes the post-advance no-op path.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-good")
        vm.validateApiKey(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        assertThat(vm.stepState).isInstanceOf(DemoStepState::class.java)

        vm.validateApiKey(); drain(scope, this)
        assertThat(server.requestCount).isEqualTo(1)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `retryValidation from non-TransientError is no-op`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-x")

        val before = vm.stepState
        vm.retryValidation(); drain(scope, this)
        assertThat(vm.stepState).isEqualTo(before)
        assertThat(server.requestCount).isEqualTo(0)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `onApiKeyChanged is no-op when currentStep is not ApiKey`() = runTest {
        every { store.loadOutcomes() } returns StepOutcomes() // Accessibility step
        every { permissionMonitor.isAccessibilityEnabled() } returns false
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        val before = vm.stepState

        vm.onApiKeyChanged("sk-x")
        assertThat(vm.stepState).isEqualTo(before)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `selectProvider is no-op when currentStep is not ApiKey`() = runTest {
        every { store.loadOutcomes() } returns StepOutcomes()
        every { permissionMonitor.isAccessibilityEnabled() } returns false
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)

        vm.selectProvider(OnboardingProvider.OPENROUTER)
        assertThat(vm.selectedProvider).isEqualTo(OnboardingProvider.OPENAI_API)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `selectAuthMethod is no-op when currentStep is not ApiKey`() = runTest {
        every { store.loadOutcomes() } returns StepOutcomes()
        every { permissionMonitor.isAccessibilityEnabled() } returns false
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)

        vm.selectAuthMethod(ApiKeyAuthMethod.MANUAL)
        assertThat(vm.authMethod).isEqualTo(ApiKeyAuthMethod.OAUTH)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `selectProvider with same provider is no-op (does not reset state)`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-x")

        vm.selectProvider(OnboardingProvider.OPENROUTER)
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.Editing("sk-x"))
        scope.coroutineContext.job.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // OAUTH PATH TRANSITIONS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `OAuthReady to OAuthInProgress to OAuthSuccess writes credential and advances`() = runTest {
        mockkStatic("ai.closepaw.auth.OpenAiSignInKt")
        val tokens = OAuthTokens(
            accessToken = "acc-1", refreshToken = "ref", expiresAt = 0L, email = "u@x"
        )
        coEvery { openAiSignIn(any()) } returns OpenAiSignInResult.Success(tokens)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)

        vm.startOAuth(); drain(scope, this)

        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Done)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        coVerify {
            authStore.set(
                LLMProvider.OPENAI_CODEX,
                match<AuthCredential.OAuth> { it.accessToken == "acc-1" && it.email == "u@x" }
            )
        }
        verify { settingsState.updateModel("oai-codex") }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `OAuthInProgress to OAuthError on Error result`() = runTest {
        mockkStatic("ai.closepaw.auth.OpenAiSignInKt")
        coEvery { openAiSignIn(any()) } returns OpenAiSignInResult.Error("denied")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)

        vm.startOAuth(); drain(scope, this)

        val state = vm.stepState
        assertThat(state).isInstanceOf(ApiKeyStepState.OAuthError::class.java)
        assertThat((state as ApiKeyStepState.OAuthError).message).isEqualTo("denied")
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Pending)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `OAuthError to OAuthInProgress on retry via startOAuth`() = runTest {
        mockkStatic("ai.closepaw.auth.OpenAiSignInKt")
        val tokens = OAuthTokens("acc-2", "r", 0L, "u@x")
        coEvery { openAiSignIn(any()) } returnsMany listOf(
            OpenAiSignInResult.Error("first"),
            OpenAiSignInResult.Success(tokens),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.startOAuth(); drain(scope, this)
        assertThat(vm.stepState).isInstanceOf(ApiKeyStepState.OAuthError::class.java)

        vm.startOAuth(); drain(scope, this)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Done)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `OAuthInProgress to OAuthReady via cancelOAuth`() = runTest {
        mockkStatic("ai.closepaw.auth.OpenAiSignInKt")
        val gate = CompletableDeferred<OpenAiSignInResult>()
        coEvery { openAiSignIn(any()) } coAnswers { gate.await() }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)

        vm.startOAuth()
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.OAuthInProgress)

        vm.cancelOAuth()
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.OAuthReady)

        // Late completion after cancel must not mutate state — if the job were
        // not actually cancelled, this would flip stepState to OAuthError("late").
        gate.complete(OpenAiSignInResult.Error("late"))
        drain(scope, this)
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.OAuthReady)
        scope.coroutineContext.job.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // OAUTH PATH GUARD REJECTIONS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `startOAuth is no-op when already OAuthInProgress`() = runTest {
        mockkStatic("ai.closepaw.auth.OpenAiSignInKt")
        val gate = CompletableDeferred<OpenAiSignInResult>()
        var calls = 0
        coEvery { openAiSignIn(any()) } coAnswers { calls++; gate.await() }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)

        vm.startOAuth()
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.OAuthInProgress)
        vm.startOAuth() // second call should be ignored
        assertThat(calls).isEqualTo(1)
        gate.complete(OpenAiSignInResult.Error("done"))
        scope.coroutineContext.job.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // RE-ENTRY FROM BACK NAVIGATION
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `re-entry with apiKey Done and OPENAI_CODEX credential yields OAuthSuccess empty email`() = runTest {
        every { store.loadOutcomes() } returns onApiKeyStepOutcomes(apiKey = StepOutcome.Done)
        every { authStore.has(LLMProvider.OPENAI_CODEX) } returns true
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)

        // VM starts at Demo since ApiKey is Done; navigate back to ApiKey.
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        vm.goBack(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.authMethod).isEqualTo(ApiKeyAuthMethod.OAUTH)
        assertThat(vm.selectedProvider).isEqualTo(OnboardingProvider.OPENAI_API)
        val state = vm.stepState
        assertThat(state).isInstanceOf(ApiKeyStepState.OAuthSuccess::class.java)
        assertThat((state as ApiKeyStepState.OAuthSuccess).email).isEqualTo("")
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `re-entry with apiKey Done and manual credential yields Valid empty key`() = runTest {
        every { store.loadOutcomes() } returns onApiKeyStepOutcomes(apiKey = StepOutcome.Done)
        every { authStore.has(LLMProvider.OPENAI_CODEX) } returns false
        every { authStore.has(LLMProvider.OPENROUTER) } returns true
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.goBack(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.authMethod).isEqualTo(ApiKeyAuthMethod.MANUAL)
        assertThat(vm.selectedProvider).isEqualTo(OnboardingProvider.OPENROUTER)
        val state = vm.stepState
        assertThat(state).isInstanceOf(ApiKeyStepState.Valid::class.java)
        assertThat((state as ApiKeyStepState.Valid).key).isEqualTo("")
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `re-entry with apiKey Done but no credential resets outcome and falls through`() = runTest {
        every { store.loadOutcomes() } returns onApiKeyStepOutcomes(apiKey = StepOutcome.Done)
        every { authStore.has(any()) } returns false
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope); drain(scope, this)
        vm.goBack(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Pending)
        verify { store.saveOutcome(WizardStep.ApiKey, StepOutcome.Pending) }
        // Default selected provider is OPENAI_API → fresh OAuthReady fallthrough.
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.OAuthReady)
        scope.coroutineContext.job.cancel()
    }
}
