package com.moonkey.androidagent.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.moonkey.androidagent.BuildConfig
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.PlatformMode
import java.io.File

data class AppSettings(
        val apiKey: String,
        val openAiManualApiKey: String,
        val openRouterApiKey: String,
        val novitaApiKey: String,
        val selectedModel: String,
        val maxTurns: Int,
        val debugMode: Boolean,
        val perceptionMode: String,
        val agentMode: AgentMode,
        val llmBackend: LLMBackendType,
        val localModelId: String,
        val localModelSlug: String,
        val localModelQuant: String,
        val executorModel: String?,
        val platformMode: PlatformMode,
        val traceEnabled: Boolean
)

class AppSettingsStore(private val context: Context) {
    companion object {
        private const val TAG = "AppSettingsStore"
        private const val PREFS_NAME = "agent_prefs"
        private const val ENCRYPTED_PREFS_NAME = "agent_secure_prefs"
        private const val KEY_MIGRATED = "keys_migrated"

        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_MAX_TURNS = "max_turns"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_SCREENSHOT_INPUT = "screenshot_input"
        private const val KEY_PERCEPTION_MODE = "perception_mode"
        private const val KEY_AGENT_MODE = "agent_mode"
        private const val KEY_LLM_BACKEND = "llm_backend"
        private const val KEY_LOCAL_MODEL_ID = "local_model_id"
        private const val KEY_LOCAL_MODEL_SLUG = "local_model_slug"
        private const val KEY_LOCAL_MODEL_QUANT = "local_model_quant"
        private const val KEY_EXECUTOR_MODEL = "executor_model"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_NOVITA_API_KEY = "novita_api_key"
        private const val KEY_OPENAI_MANUAL_API_KEY = "openai_manual_api_key"
        private const val KEY_PLATFORM_MODE = "platform_mode"
        private const val KEY_USER_ALLOWED_PACKAGES = "user_allowed_packages"
        private const val KEY_CREDENTIAL_SPLIT_MIGRATED = "credential_split_migrated"
        private const val KEY_TRACE_ENABLED = "trace_enabled"

        const val DEFAULT_MODEL = "glm-5"
        const val DEFAULT_MAX_TURNS = 20
        const val DEFAULT_DEBUG_MODE = false
        const val DEFAULT_PERCEPTION_MODE = "accessibility_only"
        val DEFAULT_AGENT_MODE = AgentMode.PRO
        val DEFAULT_LLM_BACKEND = LLMBackendType.OPENAI
        const val DEFAULT_LOCAL_MODEL_ID = "LFM2.5-1.2B-Instruct"
        const val DEFAULT_LOCAL_MODEL_SLUG = "LFM2.5-1.2B-Instruct"
        const val DEFAULT_LOCAL_MODEL_QUANT = "Q4_K_M"
        val DEFAULT_PLATFORM_MODE = PlatformMode.ACCESSIBILITY
        const val DEFAULT_TRACE_ENABLED = false
    }

    /** Plain prefs for non-secret settings only (model, turns, mode, etc.). */
    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var _securePrefs: SharedPreferences? = null
    private var _encryptionDegraded = false

    /** True when encrypted storage is unavailable. Credentials exist only in memory. */
    val encryptionDegraded: Boolean get() = _encryptionDegraded

    /** In-memory cache for secrets when encryption is unavailable. */
    private val memorySecrets = mutableMapOf<String, String>()

