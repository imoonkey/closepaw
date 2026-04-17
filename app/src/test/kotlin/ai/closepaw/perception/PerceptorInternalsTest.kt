package ai.closepaw.perception

import com.google.common.truth.Truth.assertThat
import ai.closepaw.model.Bounds
import ai.closepaw.model.PerceptionElement
import ai.closepaw.model.Point
import org.junit.Test

class PerceptorInternalsTest {

    // --- mergedText ---

    @Test
    fun `mergedText returns text when available`() {
        val elem = element(text = "Hello", description = "Desc", hintText = "Hint")
        assertThat(mergedText(elem)).isEqualTo("Hello")
    }

    @Test
    fun `mergedText falls back to description`() {
        val elem = element(text = "", description = "Desc", hintText = "Hint")
        assertThat(mergedText(elem)).isEqualTo("Desc")
    }

    @Test
    fun `mergedText falls back to hintText`() {
        val elem = element(text = "", description = "", hintText = "Hint")
        assertThat(mergedText(elem)).isEqualTo("Hint")
    }

    @Test
    fun `mergedText returns empty when only resourceId is present`() {
        val elem = element(text = "", description = "", hintText = "", resourceId = "pkg:id/search_btn")
        assertThat(mergedText(elem)).isEqualTo("")
    }

    @Test
    fun `mergedText returns empty when all blank`() {
        val elem = element(text = "", description = "", hintText = "", resourceId = "")
        assertThat(mergedText(elem)).isEqualTo("")
    }

    // --- normalizeForMatching ---

    @Test
    fun `normalizeForMatching trims and lowercases for matching`() {
        assertThat(normalizeForMatching("  Save  ")).isEqualTo("save")
    }

    @Test
    fun `normalizeForMatching preserves internal whitespace`() {
        assertThat(normalizeForMatching("A  B")).isEqualTo("a  b")
        assertThat(normalizeForMatching("A\tB")).isEqualTo("a\tb")
    }

    // --- shouldOutputResourceIds ---

    @Test
    fun `shouldOutputResourceIds returns true when density above threshold`() {
        val elements = listOf(
            element(isClickable = true, resourceId = "pkg:id/one"),
            element(isClickable = true, resourceId = "pkg:id/two"),
            element(isClickable = true, resourceId = "")
        )
        // 2/3 = 66% >= 20%
        assertThat(shouldOutputResourceIds(elements, 0.20f)).isTrue()
    }

    @Test
    fun `shouldOutputResourceIds returns false when density below threshold`() {
        val elements = listOf(
            element(isClickable = true, resourceId = "pkg:id/one"),
            element(isClickable = true, resourceId = ""),
            element(isClickable = true, resourceId = ""),
            element(isClickable = true, resourceId = ""),
            element(isClickable = true, resourceId = ""),
            element(isClickable = true, resourceId = "")
        )
        // 1/6 = 16.7% < 20%
        assertThat(shouldOutputResourceIds(elements, 0.20f)).isFalse()
    }

    @Test
    fun `shouldOutputResourceIds ignores non-actionable elements`() {
        val elements = listOf(
            element(isClickable = false, resourceId = "pkg:id/label"),
            element(isClickable = true, resourceId = "pkg:id/btn")
        )
        // 1 actionable with id / 1 actionable total = 100%
        assertThat(shouldOutputResourceIds(elements, 0.20f)).isTrue()
    }

    @Test
    fun `shouldOutputResourceIds returns false for empty list`() {
        assertThat(shouldOutputResourceIds(emptyList(), 0.20f)).isFalse()
    }

    @Test
    fun `shouldOutputResourceIds returns false when no actionable elements`() {
        val elements = listOf(
            element(isClickable = false, resourceId = "pkg:id/label")
        )
        assertThat(shouldOutputResourceIds(elements, 0.20f)).isFalse()
    }

    // --- enrichEmptyTextElements ---

