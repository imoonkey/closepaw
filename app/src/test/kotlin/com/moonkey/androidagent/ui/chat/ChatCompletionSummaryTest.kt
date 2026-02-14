package com.moonkey.androidagent.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatCompletionSummaryTest {

    @Test
    fun `uses fallback when completion result is null`() {
        assertThat(completionSummary(null)).isEqualTo("Task completed")
    }

    @Test
    fun `uses fallback when completion result is blank`() {
        assertThat(completionSummary("   ")).isEqualTo("Task completed")
    }

    @Test
    fun `keeps non-empty completion result`() {
        assertThat(completionSummary("Opened YouTube and started playback"))
            .isEqualTo("Opened YouTube and started playback")
    }
}
