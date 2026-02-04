package com.moonkey.androidagent.agent.cognition.profile

import com.moonkey.androidagent.agent.cognition.context.ContextPolicy
import com.moonkey.androidagent.agent.cognition.policy.RetryPolicy

object BuiltinCognitionProfiles {
    const val BASELINE_ID: String = "baseline"
    private const val CONCISE_ID: String = "concise"

    val baseline: CognitionProfile =
        CognitionProfile(
            id = BASELINE_ID,
            promptVariant = PromptVariant.BASELINE,
            contextPolicy = ContextPolicy.STANDARD,
            retryPolicy = RetryPolicy(allowTransientNetworkRetry = true),
            turnPolicyMode = TurnPolicyMode.PREFER_NON_COMPLETION_SINGLE_TOOL
        )

    val concise: CognitionProfile =
        CognitionProfile(
            id = CONCISE_ID,
            promptVariant = PromptVariant.CONCISE,
            contextPolicy = ContextPolicy.STANDARD,
            retryPolicy = RetryPolicy(allowTransientNetworkRetry = true),
            turnPolicyMode = TurnPolicyMode.PREFER_NON_COMPLETION_SINGLE_TOOL
        )

    fun all(): List<CognitionProfile> = listOf(baseline, concise)
}
