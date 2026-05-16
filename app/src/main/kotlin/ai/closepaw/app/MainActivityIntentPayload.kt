package ai.closepaw.app

import android.content.Intent
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode

data class MainActivityIntentPayload(
        val apiKey: String?,
        val openRouterApiKey: String?,
        val novitaApiKey: String?,
        val openaiBaseUrl: String?,
        val backendType: LLMBackendType?,
        val perceptionMode: String?,
        val platformMode: PlatformMode?,
        val mainModel: String?,
        val subagentModel: String?,
        val approvalMode: ApprovalMode?,
        val browserScriptEnabled: Boolean?,
        val goalText: String?,
        val freshSession: Boolean,
        val debugMode: Boolean?,
        val traceEnabled: Boolean?,
        val traceRunId: String?,
        val excludedTools: Set<String>
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

            val openaiBaseUrl =
                    intent.getStringExtra(MainActivity.EXTRA_OPENAI_BASE_URL)?.takeIf {
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

            val subagentModel =
                    intent.getStringExtra(MainActivity.EXTRA_SUBAGENT_MODEL)?.takeIf {
                        it.isNotBlank()
                    }

            val approvalMode =
                    intent.getStringExtra(MainActivity.EXTRA_APPROVAL_MODE)?.let { raw ->
                        try {
                            ApprovalMode.valueOf(raw.uppercase())
                        } catch (_: Exception) {
                            null
                        }
                    }

            val browserScriptEnabled =
                    if (intent.hasExtra(MainActivity.EXTRA_BROWSER_SCRIPT_ENABLED)) {
                        intent.getBooleanExtra(MainActivity.EXTRA_BROWSER_SCRIPT_ENABLED, false)
                    } else {
                        null
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

            val excludedTools =
                    intent.getStringExtra(MainActivity.EXTRA_EXCLUDED_TOOLS)
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?.toSet()
                            ?: emptySet()

            return MainActivityIntentPayload(
                    apiKey = apiKey,
                    openRouterApiKey = openRouterApiKey,
                    novitaApiKey = novitaApiKey,
                    openaiBaseUrl = openaiBaseUrl,
                    backendType = backendType,
                    perceptionMode = perceptionMode,
                    platformMode = platformMode,
                    mainModel = mainModel,
                    subagentModel = subagentModel,
                    approvalMode = approvalMode,
                    browserScriptEnabled = browserScriptEnabled,
                    goalText = goalText,
                    freshSession = intent.getBooleanExtra(MainActivity.EXTRA_FRESH_SESSION, false),
                    debugMode = debugMode,
                    traceEnabled = traceEnabled,
                    traceRunId = traceRunId,
                    excludedTools = excludedTools
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
