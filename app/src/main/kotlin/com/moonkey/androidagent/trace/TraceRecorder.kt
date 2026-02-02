package com.moonkey.androidagent.trace

interface TraceRecorder {
    val enabled: Boolean
    val runId: String?

    fun nextSeq(): Long

    fun record(event: TraceEventRecord)

    /**
     * Store a UTF-8 text artifact in the trace run folder.
     * Returns a reference that can be attached to events.
     */
    fun storeText(
        kind: String,
        filenameHint: String,
        content: String,
        mimeType: String? = null,
        description: String? = null
    ): TraceArtifactRef?

    /**
     * Store bytes artifact in the trace run folder.
     * Returns a reference that can be attached to events.
     */
    fun storeBytes(
        kind: String,
        filenameHint: String,
        bytes: ByteArray,
        mimeType: String? = null,
        description: String? = null
    ): TraceArtifactRef?

    suspend fun close()
}

object NoopTraceRecorder : TraceRecorder {
    override val enabled: Boolean = false
    override val runId: String? = null

    override fun nextSeq(): Long = 0L

    override fun record(event: TraceEventRecord) = Unit

    override fun storeText(
        kind: String,
        filenameHint: String,
        content: String,
        mimeType: String?,
        description: String?
    ): TraceArtifactRef? = null

    override fun storeBytes(
        kind: String,
        filenameHint: String,
        bytes: ByteArray,
        mimeType: String?,
        description: String?
    ): TraceArtifactRef? = null

    override suspend fun close() = Unit
}

internal fun TraceRecorder.emit(
    sessionId: String,
    type: String,
    turnId: String? = null,
    turnNumber: Int? = null,
    tsMs: Long = System.currentTimeMillis(),
    data: kotlinx.serialization.json.JsonElement? = null,
    artifacts: List<TraceArtifactRef> = emptyList()
) {
    if (!enabled) return
    val run = runId ?: return
    record(
        TraceEventRecord(
            runId = run,
            seq = nextSeq(),
            tsMs = tsMs,
            sessionId = sessionId,
            turnId = turnId,
            turnNumber = turnNumber,
            type = type,
            data = data,
            artifacts = artifacts
        )
    )
}

