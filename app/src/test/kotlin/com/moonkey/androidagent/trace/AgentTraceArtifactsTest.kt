package com.moonkey.androidagent.trace

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class AgentTraceArtifactsTest {

    private lateinit var rootDir: File
    private lateinit var recorder: FileTraceRecorder
    private lateinit var artifacts: AgentTraceArtifacts
    private val runId = "run-artifacts-test"

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("agent-trace-artifacts-test").toFile()
        recorder = FileTraceRecorder(runId = runId, rootDir = rootDir)
        artifacts = AgentTraceArtifacts(recorder)
    }

    @After
    fun tearDown() {
        runBlocking { recorder.close() }
        rootDir.deleteRecursively()
    }

    @Test
    fun `artifact naming sanitizes hints and avoids collisions across repeated calls`() = runBlocking {
        val hint = "../etc/turn_1_system.txt"
        val refs = (1..3).map {
            artifacts.storeRedactedText(
                kind = "llm_system_prompt",
                filenameHint = hint,
                content = "prompt #$it",
                mimeType = "text/plain"
            )
        }
        recorder.flush()

        val paths = refs.map { it!!.path }
        assertThat(paths).hasSize(3)
        assertThat(paths.toSet()).hasSize(3)

        val runDir = File(rootDir, runId)
        paths.forEach { path ->
            assertThat(path).startsWith("artifacts/llm_system_prompt/")
            val resolved = File(runDir, path).canonicalFile
            assertThat(resolved.canonicalPath.startsWith(runDir.canonicalPath + File.separator)).isTrue()
            assertThat(resolved.parentFile.canonicalPath)
                .startsWith(runDir.canonicalPath + File.separator + "artifacts")
            assertThat(resolved.exists()).isTrue()
        }
    }

    @Test
    fun `storeRedactedText writes file with secrets redacted`() = runBlocking {
        val rawToken = "sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val rawEmail = "user@example.com"
        val rawContent = "Authorization: Bearer $rawToken\ncontact: $rawEmail"

        val ref = artifacts.storeRedactedText(
            kind = "llm_full_prompt",
            filenameHint = "turn_1_full_prompt.txt",
            content = rawContent,
            mimeType = "text/plain"
        )
        recorder.flush()

        assertThat(ref).isNotNull()
        val file = File(File(rootDir, runId), ref!!.path)
        val written = file.readText()
        assertThat(written).doesNotContain(rawToken)
        assertThat(written).doesNotContain(rawEmail)
        assertThat(written).contains("[REDACTED_TOKEN]")
        assertThat(written).contains("[REDACTED_EMAIL]")
    }

    @Test
    fun `encodeRedactedJson strips sensitive keys`() {
        val element = buildJsonObject {
            put("api_key", JsonPrimitive("sk-THIS_IS_A_SECRET_KEY_VALUE_XYZ123"))
            put("note", JsonPrimitive("ok"))
        }

        val encoded = artifacts.encodeRedactedJson(element)

        assertThat(encoded).doesNotContain("sk-THIS_IS_A_SECRET_KEY_VALUE_XYZ123")
        assertThat(encoded).contains("[REDACTED]")
        assertThat(encoded).contains("\"note\"")
    }
}
