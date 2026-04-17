package ai.closepaw.agent

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class ActionTargetTest {

    // --- decodeActionTarget: basic target variants ---

    @Test
    fun `text target decoded`() {
        val args = JSONObject("""{"text":"Save","text_index":2}""")
        val target = decodeActionTarget(args)
        assertThat(target.text).isEqualTo("Save")
        assertThat(target.textIndex).isEqualTo(2)
        assertThat(target.bounds).isNull()
        assertThat(target.point).isNull()
        assertThat(target.elementIndex).isNull()
    }

    @Test
    fun `bounds target decoded`() {
        val args = JSONObject("""{"x1":10,"y1":20,"x2":100,"y2":200}""")
        val target = decodeActionTarget(args)
        assertThat(target.text).isEmpty()
        assertThat(target.bounds).isEqualTo(ActionTarget.Bounds(10, 20, 100, 200))
        assertThat(target.point).isNull()
        assertThat(target.elementIndex).isNull()
    }

    @Test
    fun `point target decoded`() {
        val args = JSONObject("""{"x":50,"y":75}""")
        val target = decodeActionTarget(args)
        assertThat(target.point).isEqualTo(ActionTarget.Point(50, 75))
        assertThat(target.bounds).isNull()
    }

    @Test
    fun `element index target decoded`() {
        val args = JSONObject("""{"element_index":7}""")
        val target = decodeActionTarget(args)
        assertThat(target.elementIndex).isEqualTo(7)
        assertThat(target.text).isEmpty()
    }

    @Test
    fun `negative element index treated as absent`() {
        val args = JSONObject("""{"element_index":-1}""")
        val target = decodeActionTarget(args)
        assertThat(target.elementIndex).isNull()
    }

    @Test
    fun `empty args produce empty target`() {
        val target = decodeActionTarget(JSONObject())
        assertThat(target.text).isEmpty()
        assertThat(target.textIndex).isEqualTo(0)
        assertThat(target.bounds).isNull()
        assertThat(target.point).isNull()
        assertThat(target.elementIndex).isNull()
    }

    @Test
    fun `partial bounds not decoded as bounds`() {
        val args = JSONObject("""{"x1":10,"y1":20}""")
        val target = decodeActionTarget(args)
        assertThat(target.bounds).isNull()
    }

    // --- decodeActionTarget: type action text resolution ---

    @Test
    fun `type action with input_text uses text as target`() {
        val args = JSONObject("""{"action":"type","input_text":"hello","text":"Search","text_index":1}""")
        val target = decodeActionTarget(args, action = "type")
        assertThat(target.text).isEqualTo("Search")
        assertThat(target.textIndex).isEqualTo(1)
    }

    @Test
    fun `type action without input_text uses target_text as target`() {
        val args = JSONObject("""{"action":"type","text":"hello","target_text":"Search","target_text_index":3}""")
        val target = decodeActionTarget(args, action = "type")
        assertThat(target.text).isEqualTo("Search")
        assertThat(target.textIndex).isEqualTo(3)
    }

    @Test
    fun `type action without input_text and no target_text has empty target`() {
        val args = JSONObject("""{"action":"type","text":"hello"}""")
        val target = decodeActionTarget(args, action = "type")
        assertThat(target.text).isEmpty()
    }

    @Test
    fun `type with input_text falls back to target_text_index when text_index absent`() {
        val args = JSONObject("""{"input_text":"hello","text":"Search","target_text_index":5}""")
        val target = decodeActionTarget(args, action = "type")
        assertThat(target.textIndex).isEqualTo(5)
    }

    @Test
    fun `type without input_text falls back to text_index when target_text_index absent`() {
        val args = JSONObject("""{"text":"hello","target_text":"Search","text_index":2}""")
        val target = decodeActionTarget(args, action = "type")
        assertThat(target.textIndex).isEqualTo(2)
    }

    @Test
    fun `text plus bounds plus point resolves to text (priority order)`() {
        val call = ToolCallRequest("1", "mobile_action",
            JSONObject("""{"action":"click","text":"Save","text_index":0,"x1":0,"y1":0,"x2":100,"y2":100,"x":50,"y":50}"""))
        assertThat(ActionDescriptionFormatter.format(call))
            .isEqualTo("Click text \"Save\" (index 0)")
    }

    // --- ActionDescriptionFormatter integration ---

    @Test
    fun `formatter click with text target`() {
        val call = ToolCallRequest("1", "mobile_action",
            JSONObject("""{"action":"click","text":"Save","text_index":0}"""))
        assertThat(ActionDescriptionFormatter.format(call))
            .isEqualTo("Click text \"Save\" (index 0)")
    }

    @Test
    fun `formatter click with bounds target`() {
        val call = ToolCallRequest("1", "mobile_action",
            JSONObject("""{"action":"click","x1":10,"y1":20,"x2":100,"y2":200}"""))
        assertThat(ActionDescriptionFormatter.format(call))
            .isEqualTo("Click bounds (10,20)-(100,200)")
    }

    @Test
    fun `formatter click with point target`() {
        val call = ToolCallRequest("1", "mobile_action",
            JSONObject("""{"action":"click","x":50,"y":75}"""))
        assertThat(ActionDescriptionFormatter.format(call))
            .isEqualTo("Click at (50,75)")
    }

    @Test
    fun `formatter click with element index target`() {
        val call = ToolCallRequest("1", "mobile_action",
            JSONObject("""{"action":"click","element_index":12}"""))
        assertThat(ActionDescriptionFormatter.format(call))
            .isEqualTo("Click element 12")
    }

    @Test
    fun `formatter type with input_text targets text field`() {
        val call = ToolCallRequest("1", "mobile_action",
            JSONObject("""{"action":"type","input_text":"hello world","text":"Search","text_index":0}"""))
        assertThat(ActionDescriptionFormatter.format(call))
            .isEqualTo("Type \"hello world\" into text \"Search\" (index 0)")
    }

    @Test
    fun `formatter type without target falls to focused field`() {
        val call = ToolCallRequest("1", "mobile_action",
            JSONObject("""{"action":"type","text":"hello"}"""))
        assertThat(ActionDescriptionFormatter.format(call))
            .isEqualTo("Type \"hello\" into focused field")
    }

    @Test
    fun `formatter swipe with direction and text target`() {
        val call = ToolCallRequest("1", "mobile_action",
            JSONObject("""{"action":"swipe","direction":"up","text":"List","text_index":0}"""))
        assertThat(ActionDescriptionFormatter.format(call))
            .isEqualTo("Swipe up (medium) from text \"List\" (index 0)")
    }

    @Test
    fun `formatter long press includes duration`() {
        val call = ToolCallRequest("1", "mobile_action",
            JSONObject("""{"action":"long_press","text":"Item","text_index":0,"duration_ms":2000}"""))
        assertThat(ActionDescriptionFormatter.format(call))
            .isEqualTo("Long press text \"Item\" (index 0) for 2000ms")
    }
}
