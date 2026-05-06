package ai.closepaw.browser.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.Test

/**
 * JVM tests for [ShizukuShellRunner.runDrained]. Runs a real `bash`/`sh` child process via
 * [ProcessBuilder] so we exercise the exact drain+wait code path that production hits through
 * Shizuku, just without the Shizuku binder.
 *
 * Pre-fix, [ShizukuShellRunner] read stdout AFTER `waitFor` returned. Any command emitting more
 * than the OS pipe buffer (~64 KiB on Linux) blocked inside the child's `write()` until the
 * 5-second timeout fired and then returned an empty stdout. This is the regression test for
 * that — a `seq 1 100000` (~588 KiB) must come back fully within the timeout.
 */
class ShellRunnerTest {

    @Test
    fun `runDrained returns full stdout for output larger than pipe buffer`() {
        val sh = findShell()
        Assume.assumeTrue("no POSIX shell on PATH", sh != null)

        val process = ProcessBuilder(sh!!, "-c", "seq 1 100000").start()

        val result = ShizukuShellRunner.runDrained(process, timeoutSec = 10L)

        // Pre-fix this assertion failed: exitCode was -1 (timeout) and stdout was empty.
        assertThat(result.exitCode).isEqualTo(0)
        val lines = result.stdout.lineSequence().filter { it.isNotEmpty() }.toList()
        assertThat(lines.size).isEqualTo(100_000)
        assertThat(lines.first()).isEqualTo("1")
        assertThat(lines.last()).isEqualTo("100000")
    }

    @Test
    fun `runDrained returns small stdout`() {
        val sh = findShell()
        Assume.assumeTrue("no POSIX shell on PATH", sh != null)

        val process = ProcessBuilder(sh!!, "-c", "echo hello").start()

        val result = ShizukuShellRunner.runDrained(process, timeoutSec = 5L)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout.trim()).isEqualTo("hello")
    }

    @Test
    fun `runDrained reports non-zero exit code`() {
        val sh = findShell()
        Assume.assumeTrue("no POSIX shell on PATH", sh != null)

        val process = ProcessBuilder(sh!!, "-c", "exit 42").start()

        val result = ShizukuShellRunner.runDrained(process, timeoutSec = 5L)

        assertThat(result.exitCode).isEqualTo(42)
        assertThat(result.stdout).isEmpty()
    }

    @Test
    fun `runDrained drains stderr in parallel without blocking stdout`() {
        val sh = findShell()
        Assume.assumeTrue("no POSIX shell on PATH", sh != null)

        // Emit ~150 KiB to BOTH stdout and stderr concurrently. Pre-fix, even with the stdout
        // drain in place, leaving stderr undrained on a child that writes a lot of stderr could
        // block the child indefinitely (write to stderr blocks once stderr's pipe buffer fills).
        val script = "seq 1 30000 & seq 1 30000 1>&2; wait"
        val process = ProcessBuilder(sh!!, "-c", script).start()

        val result = ShizukuShellRunner.runDrained(process, timeoutSec = 10L)

        assertThat(result.exitCode).isEqualTo(0)
        val stdoutLines = result.stdout.lineSequence().filter { it.isNotEmpty() }.toList()
        assertThat(stdoutLines.size).isEqualTo(30_000)
    }

    private fun findShell(): String? =
        listOf("/bin/bash", "/usr/bin/bash", "/bin/sh", "/usr/bin/sh")
            .firstOrNull { java.io.File(it).canExecute() }
}
