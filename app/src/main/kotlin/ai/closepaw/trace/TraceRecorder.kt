package ai.closepaw.trace

interface TraceRecorder {
    val enabled: Boolean
    val runId: String?

    /**
     * Absolute filesystem path of the run folder that backs the artifacts written via
     * [storeBytes] / [storeText]. Returns null when no on-disk run folder exists (e.g.
     * [NoopTraceRecorder]). Callers that need to surface artifact locations to consumers
     * outside the trace process should prefer this absolute path over [TraceArtifactRef.path],
     * which is relative to the run folder.
     */
    val runDirAbsolutePath: String? get() = null

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

    /** Block until all enqueued events are written to disk. */
    suspend fun flush()

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

    override suspend fun flush() = Unit

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

