package com.moonkey.androidagent.tool.action

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import org.junit.Test

class TargetResolverTest {

    @Test
    fun `resolve element_index returns element center when no bottom nav overlap`() {
        val target = element(index = 1, text = "Play", bounds = Bounds(100, 400, 500, 700))
        val snapshot = snapshotOf(target)

        val resolved = TargetResolver.resolve(Target.ElementIndex(1), snapshot)

        assertThat(resolved).isEqualTo(
            TargetResolver.ResolveResult.Resolved(point = Point(300, 550))
        )
    }

    @Test
    fun `resolve element_index shifts tap above bottom nav when target overlaps nav strip`() {
        val target =
            element(
                index = 14,
                text = "Video row",
                bounds = Bounds(0, 2353, 1264, 2800)
            )
        val nav = listOf(
            element(index = 15, text = "Home", bounds = Bounds(0, 2576, 252, 2744)),
            element(index = 16, text = "Shorts", bounds = Bounds(252, 2576, 505, 2744)),
            element(index = 17, text = "Create", bounds = Bounds(505, 2576, 758, 2744)),
            element(index = 18, text = "Subscriptions", bounds = Bounds(758, 2576, 1011, 2744)),
            element(index = 19, text = "You", bounds = Bounds(1011, 2576, 1264, 2744))
        )
        val snapshot = snapshotOf(target, *nav.toTypedArray())

        val resolved = TargetResolver.resolve(Target.ElementIndex(14), snapshot)

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.Resolved::class.java)
        val point = (resolved as TargetResolver.ResolveResult.Resolved).point
        assertThat(point.x).isEqualTo(632)
        assertThat(point.y).isLessThan(2576)
        assertThat(point.y).isAtLeast(2354)
    }

    @Test
    fun `resolve element_index keeps nav button center unchanged`() {
        val overlappingContent =
            element(
                index = 14,
                text = "Video row",
                bounds = Bounds(0, 2353, 1264, 2800)
            )
        val create = element(index = 17, text = "Create", bounds = Bounds(505, 2576, 758, 2744))
        val peers = listOf(
            element(index = 15, text = "Home", bounds = Bounds(0, 2576, 252, 2744)),
            element(index = 16, text = "Shorts", bounds = Bounds(252, 2576, 505, 2744)),
            element(index = 18, text = "Subscriptions", bounds = Bounds(758, 2576, 1011, 2744))
        )
        val snapshot = snapshotOf(overlappingContent, create, *peers.toTypedArray())

        val resolved = TargetResolver.resolve(Target.ElementIndex(17), snapshot)

        assertThat(resolved).isEqualTo(
            TargetResolver.ResolveResult.Resolved(point = Point(631, 2660))
        )
    }

    @Test
    fun `resolve element_index returns warning when center is occluded`() {
        val target = element(index = 20, text = "Large card", bounds = Bounds(100, 100, 900, 900))
        val blockers = listOf(
            element(index = 21, text = "OverlayA", bounds = Bounds(120, 120, 880, 320)),
            element(index = 22, text = "OverlayB", bounds = Bounds(120, 320, 880, 580)),
            element(index = 23, text = "OverlayC", bounds = Bounds(120, 580, 880, 880))
        )
        val snapshot = snapshotOf(target, *blockers.toTypedArray())

        val resolved = TargetResolver.resolve(Target.ElementIndex(20), snapshot)

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.Resolved::class.java)
        val result = resolved as TargetResolver.ResolveResult.Resolved
        assertThat(result.point).isNotEqualTo(Point(500, 500))
        assertThat(result.warnings).contains("Element center likely occluded; using offset point")
    }

    @Test
    fun `resolve element_index returns not found for missing index`() {
        val snapshot = snapshotOf(element(index = 1, text = "Play", bounds = Bounds(100, 400, 500, 700)))

        val resolved = TargetResolver.resolve(Target.ElementIndex(9), snapshot)

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.NotFound::class.java)
    }

    private fun snapshotOf(vararg elements: PerceptionElement): ScreenSnapshot {
        return ScreenSnapshot(
            timestamp = 0L,
            elements = elements.toList()
        )
    }

    private fun element(index: Int, text: String, bounds: Bounds): PerceptionElement {
        return PerceptionElement(
            index = index,
            text = text,
            resourceId = "",
            className = "Button",
            description = "",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isEnabled = true,
            isFocused = false,
            isLongClickable = false,
            bounds = bounds,
            center = Point(bounds.centerX, bounds.centerY)
        )
    }
}
