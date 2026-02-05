package com.moonkey.androidagent.agent.cognition.profile

internal data class CognitionProfile(
    val id: String,
    val allowTransientNetworkRetry: Boolean = true,
    val turnPolicyMode: TurnPolicyMode = TurnPolicyMode.BASELINE,
    val loopDetectionEnabled: Boolean = true,
    val loopSimilarityThreshold: Double = 0.90,
    val loopRepeatedScreenWindow: Int = 3,
    val loopRepeatedActionWindow: Int = 3,
    val maxConsecutiveScrollActions: Int = 5,
    val maxExecutorSteps: Int = 5,
    val narrativeSummaryOnExecutorLimit: Boolean = true,
    val todoListEnabled: Boolean = true
)

internal enum class TurnPolicyMode {
    BASELINE,
    PREFER_NON_COMPLETION_SINGLE_TOOL
}

internal object BuiltinCognitionProfiles {
    const val BASELINE_ID: String = "baseline"

    val baseline: CognitionProfile =
        CognitionProfile(
            id = BASELINE_ID,
            allowTransientNetworkRetry = true,
            turnPolicyMode = TurnPolicyMode.PREFER_NON_COMPLETION_SINGLE_TOOL
        )

    fun all(): List<CognitionProfile> = listOf(baseline)
}

internal fun resolveCognitionProfile(profileId: String?): CognitionProfile {
    if (profileId == null) {
        return BuiltinCognitionProfiles.baseline
    }
    return BuiltinCognitionProfiles
        .all()
        .firstOrNull { it.id == profileId }
        ?: BuiltinCognitionProfiles.baseline
}
