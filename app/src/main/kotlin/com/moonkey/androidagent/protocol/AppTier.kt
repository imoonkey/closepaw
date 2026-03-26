package com.moonkey.androidagent.protocol

/**
 * App classification tier for security decisions.
 *
 * BLOCKED: Financial/auth apps — screen masked, all actions denied.
 * CAUTIOUS: Unknown apps — actions require approval in SMART mode.
 * NORMAL: Known safe apps — actions auto-approved in SMART mode.
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
