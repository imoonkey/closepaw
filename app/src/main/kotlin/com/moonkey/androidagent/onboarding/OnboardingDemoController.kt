package com.moonkey.androidagent.onboarding

/**
 * Controls the demo session lifecycle during onboarding.
 *
 * Creates a throwaway AgentSession, runs "Open the Settings app", observes events,
 * and reports success/failure. Not tied to SessionCoordinator or ChatViewModel.
 */
interface OnboardingDemoController {
    fun run(
        onSuccess: (message: String) -> Unit,
        onFailure: (reason: String) -> Unit,
        onBringToFront: () -> Unit
    )

    fun cancel()
}