    private fun securePrefs(): SharedPreferences? {
        if (_encryptionDegraded) return null
        _securePrefs?.let { return it }
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { _securePrefs = it }
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, credentials will be memory-only: ${e.message}")
            _encryptionDegraded = true
            null
        }
    }

    private fun readSecure(key: String): String? {
        val prefs = securePrefs() ?: return memorySecrets[key]
        return try {
            prefs.getString(key, null)
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted read failed for $key: ${e.message}")
            memorySecrets[key]
        }
    }

    private fun writeSecure(key: String, value: String) {
        val prefs = securePrefs()
        if (prefs == null) {
            memorySecrets[key] = value
            return
        }
        try {
            prefs.edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted write failed for $key: ${e.message}")
            memorySecrets[key] = value
        }
    }

    private fun migrateApiKeysIfNeeded() {
        try {
            val secure = securePrefs() ?: return // can't migrate without encryption
            val plain = prefs()
            val secretKeys = listOf(KEY_API_KEY, KEY_OPENROUTER_API_KEY, KEY_NOVITA_API_KEY, KEY_OPENAI_MANUAL_API_KEY)

            // One-time migration: copy plaintext secrets to encrypted storage
            if (!secure.getBoolean(KEY_MIGRATED, false)) {
                secretKeys.forEach { key ->
                    plain.getString(key, null)?.takeIf { it.isNotBlank() }?.let { value ->
                        secure.edit().putString(key, value).apply()
                    }
                }
                secure.edit().putBoolean(KEY_MIGRATED, true).apply()
                Log.d(TAG, "Migrated API keys to encrypted storage")
            }

            // Always scrub: remove any leftover plaintext secrets from plain prefs
            val editor = plain.edit()
            var scrubbed = false
            secretKeys.forEach { key ->
                if (plain.contains(key)) {
                    editor.remove(key)
                    scrubbed = true
                }
            }
            if (scrubbed) {
                editor.apply()
                Log.d(TAG, "Removed leftover plaintext secrets from plain prefs")
            }
        } catch (e: Exception) {
            Log.w(TAG, "API key migration failed, keys remain in plain storage: ${e.message}")
        }
    }

    fun load(): AppSettings {
        migrateApiKeysIfNeeded()
        val prefs = prefs()
        val savedKey = readSecure(KEY_API_KEY)?.takeIf { it.isNotBlank() }
        val apiKey = savedKey ?: loadApiKeyFromFile().orEmpty()

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

        val localModelId =
                prefs.getString(KEY_LOCAL_MODEL_ID, DEFAULT_LOCAL_MODEL_ID)
                        ?: DEFAULT_LOCAL_MODEL_ID
        val localModelSlug =
                prefs.getString(KEY_LOCAL_MODEL_SLUG, DEFAULT_LOCAL_MODEL_SLUG)
                        ?: DEFAULT_LOCAL_MODEL_SLUG
        val localModelQuant =
                prefs.getString(KEY_LOCAL_MODEL_QUANT, DEFAULT_LOCAL_MODEL_QUANT)
                        ?: DEFAULT_LOCAL_MODEL_QUANT
        val executorModel = prefs.getString(KEY_EXECUTOR_MODEL, null)
        val openRouterApiKey = readSecure(KEY_OPENROUTER_API_KEY) ?: ""
        val novitaApiKey = readSecure(KEY_NOVITA_API_KEY) ?: ""
        val openAiManualApiKey = readSecure(KEY_OPENAI_MANUAL_API_KEY) ?: ""
        val platformModeName = prefs.getString(KEY_PLATFORM_MODE, DEFAULT_PLATFORM_MODE.name)
                ?: DEFAULT_PLATFORM_MODE.name
        val platformMode = try {
            PlatformMode.valueOf(platformModeName)
        } catch (_: Exception) {
            DEFAULT_PLATFORM_MODE
        }
        val traceEnabled = prefs.getBoolean(KEY_TRACE_ENABLED, DEFAULT_TRACE_ENABLED)

        return AppSettings(
                apiKey = apiKey,
                openAiManualApiKey = openAiManualApiKey,
                openRouterApiKey = openRouterApiKey,
                novitaApiKey = novitaApiKey,
                selectedModel = selectedModel,
                maxTurns = maxTurns,
                debugMode = debugMode,
                perceptionMode = perceptionMode,
                agentMode = agentMode,
                llmBackend = llmBackend,
                localModelId = localModelId,
                localModelSlug = localModelSlug,
                localModelQuant = localModelQuant,
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

    fun saveApiKey(value: String) {
        writeSecure(KEY_API_KEY, value)
    }

    fun saveOpenAiManualApiKey(value: String) {
        writeSecure(KEY_OPENAI_MANUAL_API_KEY, value)
    }

    /**
     * One-time migration: split legacy [KEY_API_KEY] into [KEY_OPENAI_MANUAL_API_KEY].
     *
     * If the legacy value matches [oauthAccessToken], it was an OAuth artifact written
     * before the credential split — clear it from the manual-key slot.
     * Otherwise copy it as the persisted manual OpenAI key.
     */
    fun migrateCredentialSplit(oauthAccessToken: String?) {
        try {
            val prefs = securePrefs() ?: return // can't migrate without encryption

            if (prefs.getBoolean(KEY_CREDENTIAL_SPLIT_MIGRATED, false)) return

            val legacyKey = readSecure(KEY_API_KEY) ?: ""
            if (legacyKey.isNotBlank() && readSecure(KEY_OPENAI_MANUAL_API_KEY).isNullOrBlank()) {
                if (oauthAccessToken == null || legacyKey != oauthAccessToken) {
                    writeSecure(KEY_OPENAI_MANUAL_API_KEY, legacyKey)
                }
            }
            prefs.edit().putBoolean(KEY_CREDENTIAL_SPLIT_MIGRATED, true).apply()
            Log.d(TAG, "Credential split migration completed")
        } catch (e: Exception) {
            Log.w(TAG, "Credential split migration failed: ${e.message}")
        }
    }

    fun saveOpenRouterApiKey(value: String) {
        writeSecure(KEY_OPENROUTER_API_KEY, value)
    }

    fun saveNovitaApiKey(value: String) {
        writeSecure(KEY_NOVITA_API_KEY, value)
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

    fun saveLocalModel(id: String, slug: String, quantization: String) {
        prefs().edit()
                .putString(KEY_LOCAL_MODEL_ID, id)
                .putString(KEY_LOCAL_MODEL_SLUG, slug)
                .putString(KEY_LOCAL_MODEL_QUANT, quantization)
                .apply()
    }

    // ===== Persistent allow-list =====

    fun loadPersistentAllowList(): Set<String> =
        prefs().getStringSet(KEY_USER_ALLOWED_PACKAGES, emptySet()) ?: emptySet()

    fun savePersistentAllowList(packages: Set<String>) {
        prefs().edit().putStringSet(KEY_USER_ALLOWED_PACKAGES, packages).apply()
    }

    private fun loadApiKeyFromFile(): String? {
        if (!BuildConfig.DEBUG) return null
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

}
