package com.moonkey.androidagent.ui.overlay.model

import com.google.common.truth.Truth.assertThat
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
        assertThat(sanitizeThought("打开淘宝")).isEqualTo("打开淘宝")
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

    @Test
    fun `displayThought returns thought for Running`() {
        val mode = CapsuleMode.Running("打开淘宝")
        assertThat(mode.displayThought()).isEqualTo("打开淘宝")
    }

    @Test
    fun `displayThought returns lastThought for Takeover`() {
        val mode = CapsuleMode.Takeover("最后的想法")
        assertThat(mode.displayThought()).isEqualTo("最后的想法")
    }

    @Test
    fun `displayThought returns null for Hidden`() {
        assertThat(CapsuleMode.Hidden.displayThought()).isNull()
    }

    @Test
    fun `displayThought returns message for Done`() {
        val mode = CapsuleMode.Done("任务完成")
        assertThat(mode.displayThought()).isEqualTo("✓ 任务完成")
    }

    @Test
    fun `displayThought returns message for Error`() {
        val mode = CapsuleMode.Error("网络错误")
        assertThat(mode.displayThought()).isEqualTo("⚠ 网络错误")
    }

    @Test
    fun `SupplementInput delegates to previousMode`() {
        val running = CapsuleMode.Running("搜索中")
        val supplement = CapsuleMode.SupplementInput(running)
        assertThat(supplement.displayThought()).isEqualTo("搜索中")
    }
}
