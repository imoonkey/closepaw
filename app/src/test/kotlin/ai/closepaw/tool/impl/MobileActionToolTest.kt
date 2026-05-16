package ai.closepaw.tool.impl

import com.google.common.truth.Truth.assertThat
import ai.closepaw.tool.ValidationResult
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
    fun `swipe allows both direction and explicit coordinates`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "swipe")
            .put("direction", "down")
            .put("start", JSONArray(listOf(540, 50)))
            .put("end", JSONArray(listOf(540, 500)))

        val result = tool.validate(params)

        assertThat(result).isEqualTo(ValidationResult.Valid)
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
    fun `text_index without text is invalid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "click")
            .put("text_index", 1)

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `wait is no longer a mobile_action action`() {
        val tool = MobileActionTool()
        val params = JSONObject().put("action", "wait")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    // ---------- Coordinate-hint normalization (Codex dual target) ----------

    @Test
    fun `click element_index plus xy is valid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "click")
            .put("element_index", 14)
            .put("x", 540)
            .put("y", 1230)

        assertThat(tool.validate(params)).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `click text plus xy is valid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "click")
            .put("text", "Save")
            .put("x", 100)
            .put("y", 200)

        assertThat(tool.validate(params)).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `long_press element_index plus xy is valid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "long_press")
            .put("element_index", 3)
            .put("x", 50)
            .put("y", 60)

        assertThat(tool.validate(params)).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `type element_index plus xy plus input_text is valid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "type")
            .put("element_index", 1)
            .put("x", 100)
            .put("y", 100)
            .put("input_text", "hello")

        assertThat(tool.validate(params)).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `click element_index plus text is invalid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "click")
            .put("element_index", 1)
            .put("text", "Save")

        val result = tool.validate(params)
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.joinToString()).contains("ONE semantic target")
    }

    @Test
    fun `click element_index plus text plus xy is invalid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "click")
            .put("element_index", 1)
            .put("text", "Save")
            .put("x", 100)
            .put("y", 200)

        val result = tool.validate(params)
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.joinToString()).contains("ONE semantic target")
    }

    @Test
    fun `click negative element_index plus xy is invalid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "click")
            .put("element_index", -1)
            .put("x", 10)
            .put("y", 10)

        val result = tool.validate(params)
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.joinToString()).contains("element_index")
    }

    @Test
    fun `click x without y is invalid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "click")
            .put("x", 10)

        val result = tool.validate(params)
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.joinToString()).contains("both x and y")
    }

    @Test
    fun `click negative x is invalid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "click")
            .put("x", -1)
            .put("y", 10)

        val result = tool.validate(params)
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.joinToString()).contains(">= 0")
    }

    @Test
    fun `scroll element_index plus xy is valid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "scroll")
            .put("direction", "down")
            .put("element_index", 1)
            .put("x", 540)
            .put("y", 1200)

        assertThat(tool.validate(params)).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `scroll bare xy with no semantic target is invalid`() {
        val tool = MobileActionTool()
        val params = JSONObject()
            .put("action", "scroll")
            .put("direction", "down")
            .put("x", 540)
            .put("y", 1200)

        val result = tool.validate(params)
        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((result as ValidationResult.Invalid).errors.joinToString()).contains("bare x/y")
    }

    @Test
    fun `description string contains semantic-primary and coordinate-hint wording`() {
        val description = MobileActionTool().description

        assertThat(description).contains("semantic target is primary")
        assertThat(description).contains("fallback hint")
    }
}
