package com.moonkey.androidagent.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.agent.cognition.context.NavigationState
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import org.junit.Test

class LoopDetectionPolicyTest {

    @Test
    fun `detect flags repeated screen signatures`() {
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(3) {
            state = state.advance(snapshot(label = "Inbox"), previousAction = null)
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.CRITICAL)
        assertThat(result.escalation).isEqualTo(EscalationLevel.ADVISORY)
    }

    @Test
    fun `detect flags excessive scrolling`() {
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(5) { index ->
            state = state.advance(snapshot(label = "Inbox-$index"), previousAction = "scroll:up")
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.message).contains("consecutive scroll")
    }

    @Test
    fun `detect flags repeated identical action`() {
        val policy =
            LoopDetectionPolicy(
                LoopDetectionConfig(
                    repeatedScreenWindow = 4
                )
            )
        var state = NavigationState()

        repeat(3) { index ->
            state = state.advance(snapshot(label = "Screen-$index"), previousAction = "mobile_action:click")
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.WARNING)
        assertThat(result.warning?.message).contains("Same action repeated")
    }

    @Test
    fun `detect treats near-identical screens as repeated`() {
        val policy =
            LoopDetectionPolicy(
                LoopDetectionConfig(
                    similarityThreshold = 0.80
                )
            )
        var state = NavigationState()

        state = state.advance(snapshot(label = "Inbox"), previousAction = null)
        state = state.advance(snapshot(label = "Inbox "), previousAction = null)
        state = state.advance(snapshot(label = "Inbox  "), previousAction = null)

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.CRITICAL)
    }

    @Test
    fun `escalation reaches BLOCK after consecutiveLoopTurns threshold`() {
        val policy = LoopDetectionPolicy(
            LoopDetectionConfig(blockEscalationThreshold = 2)
        )
        var state = NavigationState()

        // Build up 3 identical screens to trigger CRITICAL warning
        repeat(3) {
            state = state.advance(snapshot(label = "Stuck"), previousAction = null)
        }
        // Simulate that 2 consecutive CRITICAL loop turns have already occurred
        state = state.copy(consecutiveLoopTurns = 2)

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.escalation).isEqualTo(EscalationLevel.BLOCK)
    }

    @Test
    fun `escalation reaches FORCE_COMPLETE after high consecutiveLoopTurns`() {
        val policy = LoopDetectionPolicy(
            LoopDetectionConfig(forceCompleteEscalationThreshold = 5)
        )
        var state = NavigationState()

        repeat(3) {
            state = state.advance(snapshot(label = "Stuck"), previousAction = null)
        }
        state = state.copy(consecutiveLoopTurns = 5)

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.escalation).isEqualTo(EscalationLevel.FORCE_COMPLETE)
    }

    @Test
    fun `no escalation when no warning detected`() {
        val policy = LoopDetectionPolicy()
        val state = NavigationState()

        val result = policy.detect(state)

        assertThat(result.warning).isNull()
        assertThat(result.escalation).isEqualTo(EscalationLevel.NONE)
    }

    private fun snapshot(label: String): ScreenSnapshot {
        return ScreenSnapshot(
            timestamp = System.currentTimeMillis(),
            elements =
                listOf(
                    PerceptionElement(
                        index = 0,
                        text = label,
                        resourceId = "com.test:id/title",
                        className = "TextView",
                        description = label,
                        isClickable = true,
                        isEditable = false,
                        isScrollable = false,
                        isEnabled = true,
                        isFocused = false,
                        isLongClickable = false,
                        bounds = Bounds(left = 0, top = 0, right = 100, bottom = 40),
                        center = Point(x = 50, y = 20)
                    )
                )
        )
    }
}