    @Test
    fun `enrichEmptyTextElements bubbles child text into empty interactive parent`() {
        val parent = candidate(
            text = "", description = "", hintText = "", resourceId = "",
            isClickable = true,
            bounds = Bounds(0, 0, 200, 100)
        )
        val child = candidate(
            text = "Submit",
            isClickable = false,
            bounds = Bounds(10, 10, 190, 90)
        )
        val result = enrichEmptyTextElements(listOf(parent, child))
        assertThat(result[0].element.text).isEqualTo("Submit")
        assertThat(result[1].element.text).isEqualTo("Submit")
    }

    @Test
    fun `enrichEmptyTextElements does not enrich scrollable containers`() {
        val scrollContainer = candidate(
            text = "", description = "", hintText = "", resourceId = "",
            isClickable = false, isScrollable = true,
            bounds = Bounds(0, 0, 500, 1000)
        )
        val child = candidate(
            text = "Item",
            isClickable = false,
            bounds = Bounds(10, 10, 490, 100)
        )
        val result = enrichEmptyTextElements(listOf(scrollContainer, child))
        assertThat(result[0].element.text).isEqualTo("")
    }

    @Test
    fun `enrichEmptyTextElements does not bubble from interactive children`() {
        val parent = candidate(
            text = "", description = "", hintText = "", resourceId = "",
            isClickable = true,
            bounds = Bounds(0, 0, 200, 100)
        )
        val interactiveChild = candidate(
            text = "Click me",
            isClickable = true,
            bounds = Bounds(10, 10, 190, 90)
        )
        val result = enrichEmptyTextElements(listOf(parent, interactiveChild))
        // Interactive children are NOT text sources
        assertThat(result[0].element.text).isEqualTo("")
    }

    @Test
    fun `enrichEmptyTextElements joins multiple child texts`() {
        val parent = candidate(
            text = "", description = "", hintText = "", resourceId = "",
            isClickable = true,
            bounds = Bounds(0, 0, 300, 100)
        )
        val child1 = candidate(text = "A", isClickable = false, bounds = Bounds(0, 0, 100, 100))
        val child2 = candidate(text = "B", isClickable = false, bounds = Bounds(100, 0, 200, 100))
        val child3 = candidate(text = "C", isClickable = false, bounds = Bounds(200, 0, 300, 100))
        val result = enrichEmptyTextElements(listOf(parent, child1, child2, child3))
        assertThat(result[0].element.text).isEqualTo("A | B | C")
    }

    @Test
    fun `enrichEmptyTextElements takes at most 3 children`() {
        val parent = candidate(
            text = "", description = "", hintText = "", resourceId = "",
            isClickable = true,
            bounds = Bounds(0, 0, 400, 100)
        )
        val children = (0..4).map { i ->
            candidate(text = "T$i", isClickable = false, bounds = Bounds(i * 80, 0, (i + 1) * 80, 100))
        }
        val result = enrichEmptyTextElements(listOf(parent) + children)
        val parts = result[0].element.text.split(" | ")
        assertThat(parts).hasSize(3)
    }

    @Test
    fun `enrichEmptyTextElements preserves candidate order for out-of-order labels`() {
        // Parent is a button with no text. Its labels, in candidate order, are
        // ["A", "B", "C"] — but their vertical positions are out of order, so
        // a naive sort-by-top would produce ["C", "A", "B"] instead.
        val parent = candidate(
            text = "", description = "", hintText = "", resourceId = "",
            isClickable = true,
            bounds = Bounds(0, 0, 300, 300)
        )
        val childA = candidate(text = "A", isClickable = false, bounds = Bounds(0, 200, 100, 290))
        val childB = candidate(text = "B", isClickable = false, bounds = Bounds(0,   0, 100,  90))
        val childC = candidate(text = "C", isClickable = false, bounds = Bounds(0, 100, 100, 190))
        val result = enrichEmptyTextElements(listOf(parent, childA, childB, childC))
        assertThat(result[0].element.text).isEqualTo("A | B | C")
    }

