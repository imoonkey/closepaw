package com.moonkey.androidagent.tool.impl

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Execution-path coverage for ShellTool.ShellInvocation.execute().
 * These tests shell out to the host JVM's /bin/sh, so they only run on Unix-like hosts.
 */
class ShellToolExecutionTest {

    private val isUnix = !System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Test
    fun `command timeout returns timeout-specific failure`() = runTest {
        assumeTrue(isUnix)
        val tool = ShellTool()
        val invocation = tool.createInvocation(JSONObject().put("command", "sleep 15"))

        val result = invocation.execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
        val error = (result as ToolExecutionResult.Failure).error
        assertThat(error).contains("timed out")
        assertThat(error).contains("10s")
    }

    @Test
    fun `long output is truncated to configured limit`() = runTest {
        assumeTrue(isUnix)
        val tool = ShellTool()
        // 5000 'a's > MAX_OUTPUT_CHARS (4096), no shell metacharacters.
        val filler = "a".repeat(5000)
        val invocation = tool.createInvocation(JSONObject().put("command", "echo $filler"))

        val result = invocation.execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).contains("[output truncated at 4096 chars]")
        // Count the longest contiguous run of 'a' — that's the captured stdout, bounded by MAX_OUTPUT_CHARS.
        val longestARun = Regex("a+").findAll(output).maxOf { it.value.length }
        assertThat(longestARun).isEqualTo(4096)
    }

    @Test
    fun `formatted output includes exit code`() = runTest {
        assumeTrue(isUnix)
        val tool = ShellTool()
        // `false` is a standard utility that exits 1 with no output.
        val invocation = tool.createInvocation(JSONObject().put("command", "false"))

        val result = invocation.execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).startsWith("exit=1")
    }

    @Test
    fun `exit code zero is reflected in output`() = runTest {
        assumeTrue(isUnix)
        val tool = ShellTool()
        val invocation = tool.createInvocation(JSONObject().put("command", "echo hello"))

        val result = invocation.execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).startsWith("exit=0")
        assertThat(output).contains("hello")
        assertThat(output).doesNotContain("[output truncated")
    }

    @Test
    fun `pre-cancelled context short-circuits before running process`() = runTest {
        val tool = ShellTool()
        // Use a command that would otherwise take a long time; short-circuit means it never runs.
        val invocation = tool.createInvocation(JSONObject().put("command", "sleep 30"))

        val start = System.currentTimeMillis()
        val result = invocation.execute(buildContext(cancelled = true))
        val elapsedMs = System.currentTimeMillis() - start

        assertThat(result).isInstanceOf(ToolExecutionResult.Cancelled::class.java)
        assertThat((result as ToolExecutionResult.Cancelled).reason).contains("Cancelled before execution")
        // Short-circuit must return well under the command's runtime / timeout.
        assertThat(elapsedMs).isLessThan(2_000L)
    }

    private fun buildContext(cancelled: Boolean = false): ToolExecutionContext =
        object : ToolExecutionContext {
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = cancelled
        }
}
