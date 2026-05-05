package ai.closepaw.trace

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong

internal class FileTraceRecorder(
    override val runId: String,
    private val rootDir: File
) : TraceRecorder {
    companion object {
        private const val TAG = "FileTraceRecorder"
        private const val TRACE_FILE_NAME = "trace.jsonl"
        private const val META_FILE_NAME = "meta.json"
        private const val ARTIFACTS_DIR_NAME = "artifacts"
        private const val WRITE_CHANNEL_CAPACITY = 2048
    }

    private sealed interface WriteOp {
        data class AppendLine(val line: String) : WriteOp
        data class WriteBytes(val relativePath: String, val bytes: ByteArray) : WriteOp
        data class WriteUtf8(val relativePath: String, val content: String) : WriteOp
        data class Flush(val done: CompletableDeferred<Unit>) : WriteOp
    }

    override val enabled: Boolean = true

    override val runDirAbsolutePath: String get() = runDir.absolutePath

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<WriteOp>(capacity = WRITE_CHANNEL_CAPACITY)
    private val seq = AtomicLong(0L)
    private val artifactSeq = AtomicLong(0L)
    private val writerJob: Job

    private val runDir: File = File(rootDir, runId)
    private val traceFile: File = File(runDir, TRACE_FILE_NAME)

    init {
        if (!runDir.exists() && !runDir.mkdirs()) {
            Log.w(TAG, "Failed to create trace run dir: ${runDir.absolutePath}")
        }

        writerJob =
            scope.launch {
                runWriterLoop()
            }
    }

    fun writeMeta(meta: TraceRunMeta) {
        val json = TraceJson.instance.encodeToString(meta)
        enqueue(WriteOp.WriteUtf8(META_FILE_NAME, json))
    }

    override fun record(event: TraceEventRecord) {
        val line = TraceJson.instance.encodeToString(event)
        enqueue(WriteOp.AppendLine(line))
    }

    override fun storeText(
        kind: String,
        filenameHint: String,
        content: String,
        mimeType: String?,
        description: String?
    ): TraceArtifactRef? {
        val path = newArtifactPath(kind, filenameHint, defaultExt = "txt")
        enqueue(WriteOp.WriteUtf8(path, content))
        return TraceArtifactRef(kind = kind, path = path, mimeType = mimeType, description = description)
    }

    override fun storeBytes(
        kind: String,
        filenameHint: String,
        bytes: ByteArray,
        mimeType: String?,
        description: String?
    ): TraceArtifactRef? {
        val path = newArtifactPath(kind, filenameHint, defaultExt = "bin")
        enqueue(WriteOp.WriteBytes(path, bytes))
        return TraceArtifactRef(kind = kind, path = path, mimeType = mimeType, description = description)
    }

    override fun nextSeq(): Long = seq.incrementAndGet()

    private fun enqueue(op: WriteOp) {
        val result = channel.trySend(op)
        if (result.isFailure) {
            Log.w(TAG, "Trace write dropped (channel full): ${result.exceptionOrNull()?.message}")
        }
    }

    private fun newArtifactPath(kind: String, filenameHint: String, defaultExt: String): String {
        val safeKind = sanitizePathSegment(kind.ifBlank { "artifact" })
        val hint = filenameHint.trim().ifBlank { "data" }
        val safeHint = sanitizePathSegment(hint)
        val ext = safeHint.substringAfterLast('.', missingDelimiterValue = defaultExt).ifBlank { defaultExt }
        val base = safeHint.substringBeforeLast('.', missingDelimiterValue = safeHint).ifBlank { "data" }
        val id = artifactSeq.incrementAndGet()
        return "$ARTIFACTS_DIR_NAME/$safeKind/${id}_${base}.$ext"
    }

    private fun sanitizePathSegment(value: String): String {
        return value
            .replace(Regex("[^a-zA-Z0-9._-]+"), "_")
            .trim('_')
            .take(80)
            .ifBlank { "artifact" }
    }

    private suspend fun runWriterLoop() {
        var writer: BufferedWriter? = null
        try {
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(traceFile, true), Charsets.UTF_8))
            for (op in channel) {
                when (op) {
                    is WriteOp.AppendLine -> {
                        writer.append(op.line)
                        writer.newLine()
                    }
                    is WriteOp.WriteBytes -> writeBytes(op.relativePath, op.bytes)
                    is WriteOp.WriteUtf8 -> writeText(op.relativePath, op.content)
                    is WriteOp.Flush -> {
                        writer.flush()
                        op.done.complete(Unit)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Trace writer loop failed", e)
        } finally {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeText(relativePath: String, content: String) {
        val file = File(runDir, relativePath)
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        try {
            file.writeText(content, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write artifact text: $relativePath (${e.message})")
        }
    }

    private fun writeBytes(relativePath: String, bytes: ByteArray) {
        val file = File(runDir, relativePath)
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        try {
            file.outputStream().use { it.write(bytes) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write artifact bytes: $relativePath (${e.message})")
        }
    }

    override suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        channel.send(WriteOp.Flush(done))
        done.await()
    }

    override suspend fun close() {
        channel.close()
        withContext(Dispatchers.IO) {
            writerJob.join()
        }
        scope.cancel()
    }
}

