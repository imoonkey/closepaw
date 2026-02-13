package com.moonkey.androidagent.ui.overlay.model

/**
 * CapsuleContext — where the Smart Capsule is currently displayed.
 *
 * Determines which navigation buttons [1][2][3] are relevant
 * and how the capsule is rendered (overlay vs embedded).
 */
enum class CapsuleContext {
    /** User is in the Android Agent main app. Capsule is embedded via Compose. */
    MAIN_APP,

    /** User is viewing the agent's screen (A11y overlay or VD viewer). Capsule is a system overlay. */
    SCREEN_VIEWING,

    /** VD mode, user on their own screen. Status island visible, capsule hidden. */
    BACKGROUND
}