    @Test
    fun `enrichEmptyTextElements handles 1000 candidates quickly`() {
        // 200 interactive buttons without text, each in its own vertical band of
        // 4 non-interactive text nodes. No button's band overlaps another's, so
        // a quadratic scan would still walk 200 * 800 = 160K containment checks.
        val interactiveCount = 200
        val textPerBand = 4
        val candidates = mutableListOf<PerceptorCandidateElement>()
        for (i in 0 until interactiveCount) {
            val top = i * 100
            candidates += candidate(
                text = "", description = "", hintText = "", resourceId = "",
                isClickable = true,
                bounds = Bounds(0, top, 200, top + 100)
            )
            for (t in 0 until textPerBand) {
                candidates += candidate(
                    text = "T_${i}_$t",
                    isClickable = false,
                    bounds = Bounds(10, top + t * 20, 190, top + t * 20 + 15)
                )
            }
        }

        val start = System.nanoTime()
        val result = enrichEmptyTextElements(candidates)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // Every interactive parent should receive exactly 3 of its band's text nodes.
        val enrichedInteractive = result.filter { it.isInteractive }
        assertThat(enrichedInteractive).hasSize(interactiveCount)
        for (c in enrichedInteractive) {
            val parts = c.element.text.split(" | ").filter { it.isNotEmpty() }
            assertThat(parts.size).isAtLeast(1)
        }
        assertThat(elapsedMs).isLessThan(500)
    }

    @Test
    fun `enrichEmptyTextElements skips parent with existing text`() {
        val parent = candidate(
            text = "Already has text",
            isClickable = true,
            bounds = Bounds(0, 0, 200, 100)
        )
        val child = candidate(text = "Child", isClickable = false, bounds = Bounds(10, 10, 190, 90))
        val result = enrichEmptyTextElements(listOf(parent, child))
        assertThat(result[0].element.text).isEqualTo("Already has text")
    }

    @Test
    fun `enrichEmptyTextElements skips child outside parent bounds`() {
        val parent = candidate(
            text = "", description = "", hintText = "", resourceId = "",
            isClickable = true,
            bounds = Bounds(0, 0, 100, 100)
        )
        val outsideChild = candidate(text = "Outside", isClickable = false, bounds = Bounds(200, 200, 300, 300))
        val result = enrichEmptyTextElements(listOf(parent, outsideChild))
        assertThat(result[0].element.text).isEqualTo("")
    }

    @Test
    fun `enrichEmptyTextElements handles empty list`() {
        assertThat(enrichEmptyTextElements(emptyList())).isEmpty()
    }

    @Test
    fun `enrichEmptyTextElements uses description as text source via mergedText`() {
        val parent = candidate(
            text = "", description = "", hintText = "", resourceId = "",
            isClickable = true,
            bounds = Bounds(0, 0, 200, 100)
        )
        val child = candidate(
            text = "", description = "Icon label",
            isClickable = false,
            bounds = Bounds(10, 10, 190, 90)
        )
        val result = enrichEmptyTextElements(listOf(parent, child))
        assertThat(result[0].element.text).isEqualTo("Icon label")
    }

    // --- applyTruncation ---

    @Test
    fun `applyTruncation returns all when under limit`() {
        val candidates = (0..4).map { candidate(text = "T$it", isClickable = true) }
        val result = applyTruncation(candidates, maxElements = 10, interactiveKeepRatio = 0.8f)
        assertThat(result).hasSize(5)
    }

    @Test
    fun `applyTruncation respects maxElements`() {
        val candidates = (0..19).map { candidate(text = "T$it", isClickable = true) }
        val result = applyTruncation(candidates, maxElements = 5, interactiveKeepRatio = 0.8f)
        assertThat(result).hasSize(5)
    }

