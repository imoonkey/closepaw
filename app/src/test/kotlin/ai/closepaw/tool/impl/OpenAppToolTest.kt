package ai.closepaw.tool.impl

import com.google.common.truth.Truth.assertThat
import ai.closepaw.tool.ValidationResult
import org.json.JSONObject
import org.junit.Test

class OpenAppToolTest {

    @Test
    fun `open_app requires app_name`() {
        val tool = OpenAppTool()
        val params = JSONObject()

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `open_app with empty app_name is invalid`() {
        val tool = OpenAppTool()
        val params = JSONObject().put("app_name", "")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `open_app with app_name is valid`() {
        val tool = OpenAppTool()
        val params = JSONObject().put("app_name", "Gmail")

        val result = tool.validate(params)

        assertThat(result).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `open_app with app_name and agent_thought is valid`() {
        val tool = OpenAppTool()
        val params = JSONObject()
            .put("app_name", "Settings")
            .put("agent_thought", "Need to change Wi-Fi settings")

        val result = tool.validate(params)

        assertThat(result).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `tool name is open_app`() {
        val tool = OpenAppTool()

        assertThat(tool.name).isEqualTo("open_app")
    }

    @Test
    fun `schema has required app_name`() {
        val tool = OpenAppTool()
        val schema = tool.parameterSchema

        val required = schema.getJSONArray("required")
        assertThat(required.length()).isEqualTo(1)
        assertThat(required.getString(0)).isEqualTo("app_name")
    }

    @Test
    fun `schema has only app_name and agent_thought properties`() {
        val tool = OpenAppTool()
        val properties = tool.parameterSchema.getJSONObject("properties")

        val keys = properties.keys().asSequence().toSet()
        assertThat(keys).containsExactly("app_name", "agent_thought")
    }

    @Test
    fun `alias map contains simple calendar pro`() {
        assertThat(AppAliases.PACKAGE_MAP["simple calendar pro"])
            .isEqualTo("com.simplemobiletools.calendar.pro")
    }

    @Test
    fun `alias map contains simple calendar`() {
        assertThat(AppAliases.PACKAGE_MAP["simple calendar"])
            .isEqualTo("com.simplemobiletools.calendar.pro")
    }

    @Test
    fun `alias map contains audio recorder`() {
        assertThat(AppAliases.PACKAGE_MAP["audio recorder"])
            .isEqualTo("com.dimowner.audiorecorder")
    }

    @Test
    fun `alias map contains pro expense`() {
        assertThat(AppAliases.PACKAGE_MAP["pro expense"])
            .isEqualTo("com.arduia.expense")
    }

    @Test
    fun `alias map contains markor`() {
        assertThat(AppAliases.PACKAGE_MAP["markor"])
            .isEqualTo("net.gsantner.markor")
    }

    @Test
    fun `alias map contains simple draw pro`() {
        assertThat(AppAliases.PACKAGE_MAP["simple draw pro"])
            .isEqualTo("com.simplemobiletools.draw.pro")
    }
}
