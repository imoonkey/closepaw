package ai.closepaw.agent.cognition.context

import com.google.common.truth.Truth.assertThat
import ai.closepaw.model.Bounds
import ai.closepaw.model.PerceptionElement
import ai.closepaw.model.Point
import ai.closepaw.model.ScreenSnapshot
import org.junit.Test

class NavigationStateTest {

    @Test
    fun `advance keeps bounded history for signatures`() {
        var state = NavigationState()

        repeat(12) { index ->
            state = state.advance(snapshot = snapshot("Screen $index"))
        }

        assertThat(state.recentSignatures).hasSize(10)
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
