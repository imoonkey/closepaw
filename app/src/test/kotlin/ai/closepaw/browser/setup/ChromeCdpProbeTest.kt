package ai.closepaw.browser.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChromeCdpProbeTest {

    @Test
    fun `parse returns Bound when chrome devtools socket prefix present`() {
        val procNetUnix = """
            Num       RefCount Protocol Flags    Type St Inode Path
            ffff: 00000002 00000000 00010000 0001 01 12345 @chrome_devtools_remote_22310
            ffff: 00000002 00000000 00010000 0001 01 12346 @other_socket
        """.trimIndent()

        assertThat(ChromeCdpProbe.parse(procNetUnix)).isEqualTo(ChromeCdpProbe.Result.Bound)
    }

    @Test
    fun `parse returns Bound when socket has no pid suffix`() {
        val procNetUnix = "ffff: 00000002 00000000 00010000 0001 01 12345 @chrome_devtools_remote"

        assertThat(ChromeCdpProbe.parse(procNetUnix)).isEqualTo(ChromeCdpProbe.Result.Bound)
    }

    @Test
    fun `parse returns NotBound when socket name absent`() {
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
    fun `parse ignores socket names without leading at-sign — they are pathname sockets, not abstract`() {
        val procNetUnix =
            "ffff: 00000002 00000000 00010000 0001 01 12345 /tmp/chrome_devtools_remote"

        assertThat(ChromeCdpProbe.parse(procNetUnix)).isEqualTo(ChromeCdpProbe.Result.NotBound)
    }
}
