package ai.closepaw.trace

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    fun `storeText with path traversal hint is sanitized and stays under run dir`() = runBlocking {
        val runId = "run-sanitize"
        val recorder = FileTraceRecorder(runId = runId, rootDir = rootDir)

        val ref = recorder.storeText(
            kind = "../../evil",
            filenameHint = "../etc/passwd",
            content = "payload",
            mimeType = null,
            description = null
        )

        recorder.flush()
        recorder.close()

        assertThat(ref).isNotNull()
        val path = ref!!.path
        assertThat(path).doesNotContain("/../")
        assertThat(path).doesNotContain("../")
        assertThat(path.startsWith("artifacts/")).isTrue()
        assertThat(path.split('/')).hasSize(3)

        val runDir = File(rootDir, runId)
        val resolved = File(runDir, path).canonicalFile
        assertThat(resolved.canonicalPath.startsWith(runDir.canonicalPath + File.separator)).isTrue()
        assertThat(resolved.exists()).isTrue()
        assertThat(resolved.readText()).isEqualTo("payload")
    }

    @Test
    fun `storeText creates missing artifacts directory`() = runBlocking {
        val runId = "run-artifact-mkdir"
        val recorder = FileTraceRecorder(runId = runId, rootDir = rootDir)
        val artifactsDir = File(File(rootDir, runId), "artifacts")
        assertThat(artifactsDir.exists()).isFalse()

        val ref = recorder.storeText(
            kind = "log",
            filenameHint = "payload.txt",
            content = "hello",
            mimeType = null,
            description = null
        )
        recorder.flush()
        recorder.close()

        assertThat(ref).isNotNull()
        assertThat(artifactsDir.exists()).isTrue()
        assertThat(artifactsDir.isDirectory).isTrue()
        val file = File(File(rootDir, runId), ref!!.path)
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `concurrent record calls process without data loss`() = runBlocking {
        val runId = "run-concurrent"
        val recorder = FileTraceRecorder(runId = runId, rootDir = rootDir)
        val producers = 8
        val perProducer = 100

        coroutineScope {
            (0 until producers).map { p ->
                async {
                    repeat(perProducer) { i ->
                        recorder.record(event((p * perProducer + i).toLong(), runId))
                    }
                }
            }.awaitAll()
        }

        recorder.close()

        val traceFile = File(File(rootDir, runId), "trace.jsonl")
        val lines = traceFile.readLines()
        assertThat(lines).hasSize(producers * perProducer)
        lines.forEach { assertThat(it).startsWith("{") }
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
