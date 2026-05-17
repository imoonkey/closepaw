package ai.closepaw.protocol

/**
 * App classification tier for security decisions.
 *
 * BLOCKED:  Sensitive apps (financial/auth) — screen masked, all actions denied.
 *           Bundled-BLOCKED is an absolute floor: the App Access UI exposes Reject-only,
 *           and the classifier refuses any non-BLOCKED override on these packages.
 * CAUTIOUS: Unknown apps — actions require approval in SMART mode.
 *           User-settable via App Access settings ("Ask").
 * NORMAL:   Known safe apps — actions auto-approved in SMART mode.
 *           User-settable via App Access settings ("Allow").
 *
 * Effective tier: if bundled == BLOCKED then BLOCKED; otherwise override ?: bundled ?: CAUTIOUS.
 * See [ai.closepaw.tool.AppClassifier].
 */
enum class AppTier {
    BLOCKED,
    CAUTIOUS,
    NORMAL;

    companion object {
        fun fromString(value: String): AppTier? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
