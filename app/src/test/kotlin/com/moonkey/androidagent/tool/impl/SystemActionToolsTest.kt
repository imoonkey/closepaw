package com.moonkey.androidagent.tool.impl

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.tool.ValidationResult
import org.json.JSONObject
import org.junit.Test

class SystemActionToolsTest {

    @Test
    fun `wait validates duration range`() {
        val tool = WaitTool()

        val valid = tool.validate(JSONObject().put("duration_ms", 1500))
        val tooLong = tool.validate(JSONObject().put("duration_ms", 30_001))
        val negative = tool.validate(JSONObject().put("duration_ms", -1))

        assertThat(valid).isEqualTo(ValidationResult.Valid)
        assertThat(tooLong).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat(negative).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `system_button requires supported button`() {
        val tool = SystemButtonTool()

        val valid = tool.validate(JSONObject().put("button", "back"))
        val missing = tool.validate(JSONObject())
        val invalid = tool.validate(JSONObject().put("button", "power"))

        assertThat(valid).isEqualTo(ValidationResult.Valid)
        assertThat(missing).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat(invalid).isInstanceOf(ValidationResult.Invalid::class.java)
    }
}
