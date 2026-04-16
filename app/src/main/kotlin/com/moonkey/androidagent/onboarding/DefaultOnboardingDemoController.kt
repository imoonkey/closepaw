package com.moonkey.androidagent.onboarding

import android.util.Log
import com.moonkey.androidagent.app.AgentService
import com.moonkey.androidagent.app.AppSettingsState
import com.moonkey.androidagent.app.AppSettingsStore
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.perception.PerceptionConfig
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.ApprovalMode
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.protocol.ScreenCaptured
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionLlmConfig
import com.moonkey.androidagent.protocol.TaskCompleted
import com.moonkey.androidagent.session.AgentSession
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
 * Not bound to SessionCoordinator or ChatViewModel.
 */
class DefaultOnboardingDemoController(
    private val settingsState: AppSettingsState,
    private val modelCatalog: ModelCatalog,
    private val scope: CoroutineScope
) : OnboardingDemoController {

    companion object {
        private const val TAG = "OnboardingDemo"
        private const val DEMO_GOAL = "Open the Settings app"
        private const val TIMEOUT_MS = 60_000L
        private const val MAX_TURNS = 5
        private const val SETTINGS_PACKAGE = "com.android.settings"
    }

    private val mutex = Mutex()
    private var demoJob: Job? = null
    private var demoSession: AgentSession? = null

    override fun run(
        onSuccess: (message: String) -> Unit,
        onFailure: (reason: String) -> Unit,
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

            try {
                val config = SessionConfig(
                    maxTurns = MAX_TURNS,
                    approvalMode = ApprovalMode.AUTO_APPROVE,
                    agentMode = AgentMode.BASIC,
                    llm = SessionLlmConfig(
                        backendType = LLMBackendType.OPENAI,
                        localConfig = null
                    ),
                    perceptionConfig = PerceptionConfig.AccessibilityOnly,
                    platformMode = PlatformMode.ACCESSIBILITY,
                    mainModel = settingsState.selectedModel
                )

                val apiKeys = settingsState.buildApiKeys()
                val visualizer = service.getActionVisualizer()
                val touchGate = service.getOverlayTouchGate()

                val session = withContext(Dispatchers.IO) {
                    AgentSession.create(
                        config = config,
                        service = service,
                        scope = scope,
                        apiKeys = apiKeys,
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
                        val isGoalAchieved = completed.reason == CompletionReason.GOAL_ACHIEVED
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
                            val reason = when (completed.reason) {
                                CompletionReason.MAX_TURNS -> "Demo reached maximum attempts"
                                CompletionReason.ERROR -> "Demo encountered an error"
                                CompletionReason.TASK_IMPOSSIBLE -> "Demo could not complete the task"
                                else -> "Demo ended: ${completed.reason}"
                            }
                            Log.w(TAG, "Demo failed: ${completed.reason}")
                            onBringToFront()
                            onFailure(reason)
                        }
                    } else {
                        Log.w(TAG, "Demo timed out after ${TIMEOUT_MS}ms")
                        onBringToFront()
                        onFailure("The demo timed out before opening Settings.")
                    }
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

    override fun cancel() {
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
