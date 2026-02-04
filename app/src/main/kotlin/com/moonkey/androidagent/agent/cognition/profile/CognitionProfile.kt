package com.moonkey.androidagent.agent.cognition.profile

import com.moonkey.androidagent.agent.cognition.context.ContextPolicy
import com.moonkey.androidagent.agent.cognition.policy.RetryPolicy

data class CognitionProfile(
    val id: String,
    val promptVariant: PromptVariant = PromptVariant.BASELINE,
    val contextPolicy: ContextPolicy = ContextPolicy.STANDARD,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val turnPolicyMode: TurnPolicyMode = TurnPolicyMode.BASELINE,
    val loopDetectionEnabled: Boolean = true,
    val loopSimilarityThreshold: Double = 0.90,
    val loopRepeatedScreenWindow: Int = 3,
    val loopRepeatedActionWindow: Int = 3,
    val maxConsecutiveScrollActions: Int = 5,
    val maxExecutorSteps: Int = 5,
    val narrativeSummaryOnExecutorLimit: Boolean = true,
    val failureRecoveryRulesEnabled: Boolean = true,
    val todoListEnabled: Boolean = true
)

enum class PromptVariant {
    BASELINE,
    CONCISE
}

enum class TurnPolicyMode {
    BASELINE,
    PREFER_NON_COMPLETION_SINGLE_TOOL
}
