package com.moonkey.androidagent.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelsTest {

    @Test
    fun `bounds computes width height and center`() {
        val bounds = Bounds(left = 10, top = 20, right = 110, bottom = 70)

        assertThat(bounds.width).isEqualTo(100)
        assertThat(bounds.height).isEqualTo(50)
        assertThat(bounds.centerX).isEqualTo(60)
        assertThat(bounds.centerY).isEqualTo(45)
    }

    @Test
    fun `screen snapshot holds elements`() {
        val element = PerceptionElement(
            index = 0,
            text = "a",
            resourceId = "id",
            className = "Text",
            description = "",
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            bounds = Bounds(0, 0, 1, 1),
            center = Point(0, 0)
        )
        val snapshot = ScreenSnapshot(timestamp = 123L, elements = listOf(element))

        assertThat(snapshot.elements).hasSize(1)
        assertThat(snapshot.elements.first().index).isEqualTo(0)
        assertThat(snapshot.timestamp).isEqualTo(123L)
    }
}
