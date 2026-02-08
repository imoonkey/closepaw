package com.moonkey.androidagent.tool.impl

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.tool.ValidationResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class MobileActionToolTest {

    @Test
    fun `missing action is invalid`() {
        val tool = MobileActionTool()

        val result = tool.validate(JSONObject())

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `unknown action is invalid`() {
        val tool = MobileActionTool()
        val params = JSONObject().put("action", "unknown")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `click requires element_index`() {
        val tool = MobileActionTool()
        val params = JSONObject().put("action", "click")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `click rejects bounds selector`() {
        val tool = MobileActionTool()
        val params =
            JSONObject()
                .put("action", "click")
                .put("x1", 0)
                .put("y1", 0)
                .put("x2", 10)
                .put("y2", 10)

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `long press rejects bounds selector`() {
        val tool = MobileActionTool()
        val params =
            JSONObject()
                .put("action", "long_press")
                .put("x1", 0)
                .put("y1", 0)
                .put("x2", 10)
                .put("y2", 10)

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `type rejects bounds selector`() {
        val tool = MobileActionTool()
        val params =
            JSONObject()
                .put("action", "type")
                .put("input_text", "hello")
                .put("x1", 0)
                .put("y1", 0)
                .put("x2", 10)
                .put("y2", 10)

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `swipe rejects bounds selector`() {
        val tool = MobileActionTool()
        val params =
            JSONObject()
                .put("action", "swipe")
                .put("direction", "up")
                .put("x1", 0)
                .put("y1", 0)
                .put("x2", 10)
                .put("y2", 10)

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `swipe requires start and end`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "swipe")
            .put("start", JSONArray(listOf(0, 0)))

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `type with text only is valid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "type")
            .put("input_text", "hello")

        val result = tool.validate(params)

        assertThat(result).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `wait is no longer a mobile_action action`() {
        val tool = MobileActionTool()
        val params = JSONObject().put("action", "wait")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }
}
