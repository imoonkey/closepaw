package ai.closepaw.protocol

/** Screen has been captured and analyzed. */
data class ScreenCaptured(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val elementCount: Int,
        val packageName: String?,
        val activityName: String?,
        val turnId: String,
        val turnNumber: Int,
        val phase: ScreenStatePhase,
        val rawA11yTreePath: String?,
        val sanitizedA11yTreePath: String?,
        val screenshotPath: String?,
        val traceRunId: String?
) : PerceptionDomainEvent
