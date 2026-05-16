package ai.closepaw.tool.action

import com.google.common.truth.Truth.assertThat
import ai.closepaw.model.Bounds
import ai.closepaw.model.PerceptionElement
import ai.closepaw.model.Point
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.SemanticTargetHint
import org.junit.Test

class TargetResolverTest {

    @Test
    fun `resolve element_index returns element center when no bottom nav overlap`() {
        val bounds = Bounds(100, 400, 500, 700)
        val target = element(index = 1, text = "Play", bounds = bounds)
        val snapshot = snapshotOf(target)

        val resolved = TargetResolver.resolve(Target.ElementIndex(1), snapshot)

        assertThat(resolved).isEqualTo(
            TargetResolver.ResolveResult.Resolved(
                point = Point(300, 550),
                bounds = bounds,
                semanticHint = hintFor("Play", bounds)
            )
        )
    }

    @Test
    fun `resolve element_index keeps target center when overlaps bottom nav strip`() {
        val targetBounds = Bounds(0, 2353, 1264, 2800)
        val target =
            element(
                index = 14,
                text = "Video row",
                bounds = targetBounds
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

        assertThat(resolved).isEqualTo(
            TargetResolver.ResolveResult.Resolved(
                point = Point(632, 2576),
                bounds = targetBounds,
                semanticHint = hintFor("Video row", targetBounds)
            )
        )
    }

    @Test
    fun `resolve element_index keeps nav button center unchanged`() {
        val overlappingContent =
            element(
                index = 14,
                text = "Video row",
                bounds = Bounds(0, 2353, 1264, 2800)
            )
        val createBounds = Bounds(505, 2576, 758, 2744)
        val create = element(index = 17, text = "Create", bounds = createBounds)
        val peers = listOf(
            element(index = 15, text = "Home", bounds = Bounds(0, 2576, 252, 2744)),
            element(index = 16, text = "Shorts", bounds = Bounds(252, 2576, 505, 2744)),
            element(index = 18, text = "Subscriptions", bounds = Bounds(758, 2576, 1011, 2744))
        )
        val snapshot = snapshotOf(overlappingContent, create, *peers.toTypedArray())

        val resolved = TargetResolver.resolve(Target.ElementIndex(17), snapshot)

        assertThat(resolved).isEqualTo(
            TargetResolver.ResolveResult.Resolved(
                point = Point(631, 2660),
                bounds = createBounds,
                semanticHint = hintFor("Create", createBounds)
            )
        )
    }

    @Test
    fun `resolve element_index keeps center even when smaller clickables overlap center`() {
        val targetBounds = Bounds(100, 100, 900, 900)
        val target = element(index = 20, text = "Large card", bounds = targetBounds)
        val blockers = listOf(
            element(index = 21, text = "OverlayA", bounds = Bounds(120, 120, 880, 320)),
            element(index = 22, text = "OverlayB", bounds = Bounds(120, 320, 880, 580)),
            element(index = 23, text = "OverlayC", bounds = Bounds(120, 580, 880, 880))
        )
        val snapshot = snapshotOf(target, *blockers.toTypedArray())

        val resolved = TargetResolver.resolve(Target.ElementIndex(20), snapshot)

        assertThat(resolved).isEqualTo(
            TargetResolver.ResolveResult.Resolved(
                point = Point(500, 500),
                bounds = targetBounds,
                semanticHint = hintFor("Large card", targetBounds)
            )
        )
    }

    @Test
    fun `resolve element_index returns center with right-edge neighbor`() {
        val targetBounds = Bounds(954, 128, 1080, 254)
        val target = element(index = 7, text = "More options", bounds = targetBounds)
        val edgeNeighbor = element(index = 6, text = "Grid view", bounds = Bounds(890, 128, 1017, 241))
        val snapshot = snapshotOf(target, edgeNeighbor)

        val resolved = TargetResolver.resolve(Target.ElementIndex(7), snapshot)

        assertThat(resolved).isEqualTo(
            TargetResolver.ResolveResult.Resolved(
                point = Point(1017, 191),
                bounds = targetBounds,
                semanticHint = hintFor("More options", targetBounds)
            )
        )
    }

    @Test
    fun `resolve element_index returns not found for missing index`() {
        val snapshot = snapshotOf(element(index = 1, text = "Play", bounds = Bounds(100, 400, 500, 700)))

        val resolved = TargetResolver.resolve(Target.ElementIndex(9), snapshot)

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.NotFound::class.java)
    }

