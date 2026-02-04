package com.moonkey.androidagent.agent.cognition.profile

data class CognitionProfile(
    val id: String,
    val promptVariant: PromptVariant = PromptVariant.BASELINE,
    val contextPolicy: ContextPolicy = ContextPolicy.STANDARD,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val turnPolicyMode: TurnPolicyMode = TurnPolicyMode.BASELINE
)

enum class PromptVariant {
    BASELINE,
    CONCISE
}

enum class ContextPolicy {
    STANDARD
}

data class RetryPolicy(
    val allowTransientNetworkRetry: Boolean = true
)

enum class TurnPolicyMode {
    BASELINE,
    PREFER_NON_COMPLETION_SINGLE_TOOL
}
