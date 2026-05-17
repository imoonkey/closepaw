package ai.closepaw.protocol

/**
 * App classification tier for security decisions.
 *
 * BLOCKED:  Sensitive apps (financial/auth) — screen masked, all actions denied.
 *           Bundled-BLOCKED rows are flagged as sensitive in the App Access UI; downgrading
 *           one requires a one-tap confirmation, but the override fully takes effect.
 * CAUTIOUS: Unknown apps — actions require approval in SMART mode.
 *           User-settable via App Access settings ("Ask").
 * NORMAL:   Known safe apps — actions auto-approved in SMART mode.
 *           User-settable via App Access settings ("Allow").
 *
 * Effective tier: user override if present, otherwise the bundled tier, otherwise CAUTIOUS.
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
