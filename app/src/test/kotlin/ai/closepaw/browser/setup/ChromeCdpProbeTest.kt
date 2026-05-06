package ai.closepaw.browser.setup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChromeCdpProbeTest {

    @Test
    fun `parse returns Bound when chrome devtools socket appears with pid suffix`() {
        val procNetUnix = """
            Num       RefCount Protocol Flags    Type St Inode Path
            ffff: 00000002 00000000 00010000 0001 01 12345 @chrome_devtools_remote_22310
            ffff: 00000002 00000000 00010000 0001 01 12346 @other_socket
        """.trimIndent()

        assertThat(ChromeCdpProbe.parse(procNetUnix)).isEqualTo(ChromeCdpProbe.Result.Bound)
    }

    @Test
    fun `parse returns Bound for exact socket name with no pid suffix`() {
        val procNetUnix = "ffff: 00000002 00000000 00010000 0001 01 12345 @chrome_devtools_remote"

        assertThat(ChromeCdpProbe.parse(procNetUnix)).isEqualTo(ChromeCdpProbe.Result.Bound)
    }

    @Test
    fun `parse returns NotBound when only similarly-named sockets are present`() {
        // webview_devtools_remote_<pid> is NOT chrome's socket — token match must reject it.
        // Substring matching would have wrongly accepted this.
        val procNetUnix = """
            ffff: 00000002 00000000 00010000 0001 01 12345 @webview_devtools_remote_42
            ffff: 00000002 00000000 00010000 0001 01 12346 @android.uirenderer
        """.trimIndent()

        assertThat(ChromeCdpProbe.parse(procNetUnix)).isEqualTo(ChromeCdpProbe.Result.NotBound)
    }

    @Test
    fun `parse returns NotBound on empty input`() {
        assertThat(ChromeCdpProbe.parse("")).isEqualTo(ChromeCdpProbe.Result.NotBound)
    }

    @Test
    fun `parse rejects pathname sockets — they are not abstract namespace`() {
        val procNetUnix =
            "ffff: 00000002 00000000 00010000 0001 01 12345 /tmp/chrome_devtools_remote"

        assertThat(ChromeCdpProbe.parse(procNetUnix)).isEqualTo(ChromeCdpProbe.Result.NotBound)
    }

    @Test
    fun `probe falls back to Shizuku shell when proc-net-unix is denied to app uid`() = runTest {
        val procFile = java.io.File.createTempFile("chrome_probe_denied", ".txt").apply {
            // Simulate a denied read by deleting the file — readText throws → fallback path.
            delete()
        }
        val shell = StaticShell(
            ShellRunner.ShellResult(
                exitCode = 0,
                stdout = "ffff: 00000002 00000000 00010000 0001 01 12345 @chrome_devtools_remote_22310\n",
            ),
        )
        val probe = ChromeCdpProbe(procNetUnix = procFile, shellRunner = shell)

        assertThat(probe.probe()).isEqualTo(ChromeCdpProbe.Result.Bound)
        assertThat(shell.calls).hasSize(1)
        assertThat(shell.calls.single().last()).contains("/proc/net/unix")
    }

    @Test
    fun `probe returns Unknown when both app-uid read and Shizuku fail`() = runTest {
        val procFile = java.io.File.createTempFile("chrome_probe_unknown", ".txt").apply { delete() }
        val shell = StaticShell(ShellRunner.ShellResult(exitCode = 1, stdout = ""))
        val probe = ChromeCdpProbe(procNetUnix = procFile, shellRunner = shell)

        assertThat(probe.probe()).isEqualTo(ChromeCdpProbe.Result.Unknown)
    }

    @Test
    fun `probe without Shizuku runner returns Unknown when app-uid read fails`() = runTest {
        val procFile = java.io.File.createTempFile("chrome_probe_no_shell", ".txt").apply {
            delete()
        }
        val probe = ChromeCdpProbe(procNetUnix = procFile, shellRunner = null)

        assertThat(probe.probe()).isEqualTo(ChromeCdpProbe.Result.Unknown)
    }

    private class StaticShell(private val response: ShellRunner.ShellResult) : ShellRunner {
        val calls = mutableListOf<Array<String>>()
        override suspend fun run(command: Array<String>): ShellRunner.ShellResult {
            calls += command
            return response
        }
    }
}
