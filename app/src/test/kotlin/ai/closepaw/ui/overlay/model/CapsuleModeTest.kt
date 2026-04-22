package ai.closepaw.ui.overlay.model

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.compactThought
import org.junit.Test

class CapsuleModeTest {

    @Test
    fun `compactThought trims whitespace`() {
        assertThat(compactThought("  hello world  ")).isEqualTo("hello world")
    }

    @Test
    fun `compactThought truncates at 80 chars`() {
        val long = "a".repeat(100)
        val result = compactThought(long)
        assertThat(result).hasLength(83)  // 80 + "..."
        assertThat(result).endsWith("...")
    }

    @Test
    fun `compactThought preserves short text`() {
        assertThat(compactThought("Open Taobao")).isEqualTo("Open Taobao")
    }

    @Test
    fun `compactThought handles empty string`() {
        assertThat(compactThought("")).isEqualTo("")
    }

    @Test
    fun `compactThought handles whitespace only`() {
        assertThat(compactThought("   ")).isEqualTo("")
    }

    @Test
    fun `compactThought handles exactly 80 chars`() {
        val exact = "a".repeat(80)
        assertThat(compactThought(exact)).isEqualTo(exact)
    }
}
