package ai.closepaw.protocol

/**
 * App classification tier for security decisions.
 *
 * BLOCKED:  Financial/auth apps — screen masked, all actions denied.
 *           **Bundled-BLOCKED is the safety floor and is non-overridable by the user.**
 * CAUTIOUS: Unknown apps — actions require approval in SMART mode.
 *           User-settable via App Security settings ("Ask").
 * NORMAL:   Known safe apps — actions auto-approved in SMART mode.
 *           User-settable via App Security settings ("Always").
 *
 * The effective tier for a package is the user override if present, otherwise the bundled
 * tier, otherwise CAUTIOUS — except bundled-BLOCKED, which always wins. See [ai.closepaw.tool.AppClassifier].
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
