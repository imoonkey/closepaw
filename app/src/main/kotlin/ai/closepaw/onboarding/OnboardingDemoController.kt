package ai.closepaw.onboarding

import android.util.Log
import ai.closepaw.app.AgentService
import ai.closepaw.app.AppSettingsState
import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.auth.MissingCredential
import ai.closepaw.auth.OAuthRefreshFailed
import ai.closepaw.auth.WrongCredentialType
import ai.closepaw.llm.LLMProvider
import ai.closepaw.perception.PerceptionConfig
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.Op
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.ScreenCaptured
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionLlmConfig
import ai.closepaw.protocol.TaskCompleted
import ai.closepaw.session.AgentSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs a throwaway demo session during onboarding.
 *
 * Goal: "Open the Settings app"
 * Success: GOAL_ACHIEVED + last captured package == com.android.settings
 * Timeout: 60 seconds
 *
 * Pulls credentials from the app-scoped [ai.closepaw.auth.AuthStore] — no
 * synthesized bridge, no legacy settings fields.
 */
class OnboardingDemoController(
    private val settingsState: AppSettingsState,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "OnboardingDemo"
        private const val DEMO_GOAL = "Open the Settings app"
        private const val TIMEOUT_MS = 60_000L
        private const val SETTINGS_PACKAGE = "com.android.settings"

        /**
         * Mirrors MainActivity.createOrReloadSession: a non-blank
         * [AppSettingsState.openaiBaseUrl] (set from `.env` / debug-run intent)
         * must override the catalog's OPENAI_API base URL, otherwise the demo
         * session would talk to api.openai.com even though onboarding validated
         * a proxy.
         */
        internal fun resolveBaseUrlOverrides(openaiBaseUrl: String): Map<LLMProvider, String> =
            if (openaiBaseUrl.isNotBlank()) {
                mapOf(LLMProvider.OPENAI_API to openaiBaseUrl)
            } else emptyMap()
    }

    private val mutex = Mutex()
    private var demoJob: Job? = null
    private var demoSession: AgentSession? = null

    fun run(
        onSuccess: (message: String) -> Unit,
        onFailure: (reason: String) -> Unit,
        onCredentialError: (message: String, isOAuth: Boolean) -> Unit,
        onBringToFront: () -> Unit
    ) {
        cancel() // clean up any prior run

        demoJob = scope.launch {
            val service = AgentService.instance
            if (service == null) {
                withContext(Dispatchers.Main) {
                    onFailure("Accessibility service not available")
                }
                return@launch
            }

            val authStore = AuthStoreHolder.get(service.applicationContext)

            try {
                val config = SessionConfig(
                    approvalMode = ApprovalMode.AUTO_APPROVE,
                    llm = SessionLlmConfig(
                        backendType = LLMBackendType.OPENAI,
                        localConfig = null
                    ),
                    perceptionConfig = PerceptionConfig.AccessibilityOnly,
                    platformMode = PlatformMode.ACCESSIBILITY,
                    mainModel = settingsState.selectedModel
                )

                val visualizer = service.getActionVisualizer()
                val touchGate = service.getOverlayTouchGate()

                val baseUrlOverrides = resolveBaseUrlOverrides(settingsState.openaiBaseUrl)

                val session = withContext(Dispatchers.IO) {
                    AgentSession.create(
                        config = config,
                        service = service,
                        scope = service.serviceScope,
                        authStore = authStore,
                        baseUrlOverrides = baseUrlOverrides,
                        visualizer = visualizer,
                        overlayTouchGate = touchGate
                    )
                }
                mutex.withLock { demoSession = session }

                // Register with service for overlay visualization
                service.observeExternalSession(session, PlatformMode.ACCESSIBILITY)

                // Collect events in background
                var lastPackageName: String? = null
                var taskCompleted: TaskCompleted? = null

                val eventJob = scope.launch {
                    session.events.collect { event ->
                        when (event) {
                            is ScreenCaptured -> {
                                lastPackageName = event.packageName
                            }
                            is TaskCompleted -> {
                                taskCompleted = event
                            }
                            else -> {}
                        }
                    }
                }

                // Submit demo goal
                session.submit(Op.UserInput(DEMO_GOAL))
                Log.d(TAG, "Demo goal submitted: $DEMO_GOAL")

                // Wait for completion or timeout
                val completed = withTimeoutOrNull(TIMEOUT_MS) {
                    while (taskCompleted == null) {
                        delay(200)
                    }
                    taskCompleted
                }

                eventJob.cancel()

                // Deliver callbacks on Main dispatcher (they mutate Compose state)
                withContext(Dispatchers.Main) {
                    if (completed != null) {
                        val isGoalAchieved = completed.outcome == TaskOutcome.GOAL_ACHIEVED
                        val isSettingsOpen = lastPackageName == SETTINGS_PACKAGE

                        if (isGoalAchieved && isSettingsOpen) {
                            Log.d(TAG, "Demo succeeded: Settings app opened")
                            onBringToFront()
                            onSuccess("Settings app opened successfully!")
                        } else if (isGoalAchieved) {
                            Log.w(TAG, "Demo goal achieved but package=$lastPackageName")
                            onBringToFront()
                            onSuccess("Demo task completed!")
                        } else {
                            val reason = when (completed.outcome) {
                                TaskOutcome.ERROR -> "Demo encountered an error"
                                TaskOutcome.TASK_IMPOSSIBLE -> "Demo could not complete the task"
                                else -> "Demo ended: ${completed.outcome}"
                            }
                            Log.w(TAG, "Demo failed: ${completed.outcome}")
                            onBringToFront()
                            onFailure(reason)
                        }
                    } else {
                        Log.w(TAG, "Demo timed out after ${TIMEOUT_MS}ms")
                        onBringToFront()
                        onFailure("The demo timed out before opening Settings.")
                    }
                }
            } catch (e: MissingCredential) {
                Log.w(TAG, "Demo credential missing for ${e.provider}")
                withContext(Dispatchers.Main) {
                    onCredentialError(
                        "No ${e.provider.displayName()} credential found. Sign in again.",
                        e.provider == LLMProvider.OPENAI_CODEX,
                    )
                }
            } catch (e: OAuthRefreshFailed) {
                Log.w(TAG, "Demo OAuth refresh failed for ${e.provider}: ${e.message}")
                withContext(Dispatchers.Main) {
                    onCredentialError(
                        "Sign-in expired. Please sign in again.",
                        true,
                    )
                }
            } catch (e: WrongCredentialType) {
                Log.w(TAG, "Demo wrong credential type for ${e.provider}: expected ${e.expected}")
                withContext(Dispatchers.Main) {
                    onCredentialError(
                        "Credential mismatch for ${e.provider.displayName()}. Re-enter it.",
                        e.provider == LLMProvider.OPENAI_CODEX,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Demo failed with exception", e)
                withContext(Dispatchers.Main) {
                    onFailure("Demo failed: ${e.message}")
                }
            } finally {
                shutdownSession()
            }
        }
    }

    fun cancel() {
        demoJob?.cancel()
        demoJob = null
        scope.launch { shutdownSession() }
    }

    private suspend fun shutdownSession() {
        val session = mutex.withLock {
            val s = demoSession
            demoSession = null
            s
        }
        try {
            session?.submit(Op.Shutdown)
        } catch (e: Exception) {
            Log.w(TAG, "Demo session shutdown error: ${e.message}")
        }
    }
}

private fun LLMProvider.displayName(): String = when (this) {
    LLMProvider.OPENAI_API -> "OpenAI"
    LLMProvider.OPENAI_CODEX -> "OpenAI"
    LLMProvider.OPENROUTER -> "OpenRouter"
    LLMProvider.OTHER -> "Other"
    LLMProvider.LOCAL_LFM -> "Local"
}
