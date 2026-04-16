package com.moonkey.androidagent.trace

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class FileTraceRecorderTest {

    private lateinit var rootDir: File

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("trace-test").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    private fun event(seq: Long, runId: String) = TraceEventRecord(
        runId = runId,
        seq = seq,
        tsMs = seq,
        sessionId = "s1",
        type = "test"
    )

    @Test
    fun `flush makes all previously recorded lines durable`() = runBlocking {
        val runId = "run-flush"
        val recorder = FileTraceRecorder(runId = runId, rootDir = rootDir)
        repeat(100) { recorder.record(event(it.toLong(), runId)) }

        recorder.flush()

        val traceFile = File(File(rootDir, runId), "trace.jsonl")
        val lines = traceFile.readLines()
        assertThat(lines).hasSize(100)
        recorder.close()
    }

    @Test
    fun `close flushes remaining buffered content to disk`() = runBlocking {
        val runId = "run-close"
        val recorder = FileTraceRecorder(runId = runId, rootDir = rootDir)
        repeat(50) { recorder.record(event(it.toLong(), runId)) }

        recorder.close()

        val traceFile = File(File(rootDir, runId), "trace.jsonl")
        val lines = traceFile.readLines()
        assertThat(lines).hasSize(50)
    }

    @Test
    fun `sequential flush-after-record rounds all land on disk`() = runBlocking {
        val runId = "run-seq"
        val recorder = FileTraceRecorder(runId = runId, rootDir = rootDir)
        repeat(5) { batch ->
            repeat(10) { recorder.record(event((batch * 10 + it).toLong(), runId)) }
            recorder.flush()
            val traceFile = File(File(rootDir, runId), "trace.jsonl")
            assertThat(traceFile.readLines().size).isEqualTo((batch + 1) * 10)
        }
        recorder.close()
    }
}