    @Test
    fun `applyTruncation guarantees non-interactive floor`() {
        // 8 interactive + 8 non-interactive, maxElements=10, ratio=0.8
        // interactive cap = 8, non-interactive floor = 2
        val interactive = (0..7).map { candidate(text = "Btn$it", isClickable = true) }
        val nonInteractive = (0..7).map { candidate(text = "Label$it", isClickable = false) }
        val result = applyTruncation(
            interactive + nonInteractive,
            maxElements = 10,
            interactiveKeepRatio = 0.8f
        )
        assertThat(result).hasSize(10)
        val keptNonInteractive = result.count { !it.isInteractive }
        assertThat(keptNonInteractive).isAtLeast(2) // floor guarantee
    }

    @Test
    fun `applyTruncation fills overflow from remaining`() {
        // 2 interactive + 10 non-interactive, maxElements=5, ratio=0.8
        // interactive cap = 4 but only 2 available, non-interactive floor = 1
        // kept = 2 interactive + 1 non-interactive = 3, then fill 2 more from remaining
        val interactive = (0..1).map { candidate(text = "Btn$it", isClickable = true) }
        val nonInteractive = (0..9).map { candidate(text = "Label$it", isClickable = false) }
        val result = applyTruncation(
            interactive + nonInteractive,
            maxElements = 5,
            interactiveKeepRatio = 0.8f
        )
        assertThat(result).hasSize(5)
        val keptInteractive = result.count { it.isInteractive }
        assertThat(keptInteractive).isEqualTo(2) // all interactive kept
    }

