package ai.closepaw.onboarding

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persistence for onboarding wizard state (step outcomes + completion flag).
 *
 * Auth credentials live in [ai.closepaw.auth.AuthStore]; the API-key typed during
 * onboarding is ViewModel-transient (process-death → retype).
 */
class OnboardingStore(private val context: Context) {

    companion object {
        private const val TAG = "OnboardingStore"
        private const val PREFS_NAME = "onboarding_prefs"

        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_COMPLETED = "onboarding_completed"
        private const val KEY_STEP_ACCESSIBILITY = "step_accessibility"
        private const val KEY_STEP_OVERLAY = "step_overlay"
        private const val KEY_STEP_BATTERY = "step_battery"
        private const val KEY_STEP_API_KEY = "step_api_key"
        private const val KEY_STEP_DEMO = "step_demo"

        // Legacy keys removed in schema v2 — retained only for migration cleanup.
        private const val LEGACY_KEY_AUTH_METHOD = "auth_method"

        private const val CURRENT_SCHEMA_VERSION = 2
    }

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    // ── Migration ──

    /**
     * Run on first access. Idempotent.
     *
     * - No schema key present → brand-new install (or pre-onboarding legacy user).
     *   Detect existing users via [hasLegacyUsageEvidence] and mark them complete.
     * - Schema < 2 → strip legacy keys introduced before the auth-cleanup split
     *   ([LEGACY_KEY_AUTH_METHOD]). The legacy encrypted prefs file
     *   `onboarding_secure_prefs` is left on disk; nothing reads it anymore.
     * - Any run: if onboarding is not complete but [hasLegacyUsageEvidence] still
     *   reports an existing user, mark complete. Recovers users whose
     *   `onboarding_prefs` was wiped/reset (e.g. selective Auto Backup restore,
     *   manual data clear of just this prefs file) while `auth_store` survived.
     *   Safe because cloud credentials can only be written through onboarding or
     *   the post-onboarding Settings page — their presence implies prior success.
     */
    fun migrateIfNeeded(hasLegacyUsageEvidence: () -> Boolean) {
        val p = prefs()
        val existing = p.getInt(KEY_SCHEMA_VERSION, -1)

        if (existing == -1) {
            val isExistingUser = hasLegacyUsageEvidence()
            p.edit()
                .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                .putBoolean(KEY_COMPLETED, isExistingUser)
                .apply()
            Log.d(
                TAG,
                if (isExistingUser) "Existing user detected — onboarding marked complete"
                else "New install — onboarding required"
            )
            return
        }

        if (existing < CURRENT_SCHEMA_VERSION) {
            p.edit()
                .remove(LEGACY_KEY_AUTH_METHOD)
                .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                .apply()
            Log.d(TAG, "Migrated onboarding schema $existing → $CURRENT_SCHEMA_VERSION")
        }

        if (!p.getBoolean(KEY_COMPLETED, false) && hasLegacyUsageEvidence()) {
            p.edit().putBoolean(KEY_COMPLETED, true).apply()
            Log.d(TAG, "Reconciled: credential exists but onboarding flag was cleared — marked complete")
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
