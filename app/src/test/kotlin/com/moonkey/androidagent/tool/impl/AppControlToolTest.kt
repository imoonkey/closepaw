package com.moonkey.androidagent.tool.impl

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.tool.ValidationResult
import org.json.JSONObject
import org.junit.Test

class AppControlToolTest {

    @Test
    fun `open_app requires package_name or app_name`() {
        val tool = AppControlTool()
        val params = JSONObject().put("action", "open_app")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `open_app with app_name is valid`() {
        val tool = AppControlTool()
        val params = JSONObject()
            .put("action", "open_app")
            .put("app_name", "Gmail")

        val result = tool.validate(params)

        assertThat(result).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `list_apps without params is valid`() {
        val tool = AppControlTool()
        val params = JSONObject().put("action", "list_apps")

        val result = tool.validate(params)

        assertThat(result).isEqualTo(ValidationResult.Valid)
    }
}
