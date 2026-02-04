package com.moonkey.androidagent.agent.cognition.policy

data class RetryPolicy(
    val allowTransientNetworkRetry: Boolean = true
)
