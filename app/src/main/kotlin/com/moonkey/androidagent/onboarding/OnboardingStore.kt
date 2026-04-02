package com.moonkey.androidagent.onboarding

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistence for onboarding wizard state.
 *
 * Uses its own prefs file ("onboarding_prefs") — separate from AppSettingsStore.
 * Encrypted storage for the API key draft (survives process death without re-entry).
 */
class OnboardingStore(private val context: Context) {

    companion object {
        private const val TAG = "OnboardingStore"
        private const val PREFS_NAME = "onboarding_prefs"
        private const val ENCRYPTED_PREFS_NAME = "onboarding_secure_prefs"

        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_COMPLETED = "onboarding_completed"
        private const val KEY_STEP_ACCESSIBILITY = "step_accessibility"
        private const val KEY_STEP_OVERLAY = "step_overlay"
        private const val KEY_STEP_BATTERY = "step_battery"
        private const val KEY_STEP_API_KEY = "step_api_key"
        private const val KEY_STEP_DEMO = "step_demo"
        private const val KEY_API_KEY_DRAFT = "onboarding_api_key_draft"
        private const val KEY_AUTH_METHOD = "auth_method"

        private const val CURRENT_SCHEMA_VERSION = 1
    }

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Encrypted prefs for API key draft ──

    private var _securePrefs: SharedPreferences? = null
    private var securePrefsFailed = false

    private fun securePrefs(): SharedPreferences {
        if (securePrefsFailed) return prefs()
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
            Log.w(TAG, "EncryptedSharedPreferences unavailable: ${e.message}")
            securePrefsFailed = true
            prefs()
        }
    }

    // ── Public API ──

    /** Whether the full onboarding wizard has been completed. */
    val isCompleted: Boolean
        get() = prefs().getBoolean(KEY_COMPLETED, false)

    /** Mark onboarding as complete. Only called from CompleteStep CTA. */
    fun setCompleted() {
        prefs().edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    /** Load persisted step outcomes. */
    fun loadOutcomes(): StepOutcomes {
        val p = prefs()
        return StepOutcomes(
            accessibility = p.readOutcome(KEY_STEP_ACCESSIBILITY),
            overlay = p.readOutcome(KEY_STEP_OVERLAY),
            battery = p.readOutcome(KEY_STEP_BATTERY),
            apiKey = p.readOutcome(KEY_STEP_API_KEY),
            demo = p.readOutcome(KEY_STEP_DEMO)
        )
    }

    /** Persist a single step outcome. */
    fun saveOutcome(step: WizardStep, outcome: StepOutcome) {
        val key = when (step) {
            WizardStep.Accessibility -> KEY_STEP_ACCESSIBILITY
            WizardStep.Overlay -> KEY_STEP_OVERLAY
            WizardStep.Battery -> KEY_STEP_BATTERY
            WizardStep.ApiKey -> KEY_STEP_API_KEY
            WizardStep.Demo -> KEY_STEP_DEMO
            WizardStep.Complete -> return // not persisted as a step outcome
        }
        prefs().edit().putString(key, outcome.toStorageValue()).apply()
    }

    // ── Auth method ──

    fun saveAuthMethod(method: String) {
        prefs().edit().putString(KEY_AUTH_METHOD, method).apply()
    }

    fun loadAuthMethod(): String? = prefs().getString(KEY_AUTH_METHOD, null)

    // ── Encrypted API key draft ──
    // Draft is only stored in encrypted prefs. If encryption is unavailable,
    // the draft is not persisted (user must re-enter on next launch).

    fun loadApiKeyDraft(): String? {
        if (securePrefsFailed) return null
        return try {
            securePrefs().getString(KEY_API_KEY_DRAFT, null)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read API key draft: ${e.message}")
            null
        }
    }

    fun saveApiKeyDraft(key: String) {
        if (securePrefsFailed) return
        try {
            securePrefs().edit().putString(KEY_API_KEY_DRAFT, key).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save API key draft: ${e.message}")
        }
    }

    fun clearApiKeyDraft() {
        if (securePrefsFailed) return
        try {
            securePrefs().edit().remove(KEY_API_KEY_DRAFT).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear API key draft: ${e.message}")
        }
    }

    // ── Migration ──

    /**
     * Run on first access. If schema_version is absent:
     * - Check for legacy usage evidence (stored keys, session history, non-default settings).
     * - If evidence exists → mark onboarding complete (existing user).
     * - Otherwise → leave incomplete (new install).
     */
    fun migrateIfNeeded(hasLegacyUsageEvidence: () -> Boolean) {
        val p = prefs()
        if (p.contains(KEY_SCHEMA_VERSION)) return

        val isExistingUser = hasLegacyUsageEvidence()
        p.edit()
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .putBoolean(KEY_COMPLETED, isExistingUser)
            .apply()

        if (isExistingUser) {
            Log.d(TAG, "Existing user detected — onboarding marked complete")
        } else {
            Log.d(TAG, "New install — onboarding required")
        }
    }

    // ── Helpers ──

    private fun SharedPreferences.readOutcome(key: String): StepOutcome =
        when (getString(key, null)) {
            "done" -> StepOutcome.Done
            "skipped" -> StepOutcome.Skipped
            else -> StepOutcome.Pending
        }

    private fun StepOutcome.toStorageValue(): String = when (this) {
        StepOutcome.Pending -> "pending"
        StepOutcome.Done -> "done"
        StepOutcome.Skipped -> "skipped"
    }
}
