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
    fun `toPromptJson includes expected fields`() {
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
            center = Point(x = 50, y = 25)
        )
        val snapshot = ScreenSnapshot(timestamp = 0L, elements = listOf(element))

        val json = Perceptor.toPromptJson(snapshot)
        val array = JSONArray(json)

        assertThat(array.length()).isEqualTo(1)
        val obj = array.getJSONObject(0)
        assertThat(obj.getInt("index")).isEqualTo(0)
        assertThat(obj.getString("text")).isEqualTo("Hello")
        assertThat(obj.getString("resource_id")).isEqualTo("id/button")
        assertThat(obj.getInt("resource_id_index")).isEqualTo(0)
        assertThat(obj.getInt("text_index")).isEqualTo(0)
        assertThat(obj.getInt("desc_index")).isEqualTo(0)
        assertThat(obj.getString("class")).isEqualTo("Button")
        assertThat(obj.getString("desc")).isEqualTo("desc")
        assertThat(obj.getBoolean("clickable")).isTrue()
        assertThat(obj.getBoolean("editable")).isFalse()
        assertThat(obj.getBoolean("scrollable")).isFalse()
        assertThat(obj.getBoolean("enabled")).isTrue()
        assertThat(obj.getBoolean("focused")).isFalse()
        assertThat(obj.getBoolean("long_clickable")).isFalse()
        val center = obj.getJSONArray("center")
        assertThat(center.getInt(0)).isEqualTo(50)
        assertThat(center.getInt(1)).isEqualTo(25)
    }
}
