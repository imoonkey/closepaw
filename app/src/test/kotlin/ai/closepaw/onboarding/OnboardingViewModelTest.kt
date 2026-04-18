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
        coEvery { openAiSignIn(any(), any()) } returns OpenAiSignInResult.Success(tokens)

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
        coEvery { openAiSignIn(any(), any()) } returns OpenAiSignInResult.Error("browser closed")

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

    // ─────────────────────────────────────────────────────────────────────────
    // Characterization: WizardStep funnel + StepOutcome rollup
    // Spec: doc/main/state_machines/onboarding_wizard.md
    // ─────────────────────────────────────────────────────────────────────────

    private fun newScope(testScope: TestScope) =
        CoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler) + Job())

    private val allDoneExceptDemo = StepOutcomes(
        accessibility = StepOutcome.Done,
        overlay = StepOutcome.Done,
        battery = StepOutcome.Skipped,
        apiKey = StepOutcome.Done,
        demo = StepOutcome.Pending,
    )

    private val allComplete = StepOutcomes(
        accessibility = StepOutcome.Done,
        overlay = StepOutcome.Done,
        battery = StepOutcome.Skipped,
        apiKey = StepOutcome.Done,
        demo = StepOutcome.Done,
    )

    // ── firstIncompleteStep — initial step selection (8 rules) ──

    @Test
    fun `firstIncompleteStep — no permissions → Accessibility`() = runTest {
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — accessibility only → Overlay`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — battery pending and gate unsatisfied → Battery`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        // battery Pending + !isBatteryOptimized → rule 3
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — battery pending despite gate satisfied → Battery (rule 5)`() = runTest {
        // acc+overlay+batteryOpt all true; outcomes.battery still Pending → rule 5 forces Battery
        allPermissionsGranted()
        // Default outcomes = all Pending. Rule 3 fails (gate satisfied). Rule 5 fires.
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        // After enterStep(Battery): permission satisfied + autoAdvance=true → onPermissionSatisfied
        // → advances to ApiKey. So currentStep ends at ApiKey, but we can verify Battery was
        // visited by checking outcomes.battery flipped to Done.
        assertThat(vm.outcomes.battery).isEqualTo(StepOutcome.Done)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — battery Done apiKey Pending → ApiKey (rule 4)`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Done,
            apiKey = StepOutcome.Pending,
            demo = StepOutcome.Pending,
        )
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — battery Skipped apiKey Pending → ApiKey (rule 4)`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Skipped,
            apiKey = StepOutcome.Pending,
            demo = StepOutcome.Pending,
        )
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — apiKey Done demo Pending → Demo (rule 7)`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        assertThat(vm.stepState).isInstanceOf(DemoStepState.Ready::class.java)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — all done → Complete (rule 8)`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allComplete
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — stored Done but live a11y revoked routes back to Accessibility`() = runTest {
        // Hard-gate re-validation: acc Done in store, but isAccessibilityEnabled=false.
        every { store.loadOutcomes() } returns allComplete
        every { permissionMonitor.isOverlayEnabled() } returns true
        every { permissionMonitor.isBatteryOptimized() } returns true
        // isAccessibilityEnabled stays false (default).
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `firstIncompleteStep — stored Done but overlay revoked routes back to Overlay`() = runTest {
        every { store.loadOutcomes() } returns allComplete
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isBatteryOptimized() } returns true
        // overlay still false
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        scope.coroutineContext.job.cancel()
    }

    // ── Forward transitions ──

    @Test
    fun `Overlay → Battery on permission satisfied`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)

        every { permissionMonitor.isOverlayEnabled() } returns true
        // Battery gate unsatisfied so we stop on Battery (don't cascade).
        vm.onHostResumed(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)
        assertThat(vm.outcomes.overlay).isEqualTo(StepOutcome.Done)
        verify { store.saveOutcome(WizardStep.Overlay, StepOutcome.Done) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Battery → ApiKey on permission satisfied`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)

        every { permissionMonitor.isBatteryOptimized() } returns true
        vm.onHostResumed(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.outcomes.battery).isEqualTo(StepOutcome.Done)
        verify { store.saveOutcome(WizardStep.Battery, StepOutcome.Done) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Battery → ApiKey on skip persists Skipped`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)

        vm.skipStep(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.outcomes.battery).isEqualTo(StepOutcome.Skipped)
        verify { store.saveOutcome(WizardStep.Battery, StepOutcome.Skipped) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Demo → Complete on demo success`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        every { demoController.run(any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (firstArg() as (String) -> Unit).invoke("yay")
        }
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)

        vm.startDemo(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)
        assertThat(vm.outcomes.demo).isEqualTo(StepOutcome.Done)
        verify { store.saveOutcome(WizardStep.Demo, StepOutcome.Done) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Demo → Complete on skip`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)

        vm.skipStep(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)
        assertThat(vm.outcomes.demo).isEqualTo(StepOutcome.Skipped)
        verify { store.saveOutcome(WizardStep.Demo, StepOutcome.Skipped) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Complete → Complete is a self-loop on continueForward`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allComplete
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)

        vm.continueForward(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Demo failure leaves user on Demo with Failure state`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        every { demoController.run(any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (secondArg() as (String) -> Unit).invoke("boom")
        }
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        vm.startDemo(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        assertThat(vm.stepState).isInstanceOf(DemoStepState.Failure::class.java)
        assertThat(vm.outcomes.demo).isEqualTo(StepOutcome.Pending)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Demo credential error leaves user on Demo with CredentialError state`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        every { demoController.run(any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (thirdArg() as (String, Boolean) -> Unit).invoke("expired", true)
        }
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        vm.startDemo(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        val state = vm.stepState
        assertThat(state).isInstanceOf(DemoStepState.CredentialError::class.java)
        assertThat((state as DemoStepState.CredentialError).isOAuth).isTrue()
        scope.coroutineContext.job.cancel()
    }

    // ── goBack — backward transitions ──

    @Test
    fun `goBack from Accessibility is no-op`() = runTest {
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)

        vm.goBack(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `goBack Overlay → Accessibility`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)

        vm.goBack(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        // Acc satisfied + autoAdvance=false → Satisfied, NOT advanced
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Satisfied)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `goBack Battery → Overlay (no auto-advance even when satisfied)`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)

        vm.goBack(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Satisfied)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `goBack ApiKey → Battery`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Done,
            apiKey = StepOutcome.Pending,
            demo = StepOutcome.Pending,
        )
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)

        vm.goBack(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `goBack Demo → ApiKey`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        every { authStore.has(LLMProvider.OPENAI_CODEX) } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)

        vm.goBack(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Done)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `goBack Complete → Demo`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allComplete
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)

        vm.goBack(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        assertThat(vm.stepState).isInstanceOf(DemoStepState.Ready::class.java)
        scope.coroutineContext.job.cancel()
    }

    // ── skipStep guard rejections (only Battery + Demo allowed) ──

    @Test
    fun `skipStep on Accessibility is no-op`() = runTest {
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        vm.skipStep(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        verify(exactly = 0) { store.saveOutcome(WizardStep.Accessibility, any()) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `skipStep on Overlay is no-op`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        vm.skipStep(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        verify(exactly = 0) { store.saveOutcome(WizardStep.Overlay, any()) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `skipStep on ApiKey is no-op`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Done,
            apiKey = StepOutcome.Pending,
        )
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        vm.skipStep(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        verify(exactly = 0) { store.saveOutcome(WizardStep.ApiKey, StepOutcome.Skipped) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `skipStep on Complete is no-op`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allComplete
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)

        vm.skipStep(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)
        verify(exactly = 0) { store.saveOutcome(WizardStep.Complete, any()) }
        // Per spec, only Battery + Demo accept Skipped — Complete never persists outcome at all.
        scope.coroutineContext.job.cancel()
    }

    // ── ApiKey recovery: stored Done but AuthStore lost the credential ──

    @Test
    fun `enterStep ApiKey resets to Pending and shows editable state when AuthStore has no matching credential`() = runTest {
        // Spec: doc/main/state_machines/onboarding_wizard.md L125-128 + OnboardingViewModel.kt:344-355
        // Land on Demo (apiKey=Done), then goBack to ApiKey. AuthStore.has(*) all false (setUp default).
        // VM should rewrite apiKey outcome to Pending and present the default editable state.
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        // authStore.has(any()) returns false from setUp — no credential anywhere.
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Done)

        vm.goBack(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Pending)
        verify { store.saveOutcome(WizardStep.ApiKey, StepOutcome.Pending) }
        // Default selectedProvider is OPENAI_API → editable state is OAuthReady
        assertThat(vm.selectedProvider).isEqualTo(OnboardingProvider.OPENAI_API)
        assertThat(vm.authMethod).isEqualTo(ApiKeyAuthMethod.OAUTH)
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.OAuthReady)
        scope.coroutineContext.job.cancel()
    }

    // ── Special transitions ──

    @Test
    fun `goToAuthStep resets apiKey + demo to Pending and jumps to ApiKey`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)

        vm.goToAuthStep(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Pending)
        assertThat(vm.outcomes.demo).isEqualTo(StepOutcome.Pending)
        verify { store.saveOutcome(WizardStep.ApiKey, StepOutcome.Pending) }
        verify { store.saveOutcome(WizardStep.Demo, StepOutcome.Pending) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `startDemo preflight a11y revoked resets accessibility and jumps back`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)

        every { permissionMonitor.isAccessibilityEnabled() } returns false
        vm.startDemo(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        assertThat(vm.outcomes.accessibility).isEqualTo(StepOutcome.Pending)
        verify { store.saveOutcome(WizardStep.Accessibility, StepOutcome.Pending) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `startDemo preflight overlay revoked resets overlay and jumps back`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)

        every { permissionMonitor.isOverlayEnabled() } returns false
        vm.startDemo(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        assertThat(vm.outcomes.overlay).isEqualTo(StepOutcome.Pending)
        verify { store.saveOutcome(WizardStep.Overlay, StepOutcome.Pending) }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `startDemo preflight apiKey not Done jumps to ApiKey without reset`() = runTest {
        // Construct corrupt-state: outcomes land us on Demo per firstIncompleteStep rule 7
        // (apiKey not Pending → not selected by rules 4/6; demo Pending → rule 7 → Demo)
        // even though apiKey != Done. Then startDemo preflight should kick to ApiKey
        // without rewriting any outcome.
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Done,
            apiKey = StepOutcome.Skipped, // not Done, not Pending → reaches Demo
            demo = StepOutcome.Pending,
        )
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)

        vm.startDemo(); drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        // Outcome unchanged (no reset path for this branch)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Skipped)
        verify(exactly = 0) { store.saveOutcome(WizardStep.ApiKey, StepOutcome.Pending) }
        scope.coroutineContext.job.cancel()
    }

    // ── Guard early-returns (currentStep mismatch) ──

    @Test
    fun `selectProvider is no-op when not on ApiKey`() = runTest {
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        val before = vm.selectedProvider

        vm.selectProvider(OnboardingProvider.OPENROUTER)
        assertThat(vm.selectedProvider).isEqualTo(before)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `selectAuthMethod is no-op when not on ApiKey`() = runTest {
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        val before = vm.authMethod

        vm.selectAuthMethod(ApiKeyAuthMethod.MANUAL)
        assertThat(vm.authMethod).isEqualTo(before)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `onApiKeyChanged is no-op when not on ApiKey`() = runTest {
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        val before = vm.stepState

        vm.onApiKeyChanged("sk-leak")
        assertThat(vm.stepState).isEqualTo(before)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `validateApiKey is no-op when stepState is Empty`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Skipped,
        )
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        vm.selectProvider(OnboardingProvider.OPENROUTER) // → Empty
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.Empty)

        vm.validateApiKey(); drain(scope, this)
        // No transition into Validating, no outcome change.
        assertThat(vm.stepState).isEqualTo(ApiKeyStepState.Empty)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Pending)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `startDemo is no-op when not on Demo step`() = runTest {
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)

        vm.startDemo(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        verify(exactly = 0) { demoController.run(any(), any(), any(), any()) }
        scope.coroutineContext.job.cancel()
    }

    // ── Invariants ──

    @Test
    fun `finish persists completion via store setCompleted`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allComplete
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)

        vm.finish()
        verify { store.setCompleted() }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `Complete is never persisted as a StepOutcome`() = runTest {
        // Drive a full happy path and assert saveOutcome(Complete, *) never fires.
        allPermissionsGranted()
        every { store.loadOutcomes() } returns allDoneExceptDemo
        every { demoController.run(any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (firstArg() as (String) -> Unit).invoke("ok")
        }
        val scope = newScope(this)
        val vm = makeVm(scope); drain(scope, this)
        vm.startDemo(); drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Complete)

        verify(exactly = 0) { store.saveOutcome(WizardStep.Complete, any()) }
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

    @Test
    fun `back to ApiKey with Done outcome but cleared credential resets to Pending`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Skipped,
            apiKey = StepOutcome.Done,
            demo = StepOutcome.Pending,
        )
        every { authStore.has(any()) } returns false

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Demo)

        vm.goBack()
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.outcomes.apiKey).isEqualTo(StepOutcome.Pending)
        verify { store.saveOutcome(WizardStep.ApiKey, StepOutcome.Pending) }
        assertThat(vm.stepState).isInstanceOf(ApiKeyStepState.OAuthReady::class.java)

        scope.coroutineContext.job.cancel()
    }
}
