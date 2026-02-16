package com.moonkey.androidagent.session

/** Formats a human-readable summary for SessionServices debugging. */
internal object SessionServicesSummaryFormatter {
    fun format(services: SessionServices): String {
        return buildString {
            appendLine("=== SessionServices Summary ===")
            appendLine()
            appendLine("Config:")
            appendLine("  Main Model: ${services.config.mainModel}")
            services.config.executorModel?.let { appendLine("  Executor Model: $it") }
            appendLine("  Approval Mode: ${services.config.approvalMode}")
            appendLine("  Max Turns: ${services.config.maxTurns}")
            appendLine("  Action Delay: ${services.config.actionDelayMs}ms")
            appendLine("  Debug Mode: ${services.config.debugMode}")
            appendLine()
            appendLine("Tools (${services.toolRegistry.size()}):")
            services.toolRegistry.getNames().forEach { name -> appendLine("  - $name") }
            appendLine()
            appendLine("History:")
            appendLine("  Items: ${services.historyManager.size()}")
            appendLine("  Tokens: ~${services.historyManager.estimateTokenCount()}")
            appendLine()
            appendLine("Platform:")
            appendLine("  Permissions OK: ${services.platform.hasRequiredPermissions()}")
            appendLine(
                    "  Current Package: ${services.platform.getCurrentPackageName() ?: "unknown"}"
            )
        }
    }
}
