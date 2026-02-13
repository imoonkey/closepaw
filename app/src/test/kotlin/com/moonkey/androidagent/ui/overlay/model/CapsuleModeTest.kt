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
    fun `displayThought returns null for WaitingForInput`() {
        val mode = CapsuleMode.WaitingForInput(question = "选哪个?", callId = "c1")
        assertThat(mode.displayThought()).isNull()
    }

    @Test
    fun `isExpanded returns true for WaitingForInput`() {
        val mode = CapsuleMode.WaitingForInput(question = "选哪个?", callId = "c1")
        assertThat(mode.isExpanded()).isTrue()
    }

    @Test
    fun `isExpanded returns false for Running`() {
        assertThat(CapsuleMode.Running("test").isExpanded()).isFalse()
    }

    @Test
    fun `displayThought returns lastThought for TakeoverPending`() {
        val mode = CapsuleMode.TakeoverPending("正在切换")
        assertThat(mode.displayThought()).isEqualTo("正在切换")
    }

    @Test
    fun `displayThought returns null for WaitingForAction`() {
        val mode = CapsuleMode.WaitingForAction(instruction = "请打开设置", callId = "c2")
        assertThat(mode.displayThought()).isNull()
    }

    @Test
    fun `isExpanded returns true for WaitingForAction`() {
        val mode = CapsuleMode.WaitingForAction(instruction = "请打开设置", callId = "c2")
        assertThat(mode.isExpanded()).isTrue()
    }

    @Test
    fun `isExpanded returns false for Done`() {
        assertThat(CapsuleMode.Done("ok").isExpanded()).isFalse()
    }

    @Test
    fun `isExpanded returns false for Hidden`() {
        assertThat(CapsuleMode.Hidden.isExpanded()).isFalse()
    }
}
