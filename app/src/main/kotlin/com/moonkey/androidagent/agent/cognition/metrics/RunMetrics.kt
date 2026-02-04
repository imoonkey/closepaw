package com.moonkey.androidagent.agent.cognition.metrics

internal data class RunMetrics(
    var turnsStarted: Int = 0,
    var turnsCompleted: Int = 0,
    var turnErrors: Int = 0,
    var llmRequests: Int = 0,
    var llmResponses: Int = 0,
    var toolCalls: Int = 0,
    var toolSuccesses: Int = 0,
    var toolFailures: Int = 0
)
