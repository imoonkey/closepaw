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
            "Point",
            "Text",
            "ElementIndex"
        ).inOrder()
        assertThat(attempts.first().label).contains("coordinates")
        assertThat(attempts[1].label).contains("text=")
        assertThat(attempts[2].label).contains("element_index")
    }

    @Test
    fun `attemptsFromParams supports click selector order`() {
        val params = JSONObject().apply {
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
            textLabel = "text",
            selectorOrder = MultiSelectorTargeting.CLICK_FALLBACK_ORDER
        )

        assertThat(attempts.map { it.selector::class.simpleName }).containsExactly(
            "ElementIndex",
            "Text",
            "Point"
        ).inOrder()
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
