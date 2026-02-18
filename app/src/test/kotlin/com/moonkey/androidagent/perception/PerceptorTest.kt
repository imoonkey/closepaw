package com.moonkey.androidagent.perception

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import org.json.JSONArray
import org.junit.Test

class PerceptorTest {

    @Test
    fun `toPromptJson includes explicit booleans and state fields`() {
        val element = PerceptionElement(
            index = 0,
            text = "Hello",
            resourceId = "id/button",
            className = "Button",
            description = "desc",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isEnabled = true,
            isFocused = false,
            isLongClickable = false,
            bounds = Bounds(left = 0, top = 0, right = 100, bottom = 50),
            center = Point(x = 50, y = 25),
            isSelected = true,
            hintText = "Type a query",
            isChecked = true,
            isCheckable = true
        )
        val snapshot = ScreenSnapshot(timestamp = 0L, elements = listOf(element))

        val json = Perceptor.toPromptJson(snapshot)
        val array = JSONArray(json)

        assertThat(array.length()).isEqualTo(1)
        val obj = array.getJSONObject(0)
        assertThat(obj.getInt("index")).isEqualTo(0)
        assertThat(obj.getString("text")).isEqualTo("Hello")
        assertThat(obj.getInt("text_index")).isEqualTo(0)
        assertThat(obj.getString("class")).isEqualTo("Button")
        assertThat(obj.getBoolean("clickable")).isTrue()
        assertThat(obj.getBoolean("editable")).isFalse()
        assertThat(obj.getBoolean("scrollable")).isFalse()
        assertThat(obj.getBoolean("selected")).isTrue()
        assertThat(obj.getBoolean("checked")).isTrue()
        assertThat(obj.getBoolean("checkable")).isTrue()
        assertThat(obj.getString("hint_text")).isEqualTo("Type a query")
        assertThat(obj.getBoolean("focused")).isFalse()
        assertThat(obj.getBoolean("long_clickable")).isFalse()
        val center = obj.getJSONArray("center")
        assertThat(center.getInt(0)).isEqualTo(50)
        assertThat(center.getInt(1)).isEqualTo(25)
    }

    @Test
    fun `toPromptJson emits desc_index for duplicate descriptions`() {
        val first = element(index = 0, text = "", description = "More options")
        val second = element(index = 1, text = "", description = "More options")
        val snapshot = ScreenSnapshot(timestamp = 0L, elements = listOf(first, second))

        val array = JSONArray(Perceptor.toPromptJson(snapshot))

        assertThat(array.getJSONObject(0).getInt("desc_index")).isEqualTo(0)
        assertThat(array.getJSONObject(1).getInt("desc_index")).isEqualTo(1)
    }

    @Test
    fun `toPromptJson applies text fallback chain`() {
        val fromHint = element(
            index = 0,
            text = "",
            description = "",
            resourceId = "",
            hintText = "Search"
        )
        val fromId = element(
            index = 1,
            text = "",
            description = "",
            resourceId = "com.example.app:id/icon_thumb"
        )
        val snapshot = ScreenSnapshot(timestamp = 0L, elements = listOf(fromHint, fromId))

        val array = JSONArray(Perceptor.toPromptJson(snapshot))

        assertThat(array.getJSONObject(0).getString("text")).isEqualTo("Search")
        assertThat(array.getJSONObject(1).getString("text")).isEqualTo("icon_thumb")
    }

    @Test
    fun `toPromptJson outputs id only when actionable id density passes threshold`() {
        val highDensitySnapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(index = 0, resourceId = "pkg:id/one", isClickable = true),
                element(index = 1, resourceId = "pkg:id/two", isClickable = false),
                element(index = 2, resourceId = "", isClickable = true)
            )
        )
        val highDensity = JSONArray(Perceptor.toPromptJson(highDensitySnapshot))
        assertThat(highDensity.getJSONObject(0).getString("id")).isEqualTo("pkg:id/one")
        assertThat(highDensity.getJSONObject(1).getString("id")).isEqualTo("pkg:id/two")
        assertThat(highDensity.getJSONObject(2).has("id")).isFalse()

        val lowDensitySnapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(index = 0, resourceId = "pkg:id/one", isClickable = true),
                element(index = 1, resourceId = "", isClickable = true),
                element(index = 2, resourceId = "", isClickable = true),
                element(index = 3, resourceId = "", isClickable = true),
                element(index = 4, resourceId = "", isClickable = true),
                element(index = 5, resourceId = "", isClickable = true)
            )
        )
        val lowDensity = JSONArray(Perceptor.toPromptJson(lowDensitySnapshot))
        assertThat(lowDensity.getJSONObject(0).has("id")).isFalse()
    }

    private fun element(
        index: Int,
        text: String = "Text",
        description: String = "",
        resourceId: String = "",
        isClickable: Boolean = true,
        hintText: String = ""
    ): PerceptionElement {
        return PerceptionElement(
            index = index,
            text = text,
            resourceId = resourceId,
            className = "Button",
            description = description,
            isClickable = isClickable,
            isEditable = false,
            isScrollable = false,
            isEnabled = true,
            isFocused = false,
            isLongClickable = false,
            bounds = Bounds(left = 0, top = 0, right = 100, bottom = 50),
            center = Point(x = 50, y = 25),
            hintText = hintText
        )
    }
}
