package ai.closepaw.onboarding

import com.google.common.truth.Truth.assertThat
import ai.closepaw.app.AppSettingsState
import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.OAuthTokens
import ai.closepaw.auth.OpenAiSignInResult
import ai.closepaw.auth.openAiSignIn
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
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

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

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
              "oai-resp":{"display_name":"OAI","provider":"OPENAI_API","api":"response","model_id":"gpt-test"},
              "oai-chat":{"display_name":"OAI-Chat","provider":"OPENAI_API","api":"chat","model_id":"gpt-chat","base_url":"$baseUrl"},
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

        every { store.loadOutcomes() } returns StepOutcomes()
        every { authStore.has(any()) } returns false
        every { permissionMonitor.isAccessibilityEnabled() } returns false
        every { permissionMonitor.isOverlayEnabled() } returns false
        every { permissionMonitor.isBatteryOptimized() } returns false
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) { /* ignore */ }
        unmockkAll()
    }

    private fun makeVm(scope: CoroutineScope) = OnboardingViewModel(
        store = store,
        settingsState = settingsState,
        modelCatalog = catalog,
        permissionMonitor = permissionMonitor,
        authStore = authStore,
        demoController = demoController,
        scope = scope
    )

    /** Drain any pending launches + real IO completions on the scope. */
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

    private fun allPermissionsGranted() {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        every { permissionMonitor.isBatteryOptimized() } returns true
    }

    @Test
    fun `startup derives ApiKey as first step from stored outcomes + satisfied permissions`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Skipped,
            apiKey = StepOutcome.Pending,
            demo = StepOutcome.Pending
        )

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.stepState).isInstanceOf(ApiKeyStepState.OAuthReady::class.java)

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `onHostResumed re-checks accessibility and advances when permission now granted`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        assertThat(vm.stepState).isInstanceOf(PermissionStepState.Ready::class.java)

        every { permissionMonitor.isAccessibilityEnabled() } returns true
        vm.onHostResumed()
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        verify { store.saveOutcome(WizardStep.Accessibility, StepOutcome.Done) }

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `OAuth success writes AuthStore and advances past ApiKey step`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Skipped
        )
        mockkStatic("ai.closepaw.auth.OpenAiSignInKt")
        val tokens = OAuthTokens(
            accessToken = "acc-123",
            refreshToken = "ref",
            expiresAt = 0L,
            email = "user@example.com"
        )
        coEvery { openAiSignIn(any()) } returns OpenAiSignInResult.Success(tokens)

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)

        vm.startOAuth()
        drain(scope, this)

        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Done)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        coVerify {
            authStore.set(
                LLMProvider.OPENAI_CODEX,
                match<AuthCredential.OAuth> { it.accessToken == "acc-123" }
            )
        }
        verify { settingsState.updateModel("oai-codex") }

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `OAuth error keeps user on ApiKey step with OAuthError state`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Skipped
        )
        mockkStatic("ai.closepaw.auth.OpenAiSignInKt")
        coEvery { openAiSignIn(any()) } returns OpenAiSignInResult.Error("browser closed")

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)

        vm.startOAuth()
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        val state = vm.stepState
        assertThat(state).isInstanceOf(ApiKeyStepState.OAuthError::class.java)
        assertThat((state as ApiKeyStepState.OAuthError).message).isEqualTo("browser closed")
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Pending)

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `manual API key validation success writes AuthStore and advances`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Skipped
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)

        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-good")
        vm.validateApiKey()
        drain(scope, this)

        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Done)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        coVerify {
            authStore.set(LLMProvider.OPENROUTER, AuthCredential.ApiKey("sk-good"))
        }
        verify { settingsState.updateModel("or-chat") }

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `manual API key 401 leaves user on ApiKey with Invalid state`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Skipped
        )
        server.enqueue(MockResponse().setResponseCode(401))

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)

        vm.selectProvider(OnboardingProvider.OPENROUTER)
        vm.onApiKeyChanged("sk-bad")
        vm.validateApiKey()
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.stepState).isInstanceOf(ApiKeyStepState.Invalid::class.java)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Pending)

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `OPENAI_API validator honors AppSettingsState openaiBaseUrl override (debug proxy)`() = runTest {
        // Catalog 'oai-resp' has no base_url, so absent override the validator would hit
        // api.openai.com and never reach our MockWebServer. With override set, the request
        // must land on the server — otherwise enqueue() goes unconsumed and the validator
        // sees a TransientError (network failure to api.openai.com from JVM tests).
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Skipped
        )
        every { settingsState.openaiBaseUrl } returns server.url("/v1").toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)

        vm.selectProvider(OnboardingProvider.OPENAI_API)
        vm.selectAuthMethod(ApiKeyAuthMethod.MANUAL)
        vm.onApiKeyChanged("sk-debug")
        vm.validateApiKey()
        drain(scope, this)

        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Done)
        assertThat(server.requestCount).isEqualTo(1)
        coVerify {
            authStore.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-debug"))
        }

        scope.coroutineContext.job.cancel()
    }
}
