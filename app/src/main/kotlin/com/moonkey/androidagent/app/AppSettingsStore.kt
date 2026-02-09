package com.moonkey.androidagent.app

import android.content.Context
import android.os.Environment
import android.util.Log
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType
import java.io.File

data class AppSettings(
    val apiKey: String,
    val selectedModel: String,
    val maxTurns: Int,
    val debugMode: Boolean,
    val perceptionMode: String,
    val agentMode: AgentMode,
    val llmBackend: LLMBackendType,
    val localModelId: String,
    val localModelSlug: String,
    val localModelQuant: String
)

class AppSettingsStore(private val context: Context) {
    companion object {
        private const val TAG = "AppSettingsStore"
        private const val PREFS_NAME = "agent_prefs"

        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_MAX_TURNS = "max_turns"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_SCREENSHOT_INPUT = "screenshot_input"  // kept for migration
        private const val KEY_PERCEPTION_MODE = "perception_mode"
        private const val KEY_AGENT_MODE = "agent_mode"
        private const val KEY_LLM_BACKEND = "llm_backend"
        private const val KEY_LOCAL_MODEL_ID = "local_model_id"
        private const val KEY_LOCAL_MODEL_SLUG = "local_model_slug"
        private const val KEY_LOCAL_MODEL_QUANT = "local_model_quant"

        const val DEFAULT_MODEL = "gpt-5.2"
        // UI default intentionally differs from SessionConfig's default (50).
        const val DEFAULT_MAX_TURNS = 20
        const val DEFAULT_DEBUG_MODE = false
        const val DEFAULT_PERCEPTION_MODE = "accessibility_only"
        val DEFAULT_AGENT_MODE = AgentMode.PRO
        val DEFAULT_LLM_BACKEND = LLMBackendType.OPENAI
        const val DEFAULT_LOCAL_MODEL_ID = "LFM2.5-1.2B-Instruct"
        const val DEFAULT_LOCAL_MODEL_SLUG = "LFM2.5-1.2B-Instruct"
        const val DEFAULT_LOCAL_MODEL_QUANT = "Q4_K_M"
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val prefs = prefs()
        val savedKey = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
        val apiKey = savedKey ?: loadApiKeyFromFile().orEmpty()

        val storedModel = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val selectedModel = normalizeModel(storedModel)
        if (selectedModel != storedModel) {
            prefs.edit().putString(KEY_MODEL, selectedModel).apply()
        }
        val maxTurns = prefs.getInt(KEY_MAX_TURNS, DEFAULT_MAX_TURNS)
        val debugMode = prefs.getBoolean(KEY_DEBUG_MODE, DEFAULT_DEBUG_MODE)
        // Migration: read old boolean, convert to new string
        val perceptionMode = prefs.getString(KEY_PERCEPTION_MODE, null)
            ?: if (prefs.getBoolean(KEY_SCREENSHOT_INPUT, false)) "hybrid" else DEFAULT_PERCEPTION_MODE
        val agentModeName = prefs.getString(KEY_AGENT_MODE, DEFAULT_AGENT_MODE.name)
            ?: DEFAULT_AGENT_MODE.name
        val agentMode = try {
            AgentMode.valueOf(agentModeName)
        } catch (_: Exception) {
            DEFAULT_AGENT_MODE
        }

        val backendName = prefs.getString(KEY_LLM_BACKEND, DEFAULT_LLM_BACKEND.name)
            ?: DEFAULT_LLM_BACKEND.name
        val llmBackend = try {
            LLMBackendType.valueOf(backendName)
        } catch (e: Exception) {
            DEFAULT_LLM_BACKEND
        }

        val localModelId = prefs.getString(KEY_LOCAL_MODEL_ID, DEFAULT_LOCAL_MODEL_ID) ?: DEFAULT_LOCAL_MODEL_ID
        val localModelSlug = prefs.getString(KEY_LOCAL_MODEL_SLUG, DEFAULT_LOCAL_MODEL_SLUG) ?: DEFAULT_LOCAL_MODEL_SLUG
        val localModelQuant = prefs.getString(KEY_LOCAL_MODEL_QUANT, DEFAULT_LOCAL_MODEL_QUANT) ?: DEFAULT_LOCAL_MODEL_QUANT

        return AppSettings(
            apiKey = apiKey,
            selectedModel = selectedModel,
            maxTurns = maxTurns,
            debugMode = debugMode,
            perceptionMode = perceptionMode,
            agentMode = agentMode,
            llmBackend = llmBackend,
            localModelId = localModelId,
            localModelSlug = localModelSlug,
            localModelQuant = localModelQuant
        )
    }

    fun saveApiKey(value: String) {
        prefs().edit().putString(KEY_API_KEY, value).apply()
    }

    fun saveModel(value: String) {
        prefs().edit().putString(KEY_MODEL, value).apply()
    }

    fun saveMaxTurns(value: Int) {
        prefs().edit().putInt(KEY_MAX_TURNS, value).apply()
    }

    fun saveDebugMode(value: Boolean) {
        prefs().edit().putBoolean(KEY_DEBUG_MODE, value).apply()
    }

    fun savePerceptionMode(value: String) {
        prefs().edit().putString(KEY_PERCEPTION_MODE, value).apply()
    }

    fun saveAgentMode(value: AgentMode) {
        prefs().edit().putString(KEY_AGENT_MODE, value.name).apply()
    }

    fun saveBackend(value: LLMBackendType) {
        prefs().edit().putString(KEY_LLM_BACKEND, value.name).apply()
    }

    fun saveLocalModel(id: String, slug: String, quantization: String) {
        prefs().edit()
            .putString(KEY_LOCAL_MODEL_ID, id)
            .putString(KEY_LOCAL_MODEL_SLUG, slug)
            .putString(KEY_LOCAL_MODEL_QUANT, quantization)
            .apply()
    }

    private fun loadApiKeyFromFile(): String? {
        return try {
            @Suppress("DEPRECATION")
            val file = File(Environment.getExternalStorageDirectory(), "api_key.txt")
            if (!file.exists()) return null

            val key = file.readText().trim()
            if (key.isNotBlank() && key.startsWith("sk-")) {
                saveApiKey(key)
                Log.d(TAG, "API key loaded from file")
                key
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load API key from file: ${e.message}")
            null
        }
    }

    private fun normalizeModel(value: String): String {
        return when (value.lowercase()) {
            "gpt-4o", "gpt-4o-mini" -> DEFAULT_MODEL
            else -> value
        }
    }
}
