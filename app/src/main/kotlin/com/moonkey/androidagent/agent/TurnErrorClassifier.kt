package com.moonkey.androidagent.agent

import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal data class TurnErrorClassification(
    val message: String,
    val recoverable: Boolean
)

/**
 * Classifies turn errors into recoverable/non-recoverable categories.
 *
 * Keep this logic isolated from turn orchestration so policies can evolve
 * without touching AgentTurnRunner control flow.
 */
internal object TurnErrorClassifier {
    fun classify(error: Throwable): TurnErrorClassification {
        val causes = generateSequence(error) { it.cause }.toList()

        fun anyMessageContains(keyword: String): Boolean {
            return causes.any { cause ->
                cause.message?.contains(keyword, ignoreCase = true) == true
            }
        }

        val isDnsFailure =
            causes.any { it is UnknownHostException } ||
                anyMessageContains("Unable to resolve host") ||
                anyMessageContains("No address associated")

        val isContextLimit =
            anyMessageContains("context length") ||
                anyMessageContains("maximum context") ||
                anyMessageContains("context window") ||
                anyMessageContains("too many tokens") ||
                anyMessageContains("max tokens")

        val isTransientNetworkError =
            !isDnsFailure &&
                !isContextLimit &&
                (causes.any { it is SocketTimeoutException } ||
                    anyMessageContains("timeout") ||
                    anyMessageContains("connection refused") ||
                    anyMessageContains("connection reset"))

        return TurnErrorClassification(
            message = error.message.orEmpty().ifEmpty { "Unknown error" },
            recoverable = isTransientNetworkError
        )
    }
}
