package com.moonkey.androidagent.agent.cognition.profile

import com.moonkey.androidagent.agent.cognition.context.ContextPolicy
import com.moonkey.androidagent.agent.cognition.policy.RetryPolicy

object BuiltinCognitionProfiles {
    const val BASELINE_ID: String = "baseline"

    val baseline: CognitionProfile =
        CognitionProfile(
            id = BASELINE_ID,
            contextPolicy = ContextPolicy.STANDARD,
            retryPolicy = RetryPolicy(allowTransientNetworkRetry = true),
            turnPolicyMode = TurnPolicyMode.PREFER_NON_COMPLETION_SINGLE_TOOL
        )

    fun all(): List<CognitionProfile> = listOf(baseline)
}
