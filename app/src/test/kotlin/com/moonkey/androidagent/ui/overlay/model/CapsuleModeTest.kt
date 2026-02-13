package com.moonkey.androidagent.ui.overlay.model

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.protocol.sanitizeThought
import org.junit.Test

class CapsuleModeTest {

    @Test
    fun `sanitizeThought trims whitespace`() {
        assertThat(sanitizeThought("  hello world  ")).isEqualTo("hello world")
    }

    @Test
    fun `sanitizeThought truncates at 40 chars`() {
        val long = "a".repeat(50)
        val result = sanitizeThought(long)
        assertThat(result).hasLength(43)  // 40 + "..."
        assertThat(result).endsWith("...")
    }

    @Test
    fun `sanitizeThought preserves short text`() {
        assertThat(sanitizeThought("Open Taobao")).isEqualTo("Open Taobao")
    }

    @Test
    fun `sanitizeThought handles empty string`() {
        assertThat(sanitizeThought("")).isEqualTo("")
    }

    @Test
    fun `sanitizeThought handles whitespace only`() {
        assertThat(sanitizeThought("   ")).isEqualTo("")
    }

    @Test
    fun `sanitizeThought handles exactly 40 chars`() {
        val exact = "a".repeat(40)
        assertThat(sanitizeThought(exact)).isEqualTo(exact)
    }
}
