package ai.closepaw.protocol

/**
 * Runtime metadata captured at `TaskCompleted` for Virtual Display tasks so the
 * chat row can render explicit handoff CTAs ("Open <App>", "View virtual screen").
 *
 * Absent (null) for non-VD completions. Carries only facts knowable at completion
 * time — no intent classification.
 */
data class CompletionHandoff(
        val appPackage: String?,
        val appLabel: String?,
        val virtualDisplayAvailable: Boolean,
)
