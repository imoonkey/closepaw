package com.moonkey.androidagent.agent.cognition.context

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import org.junit.Test

class NavigationStateTest {

    @Test
    fun `advance keeps bounded history for signatures and actions`() {
        var state = NavigationState()

        repeat(12) { index ->
            state =
                state.advance(
                    snapshot = snapshot("Screen $index"),
                    previousAction = "mobile_action:click"
                )
        }

        assertThat(state.recentSignatures).hasSize(10)
        assertThat(state.recentActions).hasSize(5)
        assertThat(state.recentSignatures.last().fingerprint)
            .isEqualTo(snapshot("Screen 11").toStateSignature().fingerprint)
    }

    @Test
    fun `advance increments and resets consecutive scroll actions`() {
        var state = NavigationState()
        state = state.advance(snapshot("A"), previousAction = "scroll:up")
        state = state.advance(snapshot("B"), previousAction = "scroll:down")
        assertThat(state.consecutiveScrollActions).isEqualTo(2)

        state = state.advance(snapshot("C"), previousAction = "mobile_action:click")
        assertThat(state.consecutiveScrollActions).isEqualTo(0)
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

private fun ScreenSnapshot.toStateSignature(): ScreenSignature {
    return NavigationState()
        .advance(snapshot = this, previousAction = null)
        .recentSignatures
        .single()
}
