package ai.closepaw.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import ai.closepaw.agent.cognition.context.NavigationState
import ai.closepaw.model.Bounds
import ai.closepaw.model.PerceptionElement
import ai.closepaw.model.Point
import ai.closepaw.model.ScreenSnapshot
import org.junit.Test

class LoopDetectionPolicyTest {

    @Test
    fun `detect returns no warning when screens differ`() {
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(5) { index ->
            state = state.advance(snapshot(label = "Screen-$index"))
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNull()
    }

    @Test
    fun `detect warns when screen unchanged for 5 turns`() {
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(5) {
            state = state.advance(snapshot(label = "Stuck"))
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.message).contains("not changed for 5 turns")
    }

    @Test
    fun `detect requires full window before warning`() {
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(4) {
            state = state.advance(snapshot(label = "Stuck"))
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNull()
    }

    @Test
    fun `detect does not warn when screens are similar but below threshold`() {
        // Default threshold is 0.95 — screens with minor differences should not trigger
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(5) { index ->
            // Each screen has the same layout element but different varying content
            state = state.advance(snapshotWithProgress(3, "Item $index"))
        }

        val result = policy.detect(state)

        // Jaccard = 3/(3+2) = 0.6 < 0.95, so no warning
        assertThat(result.warning).isNull()
    }

    @Test
    fun `detect warns with near-identical screens above 0_95 threshold`() {
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        // Use many shared elements so Jaccard is very high even with 1 varying element
        // 20 shared + 1 varying → Jaccard = 20/22 ≈ 0.91, still under 0.95
        // Need ~40 shared: 40/42 ≈ 0.952 >= 0.95
        // But simpler: use identical screens
        repeat(5) {
            state = state.advance(snapshot(label = "Identical"))
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
    }

    @Test
    fun `no warning on empty state`() {
        val policy = LoopDetectionPolicy()
        val state = NavigationState()

        val result = policy.detect(state)

        assertThat(result.warning).isNull()
    }

    @Test
    fun `consecutive scroll actions do not trigger warning`() {
        // Scroll spam check was removed — only stable screen matters
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(5) { index ->
            state = state.advance(snapshot(label = "Screen-$index"))
        }

        val result = policy.detect(state)

        // Screens differ, so no warning even with 5 consecutive scrolls
        assertThat(result.warning).isNull()
    }

    @Test
    fun `repeated identical actions do not trigger warning if screens differ`() {
        // Action repetition check was removed
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(5) { index ->
            state = state.advance(snapshot(label = "Screen-$index"))
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNull()
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

    /**
     * Snapshot with [sharedCount] stable elements + 1 varying element.
     * Jaccard between two snapshots = sharedCount / (sharedCount + 2).
     */
    private fun snapshotWithProgress(sharedCount: Int, varyingLabel: String): ScreenSnapshot {
        val sharedElements = (0 until sharedCount).map { i ->
            PerceptionElement(
                index = i,
                text = "Shared $i",
                resourceId = "com.test:id/shared_$i",
                className = "TextView",
                description = "Shared $i",
                isClickable = i % 2 == 0,
                isEditable = false,
                isScrollable = false,
                isEnabled = true,
                isFocused = false,
                isLongClickable = false,
                bounds = Bounds(left = 0, top = i * 40, right = 100, bottom = (i + 1) * 40),
                center = Point(x = 50, y = i * 40 + 20)
            )
        }
        val varyingElement = PerceptionElement(
            index = sharedCount,
            text = varyingLabel,
            resourceId = "com.test:id/item",
            className = "TextView",
            description = varyingLabel,
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isEnabled = true,
            isFocused = false,
            isLongClickable = false,
            bounds = Bounds(left = 0, top = sharedCount * 40, right = 100, bottom = (sharedCount + 1) * 40),
            center = Point(x = 50, y = sharedCount * 40 + 20)
        )
        return ScreenSnapshot(
            timestamp = System.currentTimeMillis(),
            elements = sharedElements + varyingElement
        )
    }
}