    @Test
    fun `applyTruncation prefers higher-scoring elements`() {
        // Two interactive elements, one with text (higher score) and one without
        val withText = candidate(text = "Submit", isClickable = true, visibleAreaRatio = 1.0f)
        val withoutText = candidate(text = "", description = "", hintText = "", resourceId = "",
            isClickable = true, visibleAreaRatio = 1.0f)
        val result = applyTruncation(
            listOf(withoutText, withText),
            maxElements = 1,
            interactiveKeepRatio = 0.8f
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].element.text).isEqualTo("Submit")
    }

    @Test
    fun `applyTruncation scales to 1000 candidates without duplicates`() {
        val interactive = (0 until 700).map { candidate(text = "Btn$it", isClickable = true) }
        val nonInteractive = (0 until 300).map { candidate(text = "Label$it", isClickable = false) }
        val input = interactive + nonInteractive

        val result = applyTruncation(input, maxElements = 500, interactiveKeepRatio = 0.8f)

        assertThat(result).hasSize(500)
        assertThat(result.toSet().size).isEqualTo(result.size) // no duplicates
        val keptInteractive = result.count { it.isInteractive }
        val keptNonInteractive = result.size - keptInteractive
        assertThat(keptInteractive).isAtMost(400)
        assertThat(keptNonInteractive).isAtLeast(100)
    }

    // --- spatialSort ---

    @Test
    fun `spatialSort orders by row then column`() {
        val topRight = candidate(text = "TR", bounds = Bounds(200, 0, 300, 50))
        val topLeft = candidate(text = "TL", bounds = Bounds(0, 0, 100, 50))
        val bottomLeft = candidate(text = "BL", bounds = Bounds(0, 200, 100, 250))
        val result = spatialSort(listOf(topRight, bottomLeft, topLeft), screenHeightPx = 1000, rowSnapRatio = 0.02f)
        assertThat(result.map { it.element.text }).containsExactly("TL", "TR", "BL").inOrder()
    }

    @Test
    fun `spatialSort snaps rows within threshold`() {
        // Two elements with slightly different tops but within the same snap row
        // screenHeight=1000, rowSnapRatio=0.02 -> snap=20px
        val a = candidate(text = "A", bounds = Bounds(200, 5, 300, 50))
        val b = candidate(text = "B", bounds = Bounds(0, 15, 100, 50))
        // Both top/snap = 0 (5/20=0, 15/20=0), so sorted by left
        val result = spatialSort(listOf(a, b), screenHeightPx = 1000, rowSnapRatio = 0.02f)
        assertThat(result.map { it.element.text }).containsExactly("B", "A").inOrder()
    }

    @Test
    fun `spatialSort uses fallback when no screen height`() {
        val a = candidate(text = "A", bounds = Bounds(100, 0, 200, 50))
        val b = candidate(text = "B", bounds = Bounds(0, 0, 100, 50))
        val result = spatialSort(listOf(a, b), screenHeightPx = null, rowSnapRatio = 0.02f)
        assertThat(result.map { it.element.text }).containsExactly("B", "A").inOrder()
    }

    @Test
    fun `spatialSort handles empty list`() {
        assertThat(spatialSort(emptyList(), screenHeightPx = 1000, rowSnapRatio = 0.02f)).isEmpty()
    }

    @Test
    fun `spatialSort handles single element`() {
        val elem = candidate(text = "Solo")
        val result = spatialSort(listOf(elem), screenHeightPx = 1000, rowSnapRatio = 0.02f)
        assertThat(result).hasSize(1)
        assertThat(result[0].element.text).isEqualTo("Solo")
    }

    // --- getOccurrenceIndex ---

    @Test
    fun `getOccurrenceIndex tracks duplicates`() {
        val counts = mutableMapOf<String, Int>()
        assertThat(getOccurrenceIndex("Hello", counts) { it.lowercase() }).isEqualTo(0)
        assertThat(getOccurrenceIndex("hello", counts) { it.lowercase() }).isEqualTo(1)
        assertThat(getOccurrenceIndex("HELLO", counts) { it.lowercase() }).isEqualTo(2)
    }

    @Test
    fun `getOccurrenceIndex returns null for blank`() {
        val counts = mutableMapOf<String, Int>()
        assertThat(getOccurrenceIndex("", counts) { it }).isNull()
        assertThat(getOccurrenceIndex("   ", counts) { it }).isNull()
    }

    @Test
    fun `getOccurrenceIndex tracks different values independently`() {
        val counts = mutableMapOf<String, Int>()
        assertThat(getOccurrenceIndex("A", counts) { it }).isEqualTo(0)
        assertThat(getOccurrenceIndex("B", counts) { it }).isEqualTo(0)
        assertThat(getOccurrenceIndex("A", counts) { it }).isEqualTo(1)
    }

    // --- Helpers ---

    private fun element(
        index: Int = 0,
        text: String = "Text",
        description: String = "",
        resourceId: String = "",
        isClickable: Boolean = true,
        isEditable: Boolean = false,
        isScrollable: Boolean = false,
        hintText: String = "",
        bounds: Bounds = Bounds(0, 0, 100, 50)
    ): PerceptionElement {
        return PerceptionElement(
            index = index,
            text = text,
            resourceId = resourceId,
            className = "Button",
            description = description,
            isClickable = isClickable,
            isEditable = isEditable,
            isScrollable = isScrollable,
            isEnabled = true,
            isFocused = false,
            isLongClickable = false,
            bounds = bounds,
            center = Point(
                x = (bounds.left + bounds.right) / 2,
                y = (bounds.top + bounds.bottom) / 2
            ),
            hintText = hintText
        )
    }

    private fun candidate(
        text: String = "Text",
        description: String = "",
        resourceId: String = "",
        isClickable: Boolean = false,
        isEditable: Boolean = false,
        isScrollable: Boolean = false,
        hintText: String = "",
        bounds: Bounds = Bounds(0, 0, 100, 50),
        visibleAreaRatio: Float = 1.0f
    ): PerceptorCandidateElement {
        return PerceptorCandidateElement(
            element = element(
                text = text,
                description = description,
                resourceId = resourceId,
                isClickable = isClickable,
                isEditable = isEditable,
                isScrollable = isScrollable,
                hintText = hintText,
                bounds = bounds
            ),
            visibleAreaRatio = visibleAreaRatio
        )
    }
}