    @Test
    fun `resolve text matches prompt text with matching normalization`() {
        val bounds = Bounds(100, 400, 500, 700)
        val snapshot = snapshotOf(element(index = 1, text = " Save ", bounds = bounds))

        val resolved = TargetResolver.resolve(Target.Text("save", 0), snapshot)

        assertThat(resolved).isEqualTo(
            TargetResolver.ResolveResult.Resolved(
                point = Point(300, 550),
                bounds = bounds,
                semanticHint = hintFor(" Save ", bounds)
            )
        )
    }

    @Test
    fun `resolve text matches hint when no prompt text matches`() {
        val bounds = Bounds(100, 400, 500, 700)
        val snapshot = snapshotOf(
            element(
                index = 1,
                text = "",
                description = "",
                hintText = "Search",
                bounds = bounds
            )
        )

        val resolved = TargetResolver.resolve(Target.Text("Search", 0), snapshot)

        assertThat(resolved).isEqualTo(
            TargetResolver.ResolveResult.Resolved(
                point = Point(300, 550),
                bounds = bounds,
                semanticHint = hintFor("", bounds)
            )
        )
    }

    // ---------- Coordinate-hint normalization (Codex dual target) ----------

    @Test
    fun `semantic resolves with hint inside bounds uses element center`() {
        val bounds = Bounds(100, 400, 500, 700)
        val target = element(index = 1, text = "Play", bounds = bounds)
        val snapshot = snapshotOf(target)

        val resolved = TargetResolver.resolve(
            Target.ElementIndex(1, Target.Coordinate(450, 650)),
            snapshot
        )

        assertThat(resolved).isEqualTo(
            TargetResolver.ResolveResult.Resolved(
                point = Point(300, 550),
                bounds = bounds,
                semanticHint = hintFor("Play", bounds)
            )
        )
        val r = resolved as TargetResolver.ResolveResult.Resolved
        assertThat(r.warnings).isEmpty()
        assertThat(r.coordinateFallback).isFalse()
    }

