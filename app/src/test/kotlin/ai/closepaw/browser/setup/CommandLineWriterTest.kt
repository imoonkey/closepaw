package ai.closepaw.browser.setup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CommandLineWriterTest {

    @Test
    fun `ensureWritten skips write when current content already matches desired`() = runTest {
        val recorder = RecordingRunner()
        recorder.responses += CommandLineWriter.ShellResult(
            exitCode = 0,
            stdout = CommandLineWriter.DESIRED_CONTENT,
        )
        val writer = CommandLineWriter(shell = recorder)

        val outcome = writer.ensureWritten()

        assertThat(outcome).isEqualTo(CommandLineWriter.Outcome.AlreadyCorrect)
        assertThat(recorder.calls).hasSize(1)
        assertThat(recorder.calls.single()).asList().containsAtLeast("sh", "-c").inOrder()
        assertThat(recorder.calls.single().last()).contains("cat ${CommandLineWriter.TARGET_PATH}")
    }

    @Test
    fun `ensureWritten writes when current content is missing`() = runTest {
        val recorder = RecordingRunner()
        // First call (cat): file missing → exit non-zero, empty stdout
        recorder.responses += CommandLineWriter.ShellResult(exitCode = 1, stdout = "")
        // Second call (echo write): success
        recorder.responses += CommandLineWriter.ShellResult(exitCode = 0, stdout = "")
        val writer = CommandLineWriter(shell = recorder)

        val outcome = writer.ensureWritten()

        assertThat(outcome).isEqualTo(CommandLineWriter.Outcome.Written)
        assertThat(recorder.calls).hasSize(2)
        assertThat(recorder.calls[1].last())
            .contains("> ${CommandLineWriter.TARGET_PATH}")
        assertThat(recorder.calls[1].last())
            .contains(CommandLineWriter.DESIRED_CONTENT)
    }

    @Test
    fun `ensureWritten writes when current content differs from desired`() = runTest {
        val recorder = RecordingRunner()
        recorder.responses += CommandLineWriter.ShellResult(
            exitCode = 0,
            stdout = "_ --some-other-flag",
        )
        recorder.responses += CommandLineWriter.ShellResult(exitCode = 0, stdout = "")
        val writer = CommandLineWriter(shell = recorder)

        val outcome = writer.ensureWritten()

        assertThat(outcome).isEqualTo(CommandLineWriter.Outcome.Written)
        assertThat(recorder.calls).hasSize(2)
    }

    @Test
    fun `ensureWritten reports Failed when write exits non-zero`() = runTest {
        val recorder = RecordingRunner()
        recorder.responses += CommandLineWriter.ShellResult(exitCode = 1, stdout = "")
        recorder.responses += CommandLineWriter.ShellResult(exitCode = 1, stdout = "")
        val writer = CommandLineWriter(shell = recorder)

        assertThat(writer.ensureWritten()).isEqualTo(CommandLineWriter.Outcome.Failed)
    }

    @Test
    fun `ensureWritten tolerates trailing newline differences in current content`() = runTest {
        val recorder = RecordingRunner()
        // echo always appends \n, so the stored file ends with one. Idempotency must trim.
        recorder.responses += CommandLineWriter.ShellResult(
            exitCode = 0,
            stdout = CommandLineWriter.DESIRED_CONTENT + "\n",
        )
        val writer = CommandLineWriter(shell = recorder)

        assertThat(writer.ensureWritten()).isEqualTo(CommandLineWriter.Outcome.AlreadyCorrect)
        assertThat(recorder.calls).hasSize(1)
    }

    private class RecordingRunner : CommandLineWriter.ShellRunner {
        val calls = mutableListOf<Array<String>>()
        val responses = ArrayDeque<CommandLineWriter.ShellResult>()

        override suspend fun run(command: Array<String>): CommandLineWriter.ShellResult {
            calls += command
            return responses.removeFirstOrNull()
                ?: CommandLineWriter.ShellResult(exitCode = -1, stdout = "")
        }
    }
}
