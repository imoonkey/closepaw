package ai.closepaw.onboarding

import ai.closepaw.app.AppSettingsState
import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.ModelCatalog
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Characterization tests for the PermissionStepState FSM described in
 * doc/main/state_machines/onboarding_permission_step.md.
 *
 * The state itself is a `sealed interface` with `data object` variants only —
 * every transition / guard lives in OnboardingViewModel.checkCurrentPermission
 * (and openSystemSettings / onHostResumed / skipStep). Tests drive the VM and
 * observe `vm.stepState` / `vm.currentStep` / outcome writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionStepStateTest {

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

    /** Drain pending launches on the scope (mirrors OnboardingViewModelTest.drain). */
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

    // ── Checking → Ready (live false, !isReturnFromSettings) ──

    @Test
    fun `fresh entry with permission missing lands on Ready`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Ready)

        scope.coroutineContext.job.cancel()
    }

    // ── Checking → advance (autoAdvance=true, live true) ──

    @Test
    fun `fresh entry with permission satisfied auto-advances and persists Done`() = runTest {
        // All permissions live=true; outcomes default Pending.
        // firstIncompleteStep (live override) skips Accessibility/Overlay (live true) and
        // returns Battery (because outcomes.battery == Pending). Battery's live check is
        // true → autoAdvance path runs synchronously → advances to ApiKey.
        allPermissionsGranted()

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        verify { store.saveOutcome(WizardStep.Battery, StepOutcome.Done) }
        assertThat(vm.outcomes.battery).isEqualTo(StepOutcome.Done)

        scope.coroutineContext.job.cancel()
    }

    // ── Checking → Satisfied (autoAdvance=false via goBack) ──

    @Test
    fun `goBack to satisfied permission step lands on Satisfied without advancing`() = runTest {
        // Start past Battery on ApiKey, with all permissions granted so goBack to Battery
        // re-derives Satisfied.
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Done,
            apiKey = StepOutcome.Pending
        )

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)

        vm.goBack()
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Satisfied)

        scope.coroutineContext.job.cancel()
    }

    // ── Checking → Unsatisfied (Overlay, isReturnFromSettings, live false) ──

    @Test
    fun `onHostResumed on Overlay with permission still missing transitions to Unsatisfied`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        // overlay still false

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Ready)

        vm.onHostResumed()
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Unsatisfied)

        scope.coroutineContext.job.cancel()
    }

    // ── Checking → Unsatisfied (Battery, isReturnFromSettings, live false) ──

    @Test
    fun `onHostResumed on Battery with optimization not ignored transitions to Unsatisfied`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true
        // battery still false

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Ready)

        vm.onHostResumed()
        drain(scope, this)

        assertThat(vm.stepState).isEqualTo(PermissionStepState.Unsatisfied)

        scope.coroutineContext.job.cancel()
    }

    // ── Checking → Satisfied via a11y poll (Accessibility, isReturnFromSettings) ──

    @Test
    fun `accessibility poll succeeds within 3s and advances to Overlay`() = runTest {
        // Initially missing. After onHostResumed, polling should pick up the change.
        var calls = 0
        every { permissionMonitor.isAccessibilityEnabled() } answers {
            // Become true on the 3rd call (after 2 poll iterations)
            calls++ >= 3
        }

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Ready)

        vm.onHostResumed()
        drain(scope, this)

        // Poll succeeded → onPermissionSatisfied → advance to Overlay
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        verify { store.saveOutcome(WizardStep.Accessibility, StepOutcome.Done) }

        scope.coroutineContext.job.cancel()
    }

    // ── Checking → Unsatisfied via a11y poll exhaustion ──

    @Test
    fun `accessibility poll exhausts 3s without success and lands on Unsatisfied`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)

        vm.onHostResumed()
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Unsatisfied)
        verify(exactly = 0) { store.saveOutcome(WizardStep.Accessibility, StepOutcome.Done) }

        scope.coroutineContext.job.cancel()
    }

    // ── Ready → OpeningSettings (and emits effect) ──

    @Test
    fun `openSystemSettings on Accessibility transitions Ready to OpeningSettings`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Ready)

        vm.openSystemSettings()

        assertThat(vm.stepState).isEqualTo(PermissionStepState.OpeningSettings)

        scope.coroutineContext.job.cancel()
    }

    // ── Unsatisfied → OpeningSettings ──

    @Test
    fun `openSystemSettings on Overlay Unsatisfied transitions to OpeningSettings`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        vm.onHostResumed()
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Unsatisfied)

        vm.openSystemSettings()

        assertThat(vm.stepState).isEqualTo(PermissionStepState.OpeningSettings)

        scope.coroutineContext.job.cancel()
    }

    // ── OpeningSettings → Checking (re-enters via onHostResumed) ──

    @Test
    fun `onHostResumed from OpeningSettings re-enters Checking and resolves to Ready`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)

        vm.openSystemSettings()
        assertThat(vm.stepState).isEqualTo(PermissionStepState.OpeningSettings)

        // Permission still missing on resume → for Accessibility, poll path runs and exhausts.
        vm.onHostResumed()
        drain(scope, this)

        // Final settled state on Accessibility w/ isReturnFromSettings=true and missing perm
        // is Unsatisfied (poll exhaust), not Ready. The Checking transition is implicit
        // (set first inside checkCurrentPermission).
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Unsatisfied)

        scope.coroutineContext.job.cancel()
    }

    // ── Battery Ready → Skipped via skipStep ──

    @Test
    fun `skipStep on Battery persists Skipped and advances`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true
        every { permissionMonitor.isOverlayEnabled() } returns true

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Battery)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Ready)

        vm.skipStep()
        drain(scope, this)

        verify { store.saveOutcome(WizardStep.Battery, StepOutcome.Skipped) }
        assertThat(vm.outcomes.battery).isEqualTo(StepOutcome.Skipped)
        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)

        scope.coroutineContext.job.cancel()
    }

    // ── Guard: skipStep no-ops on Accessibility ──

    @Test
    fun `skipStep on Accessibility is a no-op (no Skipped outcome no advance)`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)

        vm.skipStep()
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Ready)
        verify(exactly = 0) { store.saveOutcome(WizardStep.Accessibility, StepOutcome.Skipped) }

        scope.coroutineContext.job.cancel()
    }

    // ── Guard: skipStep no-ops on Overlay ──

    @Test
    fun `skipStep on Overlay is a no-op`() = runTest {
        every { permissionMonitor.isAccessibilityEnabled() } returns true

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)

        vm.skipStep()
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Overlay)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Ready)
        verify(exactly = 0) { store.saveOutcome(WizardStep.Overlay, StepOutcome.Skipped) }

        scope.coroutineContext.job.cancel()
    }

    // ── Guard: onHostResumed outside permission family is a no-op ──

    @Test
    fun `onHostResumed on ApiKey step does not touch permission state`() = runTest {
        allPermissionsGranted()
        every { store.loadOutcomes() } returns StepOutcomes(
            accessibility = StepOutcome.Done,
            overlay = StepOutcome.Done,
            battery = StepOutcome.Skipped,
            apiKey = StepOutcome.Pending
        )

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)
        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        val before = vm.stepState

        vm.onHostResumed()
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.ApiKey)
        assertThat(vm.stepState).isEqualTo(before)

        scope.coroutineContext.job.cancel()
    }

    // ── Invariant: a11y poll only on isReturnFromSettings (fresh entry never polls) ──

    @Test
    fun `fresh entry with accessibility missing does not poll - stays on Ready immediately`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val vm = makeVm(scope)
        drain(scope, this)

        // Even after advancing the scheduler past the 3s poll budget, state stays Ready
        // because polling only runs when isReturnFromSettings=true.
        testScheduler.advanceTimeBy(5_000)
        drain(scope, this)

        assertThat(vm.currentStep).isEqualTo(WizardStep.Accessibility)
        assertThat(vm.stepState).isEqualTo(PermissionStepState.Ready)

        scope.coroutineContext.job.cancel()
    }
}
