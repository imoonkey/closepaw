package com.moonkey.androidagent.app

import android.content.Intent
import com.moonkey.androidagent.protocol.LLMBackendType

data class MainActivityIntentPayload(
    val apiKey: String?,
    val backendType: LLMBackendType?,
    val goalText: String?,
    val freshSession: Boolean,
    val autoStart: Boolean,
    val screenshotInputEnabled: Boolean?
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

            val screenshotInputEnabled = if (intent.hasExtra(MainActivity.EXTRA_SCREENSHOT_INPUT)) {
                intent.getBooleanExtra(MainActivity.EXTRA_SCREENSHOT_INPUT, false)
            } else {
                null
            }

            return MainActivityIntentPayload(
                apiKey = apiKey,
                backendType = backendType,
                goalText = goalText,
                freshSession = intent.getBooleanExtra(MainActivity.EXTRA_FRESH_SESSION, false),
                autoStart = intent.getBooleanExtra(MainActivity.EXTRA_AUTO_START, false),
                screenshotInputEnabled = screenshotInputEnabled
            )
        }
    }
}
