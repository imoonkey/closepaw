package ai.closepaw.tool.impl

import ai.closepaw.browser.script.ScriptResult
import org.json.JSONObject

/**
 * Acquire the browser script invoker if every capability gate passes, or return a
 * structured Unavailable outcome the tool surfaces verbatim. Consulted at execution
 * time so policy and runtime state changes between turns are honored.
 */
interface BrowserScriptCapabilityGate {
    suspend fun acquire(): Outcome

    sealed class Outcome {
        data class Available(val invoker: BrowserScriptInvoker) : Outcome()
        data class Unavailable(val code: String, val reason: String) : Outcome()
    }
}

/** Test-seam over `BrowserScriptRunner.run` so the tool stays Android-free. */
fun interface BrowserScriptInvoker {
    suspend fun run(script: String, timeoutMs: Long): ScriptResult
}

/** Sink for raw script + raw runner output + timing. Implemented over TraceRecorder. */
fun interface BrowserScriptTraceSink {
    fun record(metadata: BrowserScriptTraceMetadata)
}

/** Categorical outcomes match the design's taxonomy and stay stable for trace consumers. */
object BrowserScriptOutcome {
    const val OK: String = "ok"
    const val CAPABILITY_UNAVAILABLE: String = "capability_unavailable"
    const val SCRIPT_FAILURE: String = "script_failure"
    const val RUNNER_TIMEOUT: String = "runner_timeout"
    const val CANCELLATION: String = "cancellation"
    const val HOST_ERROR: String = "host_error"
}

enum class BrowserScriptOutcomeSeverity { TRANSIENT, PERMANENT }

data class BrowserScriptTraceMetadata(
    val callId: String?,
    val script: String,
    val timeoutMs: Long,
    val durationMs: Long,
    /** One of [BrowserScriptOutcome] constants. */
    val outcome: String,
    /** Sub-code (e.g. shizuku_permission_missing for capability_unavailable). */
    val outcomeCode: String?,
    /** TRANSIENT for environment/runtime issues; PERMANENT for agent-script bugs. Null on OK. */
    val severity: BrowserScriptOutcomeSeverity?,
    val retryable: Boolean,
    /** Full serialized runner result (success payload OR error JSON), never the user-facing text. */
    val rawResultJson: String?,
    val errorMessage: String?,
    val originalChars: Int,
    val truncatedChars: Int,
)

/** Serialize the raw runner outcome for trace persistence — never the user-facing text. */
internal fun runnerResultToJson(result: ScriptResult): String {
    val obj = JSONObject()
    when (result) {
        is ScriptResult.Ok -> {
            obj.put("kind", "ok")
            obj.put("result_json", result.resultJson ?: JSONObject.NULL)
        }
        is ScriptResult.Failure -> {
            obj.put("kind", "failure")
            obj.put("message", result.message)
            obj.put("stack", result.stack ?: JSONObject.NULL)
        }
        is ScriptResult.Timeout -> {
            obj.put("kind", "timeout")
            obj.put("timeout_ms", result.timeoutMs)
        }
        is ScriptResult.Cancelled -> {
            obj.put("kind", "cancelled")
            obj.put("reason", result.reason)
        }
        is ScriptResult.HostError -> {
            obj.put("kind", "host_error")
            obj.put("class", result.cause::class.java.simpleName ?: "Throwable")
            obj.put("message", result.cause.message ?: JSONObject.NULL)
        }
    }
    return obj.toString()
}

internal fun runnerThrowableJson(t: Throwable): String =
    JSONObject().apply {
        put("kind", "host_throwable")
        put("class", t::class.java.simpleName ?: "Throwable")
        put("message", t.message ?: JSONObject.NULL)
    }.toString()