    @Test
    fun `hint at right-minus-1 bottom-minus-1 is inside bounds`() {
        val bounds = Bounds(100, 400, 500, 700)
        val snapshot = snapshotOf(element(index = 1, text = "Play", bounds = bounds))

        val resolved = TargetResolver.resolve(
            Target.ElementIndex(1, Target.Coordinate(499, 699)),
            snapshot
        )

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.Resolved::class.java)
        val r = resolved as TargetResolver.ResolveResult.Resolved
        assertThat(r.point).isEqualTo(Point(300, 550))
        assertThat(r.coordinateFallback).isFalse()
    }

    @Test
    fun `hint at right bottom edge is outside bounds and ambiguous`() {
        val bounds = Bounds(100, 400, 500, 700)
        val snapshot = snapshotOf(element(index = 1, text = "Play", bounds = bounds))

        val resolved = TargetResolver.resolve(
            Target.ElementIndex(1, Target.Coordinate(500, 700)),
            snapshot
        )

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.Ambiguous::class.java)
        val ambig = resolved as TargetResolver.ResolveResult.Ambiguous
        assertThat(ambig.reason).contains("Ambiguous")
        assertThat(ambig.reason).contains("(500, 700)")
    }

    @Test
    fun `semantic miss with hint falls back to coordinate with warning`() {
        val snapshot = snapshotOf(element(index = 1, text = "Play", bounds = Bounds(100, 400, 500, 700)))

        val resolved = TargetResolver.resolve(
            Target.ElementIndex(99, Target.Coordinate(200, 300)),
            snapshot
        )

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.Resolved::class.java)
        val r = resolved as TargetResolver.ResolveResult.Resolved
        assertThat(r.point).isEqualTo(Point(200, 300))
        assertThat(r.bounds).isNull()
        assertThat(r.semanticHint).isNull()
        assertThat(r.coordinateFallback).isTrue()
        assertThat(r.warnings).hasSize(1)
        assertThat(r.warnings.first()).contains("coordinate fallback")
    }

    @Test
    fun `semantic miss without hint returns not found`() {
        val snapshot = snapshotOf(element(index = 1, text = "Play", bounds = Bounds(100, 400, 500, 700)))

        val resolved = TargetResolver.resolve(Target.ElementIndex(99), snapshot)

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.NotFound::class.java)
    }

    @Test
    fun `text target with hint inside bounds uses element center`() {
        val bounds = Bounds(100, 400, 500, 700)
        val snapshot = snapshotOf(element(index = 1, text = "Save", bounds = bounds))

        val resolved = TargetResolver.resolve(
            Target.Text("Save", 0, Target.Coordinate(300, 550)),
            snapshot
        )

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.Resolved::class.java)
        val r = resolved as TargetResolver.ResolveResult.Resolved
        assertThat(r.point).isEqualTo(Point(300, 550))
        assertThat(r.coordinateFallback).isFalse()
        assertThat(r.warnings).isEmpty()
    }

    @Test
    fun `text target with hint outside bounds is ambiguous`() {
        val snapshot = snapshotOf(element(index = 1, text = "Save", bounds = Bounds(100, 400, 500, 700)))

        val resolved = TargetResolver.resolve(
            Target.Text("Save", 0, Target.Coordinate(9000, 9000)),
            snapshot
        )

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.Ambiguous::class.java)
    }

    @Test
    fun `text miss with hint falls back to coordinate`() {
        val snapshot = snapshotOf(element(index = 1, text = "Save", bounds = Bounds(100, 400, 500, 700)))

        val resolved = TargetResolver.resolve(
            Target.Text("Nonexistent", 0, Target.Coordinate(123, 456)),
            snapshot
        )

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.Resolved::class.java)
        val r = resolved as TargetResolver.ResolveResult.Resolved
        assertThat(r.point).isEqualTo(Point(123, 456))
        assertThat(r.coordinateFallback).isTrue()
        assertThat(r.bounds).isNull()
        assertThat(r.semanticHint).isNull()
    }

    @Test
    fun `coordinate target is unchanged`() {
        val snapshot = snapshotOf(element(index = 1, text = "Play", bounds = Bounds(100, 400, 500, 700)))

        val resolved = TargetResolver.resolve(Target.Coordinate(42, 73), snapshot)

        assertThat(resolved).isEqualTo(
            TargetResolver.ResolveResult.Resolved(point = Point(42, 73))
        )
        val r = resolved as TargetResolver.ResolveResult.Resolved
        assertThat(r.coordinateFallback).isFalse()
    }

    @Test
    fun `resolve text does not match resource id suffix when no visible text exists`() {
        val bounds = Bounds(100, 400, 500, 700)
        val snapshot = snapshotOf(
            element(
                index = 1,
                text = "",
                description = "",
                resourceId = "pkg:id/icon_thumb",
                bounds = bounds
            )
        )

        val resolved = TargetResolver.resolve(Target.Text("icon_thumb", 0), snapshot)

        assertThat(resolved).isInstanceOf(TargetResolver.ResolveResult.NotFound::class.java)
    }

    private fun snapshotOf(vararg elements: PerceptionElement): ScreenSnapshot {
        return ScreenSnapshot(
            timestamp = 0L,
            elements = elements.toList()
        )
    }

    private fun element(
        index: Int,
        text: String,
        bounds: Bounds,
        description: String = "",
        hintText: String = "",
        resourceId: String = ""
    ): PerceptionElement {
        return PerceptionElement(
            index = index,
            text = text,
            resourceId = resourceId,
            className = "Button",
            description = description,
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isEnabled = true,
            isFocused = false,
            isLongClickable = false,
            bounds = bounds,
            center = Point(bounds.centerX, bounds.centerY),
            hintText = hintText
        )
    }

    private fun hintFor(text: String, bounds: Bounds) = SemanticTargetHint(
        resourceId = "",
        text = text,
        description = "",
        className = "Button",
        bounds = bounds
    )
}
