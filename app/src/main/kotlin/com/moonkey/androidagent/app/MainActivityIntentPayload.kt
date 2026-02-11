package com.moonkey.androidagent.app

import android.content.Intent
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.PlatformMode

data class MainActivityIntentPayload(
        val apiKey: String?,
        val openRouterApiKey: String?,
        val novitaApiKey: String?,
        val backendType: LLMBackendType?,
        val agentMode: AgentMode?,
        val perceptionMode: String?,
        val platformMode: PlatformMode?,
        val mainModel: String?,
        val executorModel: String?,
        val goalText: String?,
        val freshSession: Boolean,
        val autoStart: Boolean,
        val debugMode: Boolean?,
        val traceEnabled: Boolean?,
        val traceRunId: String?
) {
    companion object {
        fun from(intent: Intent): MainActivityIntentPayload {
            val apiKey =
                    intent.getStringExtra(MainActivity.EXTRA_API_KEY)?.takeIf { it.isNotBlank() }

            val openRouterApiKey =
                    intent.getStringExtra(MainActivity.EXTRA_OPENROUTER_API_KEY)?.takeIf {
                        it.isNotBlank()
                    }

            val novitaApiKey =
                    intent.getStringExtra(MainActivity.EXTRA_NOVITA_API_KEY)?.takeIf {
                        it.isNotBlank()
                    }

            val backendType =
                    intent.getStringExtra(MainActivity.EXTRA_LLM_BACKEND)?.lowercase()?.let {
                            backend ->
                        when (backend) {
                            "local" -> LLMBackendType.LOCAL
                            "openai" -> LLMBackendType.OPENAI
                            else -> null
                        }
                    }

            val goalText =
                    intent.getStringExtra(MainActivity.EXTRA_GOAL)?.takeIf { it.isNotBlank() }

            val agentMode =
                    intent.getStringExtra(MainActivity.EXTRA_AGENT_MODE)?.let { raw ->
                        try {
                            AgentMode.valueOf(raw.uppercase())
                        } catch (_: Exception) {
                            AgentMode.PRO
                        }
                    }

            val perceptionMode =
                    normalizePerceptionMode(
                            intent.getStringExtra(MainActivity.EXTRA_PERCEPTION_MODE)
                    )

            val platformMode =
                    intent.getStringExtra(MainActivity.EXTRA_PLATFORM_MODE)?.let { raw ->
                        try {
                            PlatformMode.valueOf(raw.uppercase())
                        } catch (_: Exception) {
                            null
                        }
                    }

            val mainModel =
                    intent.getStringExtra(MainActivity.EXTRA_MAIN_MODEL)?.takeIf { it.isNotBlank() }

            val executorModel =
                    intent.getStringExtra(MainActivity.EXTRA_EXECUTOR_MODEL)?.takeIf {
                        it.isNotBlank()
                    }

            val debugMode =
                    if (intent.hasExtra(MainActivity.EXTRA_DEBUG_MODE)) {
                        intent.getBooleanExtra(MainActivity.EXTRA_DEBUG_MODE, false)
                    } else {
                        null
                    }

            val traceEnabled =
                    if (intent.hasExtra(MainActivity.EXTRA_TRACE_ENABLED)) {
                        intent.getBooleanExtra(MainActivity.EXTRA_TRACE_ENABLED, false)
                    } else {
                        null
                    }

            val traceRunId =
                    intent.getStringExtra(MainActivity.EXTRA_TRACE_RUN_ID)?.takeIf {
                        it.isNotBlank()
                    }

            return MainActivityIntentPayload(
                    apiKey = apiKey,
                    openRouterApiKey = openRouterApiKey,
                    novitaApiKey = novitaApiKey,
                    backendType = backendType,
                    agentMode = agentMode,
                    perceptionMode = perceptionMode,
                    platformMode = platformMode,
                    mainModel = mainModel,
                    executorModel = executorModel,
                    goalText = goalText,
                    freshSession = intent.getBooleanExtra(MainActivity.EXTRA_FRESH_SESSION, false),
                    autoStart = intent.getBooleanExtra(MainActivity.EXTRA_AUTO_START, false),
                    debugMode = debugMode,
                    traceEnabled = traceEnabled,
                    traceRunId = traceRunId
            )
        }

        private fun normalizePerceptionMode(raw: String?): String? {
            return when (raw?.trim()?.lowercase()) {
                "accessibility_only",
                "accessibility-only",
                "accessibility",
                "a11y_only",
                "a11y-only",
                "a11y" -> "accessibility_only"
                "hybrid" -> "hybrid"
                "screenshot_only", "screenshot-only", "screenshot" -> "screenshot_only"
                else -> null
            }
        }
    }
}
