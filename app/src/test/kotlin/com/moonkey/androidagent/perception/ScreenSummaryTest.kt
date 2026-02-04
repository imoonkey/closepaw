package com.moonkey.androidagent.perception

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import org.junit.Test

class ScreenSummaryTest {

    @Test
    fun `toSummary includes app and element counters`() {
        val snapshot =
            ScreenSnapshot(
                timestamp = 1L,
                elements =
                    listOf(
                        element(index = 0, text = "Inbox item", isClickable = true, centerY = 100),
                        element(index = 1, text = "Input", isEditable = true, centerY = 200),
                        element(index = 2, text = "Focused title", isFocused = true, centerY = 300)
                    )
            )

        val summary = snapshot.toSummary("com.test.app")

        assertThat(summary).contains("com.test.app | elements=3, clickable=1, editable=1, focused=Focused title")
    }

    @Test
    fun `toSummary orders labels by vertical position and removes duplicates`() {
        val snapshot =
            ScreenSnapshot(
                timestamp = 1L,
                elements =
                    listOf(
                        element(index = 0, text = "Later", centerY = 300),
                        element(index = 1, text = "Earlier", centerY = 100),
                        element(index = 2, text = "Earlier", centerY = 200)
                    )
            )

        val summary = snapshot.toSummary("com.test.app")

        assertThat(summary).contains("labels=Earlier, Later")
    }

    @Test
    fun `toSummary truncates long labels`() {
        val longText = "12345678901234567890123456789012345678901234567890"
        val expected = longText.take(37) + "..."
        val snapshot =
            ScreenSnapshot(
                timestamp = 1L,
                elements = listOf(element(index = 0, text = longText, centerY = 100))
            )

        val summary = snapshot.toSummary("com.test.app")

        assertThat(summary).contains("labels=$expected")
    }

    @Test
    fun `toSummary uses none when no usable labels`() {
        val snapshot =
            ScreenSnapshot(
                timestamp = 1L,
                elements =
                    listOf(
                        element(index = 0, text = "", description = "", centerY = 100),
                        element(index = 1, text = "ok", description = "", centerY = 200)
                    )
            )

        val summary = snapshot.toSummary(null)

        assertThat(summary).contains("unknown app")
        assertThat(summary).contains("labels=none")
    }

    private fun element(
        index: Int,
        text: String,
        description: String = "",
        isClickable: Boolean = false,
        isEditable: Boolean = false,
        isFocused: Boolean = false,
        centerY: Int
    ): PerceptionElement {
        return PerceptionElement(
            index = index,
            text = text,
            resourceId = "",
            className = "TextView",
            description = description,
            isClickable = isClickable,
            isEditable = isEditable,
            isScrollable = false,
            isEnabled = true,
            isFocused = isFocused,
            isLongClickable = false,
            bounds = Bounds(left = 0, top = centerY - 1, right = 10, bottom = centerY + 1),
            center = Point(x = 5, y = centerY)
        )
    }
}
