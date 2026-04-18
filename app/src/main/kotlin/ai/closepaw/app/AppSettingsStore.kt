package ai.closepaw.app

import android.content.Context
import ai.closepaw.protocol.AgentMode
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.settings.AVAILABLE_LOCAL_MODELS
import ai.closepaw.ui.settings.LocalModelOption

data class AppSettings(
        val selectedModel: String,
        val maxTurns: Int,
        val debugMode: Boolean,
        val perceptionMode: String,
        val agentMode: AgentMode,
        val llmBackend: LLMBackendType,
        val localModel: LocalModelOption,
        val executorModel: String?,
        val platformMode: PlatformMode,
        val traceEnabled: Boolean
)

class AppSettingsStore(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "agent_prefs"

        private const val KEY_MODEL = "model"
        private const val KEY_MAX_TURNS = "max_turns"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_SCREENSHOT_INPUT = "screenshot_input"
        private const val KEY_PERCEPTION_MODE = "perception_mode"
        private const val KEY_AGENT_MODE = "agent_mode"
        private const val KEY_LLM_BACKEND = "llm_backend"
        private const val KEY_LOCAL_MODEL_ID = "local_model_id"
        private const val KEY_EXECUTOR_MODEL = "executor_model"
        private const val KEY_PLATFORM_MODE = "platform_mode"
        private const val KEY_USER_ALLOWED_PACKAGES = "user_allowed_packages"
        private const val KEY_TRACE_ENABLED = "trace_enabled"

        const val DEFAULT_MODEL = "glm-5"
        const val DEFAULT_MAX_TURNS = 20
        const val DEFAULT_DEBUG_MODE = false
        const val DEFAULT_PERCEPTION_MODE = "accessibility_only"
        val DEFAULT_AGENT_MODE = AgentMode.PRO
        val DEFAULT_LLM_BACKEND = LLMBackendType.OPENAI
        val DEFAULT_LOCAL_MODEL: LocalModelOption = AVAILABLE_LOCAL_MODELS.first()
        val DEFAULT_PLATFORM_MODE = PlatformMode.ACCESSIBILITY
        const val DEFAULT_TRACE_ENABLED = false
    }

    /** Plain prefs for non-secret settings only (model, turns, mode, etc.). */
    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val prefs = prefs()

        val selectedModel = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val maxTurns = prefs.getInt(KEY_MAX_TURNS, DEFAULT_MAX_TURNS)
        val debugMode = prefs.getBoolean(KEY_DEBUG_MODE, DEFAULT_DEBUG_MODE)
        val perceptionMode =
                prefs.getString(KEY_PERCEPTION_MODE, null)
                        ?: if (prefs.getBoolean(KEY_SCREENSHOT_INPUT, false)) "hybrid"
                        else DEFAULT_PERCEPTION_MODE
        val agentModeName =
                prefs.getString(KEY_AGENT_MODE, DEFAULT_AGENT_MODE.name) ?: DEFAULT_AGENT_MODE.name
        val agentMode =
                try {
                    AgentMode.valueOf(agentModeName)
                } catch (_: Exception) {
                    DEFAULT_AGENT_MODE
                }

        val backendName =
                prefs.getString(KEY_LLM_BACKEND, DEFAULT_LLM_BACKEND.name)
                        ?: DEFAULT_LLM_BACKEND.name
        val llmBackend =
                try {
                    LLMBackendType.valueOf(backendName)
                } catch (e: Exception) {
                    DEFAULT_LLM_BACKEND
                }

        val localModelId = prefs.getString(KEY_LOCAL_MODEL_ID, null)
        val localModel = localModelId?.let { id ->
            AVAILABLE_LOCAL_MODELS.find { it.id == id }
        } ?: DEFAULT_LOCAL_MODEL
        val executorModel = prefs.getString(KEY_EXECUTOR_MODEL, null)
        val platformModeName = prefs.getString(KEY_PLATFORM_MODE, DEFAULT_PLATFORM_MODE.name)
                ?: DEFAULT_PLATFORM_MODE.name
        val platformMode = try {
            PlatformMode.valueOf(platformModeName)
        } catch (_: Exception) {
            DEFAULT_PLATFORM_MODE
        }
        val traceEnabled = prefs.getBoolean(KEY_TRACE_ENABLED, DEFAULT_TRACE_ENABLED)

        return AppSettings(
                selectedModel = selectedModel,
                maxTurns = maxTurns,
                debugMode = debugMode,
                perceptionMode = perceptionMode,
                agentMode = agentMode,
                llmBackend = llmBackend,
                localModel = localModel,
                executorModel = executorModel,
                platformMode = platformMode,
                traceEnabled = traceEnabled
        )
    }

    fun saveExecutorModel(value: String?) {
        if (value == null) {
            prefs().edit().remove(KEY_EXECUTOR_MODEL).apply()
        } else {
            prefs().edit().putString(KEY_EXECUTOR_MODEL, value).apply()
        }
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

    fun saveTraceEnabled(value: Boolean) {
        prefs().edit().putBoolean(KEY_TRACE_ENABLED, value).apply()
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

    fun savePlatformMode(value: PlatformMode) {
        prefs().edit().putString(KEY_PLATFORM_MODE, value.name).apply()
    }

    fun saveLocalModel(model: LocalModelOption) {
        prefs().edit().putString(KEY_LOCAL_MODEL_ID, model.id).apply()
    }

    // ===== Persistent allow-list =====

    fun loadPersistentAllowList(): Set<String> =
        prefs().getStringSet(KEY_USER_ALLOWED_PACKAGES, emptySet()) ?: emptySet()

    fun savePersistentAllowList(packages: Set<String>) {
        prefs().edit().putStringSet(KEY_USER_ALLOWED_PACKAGES, packages).apply()
    }
}
