package com.moonkey.androidagent.onboarding

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.moonkey.androidagent.app.AgentService

/**
 * Live permission state checks.
 *
 * Used by OnboardingViewModel during the wizard and by PermissionRepairCard
 * after onboarding completes. All checks are synchronous (no suspend).
 */
class PermissionStateMonitor(private val context: Context) {

    fun isAccessibilityEnabled(): Boolean = AgentService.instance != null

    fun isOverlayEnabled(): Boolean = Settings.canDrawOverlays(context)

    fun isBatteryOptimized(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Data class for post-onboarding repair card. */
    data class PermissionRepairModel(
        val accessibilityMissing: Boolean,
        val overlayMissing: Boolean,
        val batteryMissing: Boolean // only if was DONE during onboarding, not SKIPPED
    ) {
        val hasAnyIssue: Boolean
            get() = accessibilityMissing || overlayMissing || batteryMissing

        /** Highest-priority missing permission label. */
        val primaryIssue: String?
            get() = when {
                accessibilityMissing -> "Accessibility service is disabled"
                overlayMissing -> "Overlay permission is revoked"
                batteryMissing -> "Battery optimization re-enabled"
                else -> null
            }
    }

    /**
     * Derive repair model for post-onboarding state.
     *
     * @param batteryWasDone true if the user granted battery during onboarding (not skipped)
     * @return null if everything is fine
     */
    fun deriveRepairModel(batteryWasDone: Boolean): PermissionRepairModel? =
        deriveRepairModel(
            accessibilityEnabled = isAccessibilityEnabled(),
            overlayEnabled = isOverlayEnabled(),
            batteryIgnoringOptimizations = isBatteryOptimized(),
            batteryWasDone = batteryWasDone
        )

    companion object {
        /** Pure logic for repair-model derivation. Unit-testable without Android. */
        fun deriveRepairModel(
            accessibilityEnabled: Boolean,
            overlayEnabled: Boolean,
            batteryIgnoringOptimizations: Boolean,
            batteryWasDone: Boolean
        ): PermissionRepairModel? {
            val model = PermissionRepairModel(
                accessibilityMissing = !accessibilityEnabled,
                overlayMissing = !overlayEnabled,
                batteryMissing = batteryWasDone && !batteryIgnoringOptimizations
            )
            return if (model.hasAnyIssue) model else null
        }
    }
}
