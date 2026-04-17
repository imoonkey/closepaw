package ai.closepaw.tool.impl

import com.google.common.truth.Truth.assertThat
import ai.closepaw.tool.ValidationResult
import org.json.JSONObject
import org.junit.Test

class CompleteTaskToolTest {

    @Test
    fun `missing status is invalid`() {
        val tool = CompleteTaskTool()

        val result = tool.validate(JSONObject().put("answer", "ok"))

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `invalid status is rejected`() {
        val tool = CompleteTaskTool()
        val params = JSONObject()
            .put("status", "maybe")
            .put("answer", "ok")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `blank answer is invalid`() {
        val tool = CompleteTaskTool()
        val params = JSONObject()
            .put("status", "success")
            .put("answer", " ")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `valid success passes`() {
        val tool = CompleteTaskTool()
        val params = JSONObject()
            .put("status", "success")
            .put("answer", "done")

        val result = tool.validate(params)

        assertThat(result).isEqualTo(ValidationResult.Valid)
    }
}
