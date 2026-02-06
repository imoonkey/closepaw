package com.moonkey.androidagent.app

import android.content.Intent
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType

data class MainActivityIntentPayload(
    val apiKey: String?,
    val backendType: LLMBackendType?,
    val agentMode: AgentMode?,
    val goalText: String?,
    val freshSession: Boolean,
    val autoStart: Boolean,
    val screenshotInputEnabled: Boolean?,
    val debugMode: Boolean?,
    val traceEnabled: Boolean?,
    val traceRunId: String?
) {
    companion object {
        fun from(intent: Intent): MainActivityIntentPayload {
            val apiKey = intent.getStringExtra(MainActivity.EXTRA_API_KEY)
                ?.takeIf { it.isNotBlank() }

            val backendType = intent.getStringExtra(MainActivity.EXTRA_LLM_BACKEND)
                ?.lowercase()
                ?.let { backend ->
                    when (backend) {
                        "local" -> LLMBackendType.LOCAL
                        "openai" -> LLMBackendType.OPENAI
                        else -> null
                    }
                }

            val goalText = intent.getStringExtra(MainActivity.EXTRA_GOAL)
                ?.takeIf { it.isNotBlank() }

            val agentMode = intent.getStringExtra(MainActivity.EXTRA_AGENT_MODE)
                ?.let { raw ->
                    try {
                        AgentMode.valueOf(raw.uppercase())
                    } catch (_: Exception) {
                        AgentMode.PRO
                    }
                }

            val screenshotInputEnabled = if (intent.hasExtra(MainActivity.EXTRA_SCREENSHOT_INPUT)) {
                intent.getBooleanExtra(MainActivity.EXTRA_SCREENSHOT_INPUT, false)
            } else {
                null
            }
            val debugMode = if (intent.hasExtra(MainActivity.EXTRA_DEBUG_MODE)) {
                intent.getBooleanExtra(MainActivity.EXTRA_DEBUG_MODE, false)
            } else {
                null
            }

            val traceEnabled = if (intent.hasExtra(MainActivity.EXTRA_TRACE_ENABLED)) {
                intent.getBooleanExtra(MainActivity.EXTRA_TRACE_ENABLED, false)
            } else {
                null
            }

            val traceRunId = intent.getStringExtra(MainActivity.EXTRA_TRACE_RUN_ID)
                ?.takeIf { it.isNotBlank() }

            return MainActivityIntentPayload(
                apiKey = apiKey,
                backendType = backendType,
                agentMode = agentMode,
                goalText = goalText,
                freshSession = intent.getBooleanExtra(MainActivity.EXTRA_FRESH_SESSION, false),
                autoStart = intent.getBooleanExtra(MainActivity.EXTRA_AUTO_START, false),
                screenshotInputEnabled = screenshotInputEnabled,
                debugMode = debugMode,
                traceEnabled = traceEnabled,
                traceRunId = traceRunId
            )
        }
    }
}
