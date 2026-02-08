package com.moonkey.androidagent.tool.handlers

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import org.json.JSONObject
import org.junit.Test

class MultiSelectorTargetingTest {

    @Test
    fun `attemptsFromParams orders selectors in fallback order`() {
        val params = JSONObject().apply {
            put("x1", 0)
            put("y1", 10)
            put("x2", 100)
            put("y2", 110)
            put("x", 5)
            put("y", 6)
            put("text", "OK")
            put("text_index", 2)
            put("element_index", 3)
        }

        val attempts = MultiSelectorTargeting.attemptsFromParams(
            params = params,
            textKey = "text",
            textIndexKey = "text_index",
            textLabel = "text"
        )

        assertThat(attempts.map { it.selector::class.simpleName }).containsExactly(
            "Bounds",
            "Point",
            "Text",
            "ElementIndex"
        ).inOrder()
        assertThat(attempts.first().label).contains("bounds center")
        assertThat(attempts[1].label).contains("coordinates")
        assertThat(attempts[2].label).contains("text=")
        assertThat(attempts[3].label).contains("element_index")
    }

    @Test
    fun `findElementIndexByTextOrDescription matches text and description`() {
        val snapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(
                    index = 0,
                    resourceId = "com.app:id/input",
                    text = "Email"
                ),
                element(
                    index = 1,
                    resourceId = "com.app:id/search",
                    text = "",
                    description = "Search"
                )
            )
        )

        val textIndex = MultiSelectorTargeting.findElementIndexByTextOrDescription(
            snapshot = snapshot,
            text = "Email",
            index = 0
        )
        val descriptionIndex = MultiSelectorTargeting.findElementIndexByTextOrDescription(
            snapshot = snapshot,
            text = "Search",
            index = 0
        )

        assertThat(textIndex).isEqualTo(0)
        assertThat(descriptionIndex).isEqualTo(1)
    }

    @Test
    fun `hasActionableElementAt returns true only for actionable bounds`() {
        val snapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(
                    index = 0,
                    resourceId = "com.app:id/plain",
                    text = "Container",
                    isClickable = false,
                    isEditable = false
                ),
                element(
                    index = 1,
                    resourceId = "com.app:id/button",
                    text = "Buy",
                    isClickable = true,
                    bounds = Bounds(left = 20, top = 20, right = 60, bottom = 60),
                    center = Point(x = 40, y = 40)
                )
            )
        )

        assertThat(MultiSelectorTargeting.hasActionableElementAt(snapshot, 5, 5)).isFalse()
        assertThat(MultiSelectorTargeting.hasActionableElementAt(snapshot, 40, 40)).isTrue()
    }

    private fun element(
        index: Int,
        resourceId: String,
        text: String = "",
        description: String = "",
        isClickable: Boolean = true,
        isEditable: Boolean = true,
        bounds: Bounds = Bounds(left = 0, top = 0, right = 10, bottom = 10),
        center: Point = Point(x = 5, y = 5)
    ): PerceptionElement {
        return PerceptionElement(
            index = index,
            text = text,
            resourceId = resourceId,
            className = "View",
            description = description,
            isClickable = isClickable,
            isEditable = isEditable,
            isScrollable = false,
            isEnabled = true,
            isFocused = false,
            isLongClickable = false,
            bounds = bounds,
            center = center
        )
    }
}
