package ai.closepaw.app

import android.content.Context
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.settings.AVAILABLE_LOCAL_MODELS
import ai.closepaw.ui.settings.LocalModelOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class AppSettings(
        val selectedModel: String,
        val debugMode: Boolean,
        val perceptionMode: String,
        val llmBackend: LLMBackendType,
        val localModel: LocalModelOption,
        val platformMode: PlatformMode,
        val traceEnabled: Boolean,
        val browserScriptEnabled: Boolean,
        val termuxShellEnabled: Boolean,
        val openaiBaseUrl: String
)

class AppSettingsStore(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "agent_prefs"

        private const val KEY_MODEL = "model"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_SCREENSHOT_INPUT = "screenshot_input"
        private const val KEY_PERCEPTION_MODE = "perception_mode"
        private const val KEY_LLM_BACKEND = "llm_backend"
        private const val KEY_LOCAL_MODEL_ID = "local_model_id"
        private const val KEY_PLATFORM_MODE = "platform_mode"
        private const val KEY_USER_ALLOWED_PACKAGES = "user_allowed_packages"
        private const val KEY_TRACE_ENABLED = "trace_enabled"
        private const val KEY_BROWSER_SCRIPT_ENABLED = "browser_script_enabled"
        private const val KEY_TERMUX_SHELL_ENABLED = "termux_shell_enabled"
        private const val KEY_OPENAI_BASE_URL = "openai_base_url"

        const val DEFAULT_MODEL = "glm-5"
        const val DEFAULT_DEBUG_MODE = false
        const val DEFAULT_PERCEPTION_MODE = "accessibility_only"
        val DEFAULT_LLM_BACKEND = LLMBackendType.OPENAI
        val DEFAULT_LOCAL_MODEL: LocalModelOption = AVAILABLE_LOCAL_MODELS.first()
        val DEFAULT_PLATFORM_MODE = PlatformMode.ACCESSIBILITY
        const val DEFAULT_TRACE_ENABLED = false
        const val DEFAULT_BROWSER_SCRIPT_ENABLED = false
        const val DEFAULT_TERMUX_SHELL_ENABLED = true
    }

    private val _termuxShellEnabled = MutableStateFlow(loadTermuxShellEnabled())
    val termuxShellEnabled: StateFlow<Boolean> = _termuxShellEnabled.asStateFlow()

    private val _browserScriptEnabled = MutableStateFlow(loadBrowserScriptEnabled())
    val browserScriptEnabled: StateFlow<Boolean> = _browserScriptEnabled.asStateFlow()

    /** Plain prefs for non-secret settings only (model, turns, mode, etc.). */
    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val prefs = prefs()

        val selectedModel = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val debugMode = prefs.getBoolean(KEY_DEBUG_MODE, DEFAULT_DEBUG_MODE)
        val perceptionMode =
                prefs.getString(KEY_PERCEPTION_MODE, null)
                        ?: if (prefs.getBoolean(KEY_SCREENSHOT_INPUT, false)) "hybrid"
                        else DEFAULT_PERCEPTION_MODE

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
        val platformModeName = prefs.getString(KEY_PLATFORM_MODE, DEFAULT_PLATFORM_MODE.name)
                ?: DEFAULT_PLATFORM_MODE.name
        val platformMode = try {
            PlatformMode.valueOf(platformModeName)
        } catch (_: Exception) {
            DEFAULT_PLATFORM_MODE
        }
        val traceEnabled = prefs.getBoolean(KEY_TRACE_ENABLED, DEFAULT_TRACE_ENABLED)
        val browserScriptEnabled =
                prefs.getBoolean(KEY_BROWSER_SCRIPT_ENABLED, DEFAULT_BROWSER_SCRIPT_ENABLED)
        val termuxShellEnabled = prefs.getBoolean(
                KEY_TERMUX_SHELL_ENABLED,
                DEFAULT_TERMUX_SHELL_ENABLED
        )
        val openaiBaseUrl = prefs.getString(KEY_OPENAI_BASE_URL, "") ?: ""

        return AppSettings(
                selectedModel = selectedModel,
                debugMode = debugMode,
                perceptionMode = perceptionMode,
                llmBackend = llmBackend,
                localModel = localModel,
                platformMode = platformMode,
                traceEnabled = traceEnabled,
                browserScriptEnabled = browserScriptEnabled,
                termuxShellEnabled = termuxShellEnabled,
                openaiBaseUrl = openaiBaseUrl
        )
    }

    fun loadTermuxShellEnabled(): Boolean =
        prefs().getBoolean(KEY_TERMUX_SHELL_ENABLED, DEFAULT_TERMUX_SHELL_ENABLED)

    suspend fun setTermuxShellEnabled(value: Boolean) {
        withContext(Dispatchers.IO) {
            prefs().edit().putBoolean(KEY_TERMUX_SHELL_ENABLED, value).apply()
        }
        _termuxShellEnabled.value = value
    }

    fun loadBrowserScriptEnabled(): Boolean =
        prefs().getBoolean(KEY_BROWSER_SCRIPT_ENABLED, DEFAULT_BROWSER_SCRIPT_ENABLED)

    suspend fun setBrowserScriptEnabled(value: Boolean) {
        withContext(Dispatchers.IO) {
            prefs().edit().putBoolean(KEY_BROWSER_SCRIPT_ENABLED, value).apply()
        }
        _browserScriptEnabled.value = value
    }

    fun saveModel(value: String) {
        prefs().edit().putString(KEY_MODEL, value).apply()
    }

    fun saveDebugMode(value: Boolean) {
        prefs().edit().putBoolean(KEY_DEBUG_MODE, value).apply()
    }

    fun saveTraceEnabled(value: Boolean) {
        prefs().edit().putBoolean(KEY_TRACE_ENABLED, value).apply()
    }

    fun saveBrowserScriptEnabled(value: Boolean) {
        prefs().edit().putBoolean(KEY_BROWSER_SCRIPT_ENABLED, value).apply()
        _browserScriptEnabled.value = value
    }

    fun savePerceptionMode(value: String) {
        prefs().edit().putString(KEY_PERCEPTION_MODE, value).apply()
    }

    fun saveOpenaiBaseUrl(value: String) {
        if (value.isBlank()) {
            prefs().edit().remove(KEY_OPENAI_BASE_URL).apply()
        } else {
            prefs().edit().putString(KEY_OPENAI_BASE_URL, value).apply()
        }
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
