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
            put("resource_id", "com.app:id/button")
            put("resource_id_index", 1)
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
            "ResourceId",
            "Text",
            "ElementIndex"
        ).inOrder()
        assertThat(attempts.first().label).contains("bounds center")
        assertThat(attempts[1].label).contains("coordinates")
        assertThat(attempts[2].label).contains("resource_id=")
        assertThat(attempts[3].label).contains("text=")
        assertThat(attempts[4].label).contains("element_index")
    }

    @Test
    fun `filterTypeAttemptsByResourceIdTargetTextMismatch drops resource id attempt`() {
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
                    text = "Search"
                )
            )
        )

        val params = JSONObject().apply {
            put("resource_id", "com.app:id/input")
            put("resource_id_index", 0)
            put("target_text", "Search")
            put("target_text_index", 0)
        }

        val rawAttempts = listOf(
            MultiSelectorTargeting.Attempt(
                selector = MultiSelectorTargeting.Selector.ResourceId("com.app:id/input", 0),
                label = "resource_id='com.app:id/input' index 0"
            ),
            MultiSelectorTargeting.Attempt(
                selector = MultiSelectorTargeting.Selector.Text("Search", 0),
                label = "target_text=\"Search\" index 0"
            )
        )

        val filtered = MultiSelectorTargeting.filterTypeAttemptsByResourceIdTargetTextMismatch(
            params = params,
            snapshot = snapshot,
            attempts = rawAttempts
        )

        assertThat(filtered.attempts).hasSize(1)
        assertThat(filtered.attempts.first().selector).isInstanceOf(MultiSelectorTargeting.Selector.Text::class.java)
        assertThat(filtered.warnings).hasSize(1)
        assertThat(filtered.warnings.first()).contains("ignored")
    }

    private fun element(
        index: Int,
        resourceId: String,
        text: String = "",
        description: String = ""
    ): PerceptionElement {
        return PerceptionElement(
            index = index,
            text = text,
            resourceId = resourceId,
            className = "View",
            description = description,
            isClickable = true,
            isEditable = true,
            isScrollable = false,
            bounds = Bounds(left = 0, top = 0, right = 10, bottom = 10),
            center = Point(x = 5, y = 5)
        )
    }
}

