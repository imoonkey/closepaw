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

        val warning = policy.detect(state)

        assertThat(warning).isNotNull()
        assertThat(warning?.severity).isEqualTo(LoopWarningSeverity.CRITICAL)
    }

    @Test
    fun `detect flags excessive scrolling`() {
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(5) { index ->
            state = state.advance(snapshot(label = "Inbox-$index"), previousAction = "scroll:up")
        }

        val warning = policy.detect(state)

        assertThat(warning).isNotNull()
        assertThat(warning?.message).contains("consecutive scroll")
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

        val warning = policy.detect(state)

        assertThat(warning).isNotNull()
        assertThat(warning?.severity).isEqualTo(LoopWarningSeverity.WARNING)
        assertThat(warning?.message).contains("Same action repeated")
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

        val warning = policy.detect(state)

        assertThat(warning).isNotNull()
        assertThat(warning?.severity).isEqualTo(LoopWarningSeverity.CRITICAL)
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
