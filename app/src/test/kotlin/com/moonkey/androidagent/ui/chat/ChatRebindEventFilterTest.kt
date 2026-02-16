package com.moonkey.androidagent.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatRebindEventFilterTest {

    @Test
    fun `accepts all events when no replay cutoff provided`() {
        assertThat(shouldHandleReboundEvent(eventTimestamp = 100L, replayCutoffTimestamp = null))
                .isTrue()
    }

    @Test
    fun `filters replayed event at or before cutoff`() {
        assertThat(shouldHandleReboundEvent(eventTimestamp = 100L, replayCutoffTimestamp = 100L))
                .isFalse()
        assertThat(shouldHandleReboundEvent(eventTimestamp = 99L, replayCutoffTimestamp = 100L))
                .isFalse()
    }

    @Test
    fun `keeps new events after cutoff`() {
        assertThat(shouldHandleReboundEvent(eventTimestamp = 101L, replayCutoffTimestamp = 100L))
                .isTrue()
    }
}
