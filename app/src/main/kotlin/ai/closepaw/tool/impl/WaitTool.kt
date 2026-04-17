package ai.closepaw.tool.impl

import ai.closepaw.platform.UIAction
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.handlers.UIActionInvocation
import org.json.JSONArray
import org.json.JSONObject

/**
 * WaitTool - deterministic wait without screen targeting.
 */
class WaitTool : ToolSpec {
    companion object {
        private const val DEFAULT_WAIT_MS = 1000L
        private const val MAX_WAIT_MS = 30_000L
    }

    override val name: String = "wait"

    override val description: String = """
Wait for UI updates to settle when transitions, animations, or async loading are in progress.
""".trimIndent()

    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "agent_thought",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "Brief reason for waiting")
                    }
                )
                put(
                    "duration_ms",
                    JSONObject().apply {
                        put("type", "integer")
                        put("description", "Wait duration in milliseconds (default 1000, max 30000)")
                    }
                )
            }
        )
        put("required", JSONArray())
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject): ValidationResult {
        val durationMs = params.optLong("duration_ms", DEFAULT_WAIT_MS)
        if (durationMs < 0) {
            return ValidationResult.Invalid("duration_ms must be non-negative")
        }
        if (durationMs > MAX_WAIT_MS) {
            return ValidationResult.Invalid("duration_ms must be <= $MAX_WAIT_MS")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val durationMs = params.optLong("duration_ms", DEFAULT_WAIT_MS)
        return UIActionInvocation(
            toolName = name,
            params = params,
            description = "Wait ${durationMs}ms for UI to settle",
            uiAction = UIAction.Wait(durationMs)
        )
    }
}
