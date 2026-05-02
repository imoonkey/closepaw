package ai.closepaw.tool.impl

import ai.closepaw.browser.script.ScriptResult
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.textToolSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single agent-facing entry point for the Browser CDP runtime: strict validation,
 * execution-time capability gate, cooperative in-flight cancellation, compact bounded
 * output, and full raw runner payload persisted to the trace sink.
 */
class BrowserScriptTool(
    private val capabilityGate: BrowserScriptCapabilityGate,
    private val traceSink: BrowserScriptTraceSink? = null,
    private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxTimeoutMs: Long = MAX_TIMEOUT_MS,
    private val cancellationPollMs: Long = DEFAULT_CANCELLATION_POLL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ToolSpec {

    init {
        require(maxOutputChars >= MIN_OUTPUT_CHARS) {
            "maxOutputChars=$maxOutputChars below minimum $MIN_OUTPUT_CHARS (must fit truncation marker)"
        }
    }

    override val name: String = TOOL_NAME

    override val description: String = """
        Run an automation script against the user's real Chrome browser via raw Chrome DevTools
        Protocol. Inside the script call `await cdp(method, params, options)` — loops, branches,
        parsing and retries all happen in one tool call.
        For richer examples and reusable snippets (pageJs, waitForLoad, screenshot, tab/input
        helpers), activate the bundled `browser-use` agent skill — SKILL.md indexes installed
        snippet files you can read with `shell` and inline into your script.
        Gated at execution time on experimental flag, Shizuku authorization, and Chrome's
        DevTools socket — any gate failure returns an actionable setup error.
    """.trimIndent()

    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("script", JSONObject().apply {
                put("type", "string")
                put(
                    "description",
                    "JavaScript automation script. Use `await cdp(method, params, options)` to " +
                        "call Chrome DevTools Protocol. Activate the `browser-use` agent skill " +
                        "for richer examples and reusable snippets.",
                )
            })
            put("timeout_ms", JSONObject().apply {
                put("type", "integer")
                put(
                    "description",
                    "Execution timeout in milliseconds. Defaults to $DEFAULT_TIMEOUT_MS, capped " +
                        "at $MAX_TIMEOUT_MS by the runtime.",
                )
            })
        })
        put("required", JSONArray(listOf("script")))
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject): ValidationResult {
        if (!params.has("script") || params.isNull("script")) {
            return ValidationResult.Invalid("Missing required parameter: script")
        }
        val scriptValue = params.opt("script")
        if (scriptValue !is String) {
            return ValidationResult.Invalid("script must be a string")
        }
        if (scriptValue.isBlank()) {
            return ValidationResult.Invalid("script must be non-blank")
        }
        if (params.has("timeout_ms") && !params.isNull("timeout_ms")) {
            // Reject Double/Float/String — fractional or string timeouts must not be silently
            // coerced (org.json's getLong truncates 1.5 to 1).
            val asLong: Long = when (val raw = params.opt("timeout_ms")) {
                is Int -> raw.toLong()
                is Long -> raw
                else -> return ValidationResult.Invalid(
                    "timeout_ms must be a positive integer (got ${raw?.javaClass?.simpleName ?: "null"})",
                )
            }
            if (asLong <= 0) {
                return ValidationResult.Invalid("timeout_ms must be a positive integer")
            }
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val script = params.getString("script")
        val rawTimeout = if (params.has("timeout_ms") && !params.isNull("timeout_ms")) {
            (params.get("timeout_ms") as Number).toLong()
        } else {
            defaultTimeoutMs
        }
        return BrowserScriptInvocation(
            params = params,
            script = script,
            timeoutMs = rawTimeout.coerceAtMost(maxTimeoutMs),
            capabilityGate = capabilityGate,
            traceSink = traceSink,
            maxOutputChars = maxOutputChars,
            cancellationPollMs = cancellationPollMs,
            clock = clock,
        )
    }

    companion object {
        const val TOOL_NAME: String = "browser_script"
        const val DEFAULT_MAX_OUTPUT_CHARS: Int = 8192
        const val MIN_OUTPUT_CHARS: Int = 64
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
        const val MAX_TIMEOUT_MS: Long = 120_000L
        const val DEFAULT_CANCELLATION_POLL_MS: Long = 50L
    }
}

private class BrowserScriptInvocation(
    override val params: JSONObject,
    private val script: String,
    private val timeoutMs: Long,
    private val capabilityGate: BrowserScriptCapabilityGate,
    private val traceSink: BrowserScriptTraceSink?,
    private val maxOutputChars: Int,
    private val cancellationPollMs: Long,
    private val clock: () -> Long,
) : ToolInvocation {

    override val toolName: String = BrowserScriptTool.TOOL_NAME

    override fun getDescription(): String {
        val firstLine = script.lineSequence().firstOrNull().orEmpty().trim()
        val preview = if (firstLine.length > 80) firstLine.take(77) + "..." else firstLine
        return "Run browser_script (timeout=${timeoutMs}ms): $preview"
    }

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }
        // Captured before the watchdog scope so a mid-execution cancellation reports real
        // elapsed time, not 0ms.
        val started = clock()
        return try {
            coroutineScope {
                val watchdog = launch {
                    while (isActive) {
                        if (context.isCancelled()) {
                            this@coroutineScope.cancel(
                                CancellationException("Cancelled mid-execution"),
                            )
                            return@launch
                        }
                        delay(cancellationPollMs)
                    }
                }
                try {
                    runOnce(context)
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (ce: CancellationException) {
            // Watchdog-initiated → return Cancelled. Genuine parent cancellation → propagate.
            if (context.isCancelled()) {
                val reason = ce.message ?: "Cancelled mid-execution"
                trace(
                    callId = context.callId,
                    outcome = BrowserScriptOutcome.CANCELLATION,
                    outcomeCode = null,
                    severity = BrowserScriptOutcomeSeverity.TRANSIENT,
                    retryable = false,
                    durationMs = clock() - started,
                    rawResult = null,
                    errorMessage = reason,
                    original = reason.length,
                    truncated = reason.length,
                )
                ToolExecutionResult.Cancelled(reason)
            } else {
                throw ce
            }
        }
    }

    private suspend fun runOnce(context: ToolExecutionContext): ToolExecutionResult {
        val gateOutcome = try {
            capabilityGate.acquire()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return failClosed(
                callId = context.callId,
                outcomeCode = "probe_error",
                message = "browser_script capability check failed: ${t.message ?: t::class.java.simpleName}",
                cause = t,
            )
        }

        val invoker = when (gateOutcome) {
            is BrowserScriptCapabilityGate.Outcome.Unavailable -> return failClosed(
                callId = context.callId,
                outcomeCode = gateOutcome.code,
                message = "browser_script unavailable (${gateOutcome.code}): ${gateOutcome.reason}",
                cause = null,
            )
            is BrowserScriptCapabilityGate.Outcome.Available -> gateOutcome.invoker
        }

        val started = clock()
        val scriptResult = try {
            invoker.run(script, timeoutMs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val elapsed = clock() - started
            val msg = "browser_script host error: ${t.message ?: t::class.java.simpleName}"
            trace(
                callId = context.callId,
                outcome = BrowserScriptOutcome.HOST_ERROR,
                outcomeCode = t::class.java.simpleName,
                severity = BrowserScriptOutcomeSeverity.TRANSIENT,
                retryable = true,
                durationMs = elapsed,
                rawResult = runnerThrowableJson(t),
                errorMessage = msg,
                original = msg.length,
                truncated = msg.length,
            )
            return ToolExecutionResult.Failure(msg, t)
        }
        return finish(context.callId, scriptResult, clock() - started)
    }

    private fun finish(
        callId: String?,
        result: ScriptResult,
        durationMs: Long,
    ): ToolExecutionResult {
        val rawJson = runnerResultToJson(result)
        val profile = profileOf(result)
        val (userText, returnResult) = userFacing(result)
        val (compact, original, truncated) = compactOutput(userText)
        trace(
            callId = callId,
            outcome = profile.outcome,
            outcomeCode = profile.code,
            severity = profile.severity,
            retryable = profile.retryable,
            durationMs = durationMs,
            rawResult = rawJson,
            errorMessage = profile.errorMessage(result),
            original = original,
            truncated = truncated,
        )
        return when (returnResult) {
            ReturnKind.SUCCESS -> textToolSuccess(compact)
            ReturnKind.FAILURE -> ToolExecutionResult.Failure(
                compact,
                (result as? ScriptResult.HostError)?.cause,
            )
            ReturnKind.CANCELLED -> ToolExecutionResult.Cancelled(compact)
        }
    }

    private fun failClosed(
        callId: String?,
        outcomeCode: String,
        message: String,
        cause: Throwable?,
    ): ToolExecutionResult {
        trace(
            callId = callId,
            outcome = BrowserScriptOutcome.CAPABILITY_UNAVAILABLE,
            outcomeCode = outcomeCode,
            severity = BrowserScriptOutcomeSeverity.TRANSIENT,
            retryable = true,
            durationMs = 0L,
            rawResult = cause?.let(::runnerThrowableJson),
            errorMessage = message,
            original = message.length,
            truncated = message.length,
        )
        return ToolExecutionResult.Failure(message, cause)
    }

    private fun compactOutput(raw: String): Triple<String, Int, Int> {
        val original = raw.length
        if (original <= maxOutputChars) return Triple(raw, original, original)
        val marker = "\n[truncated: original_chars=$original]"
        val headLen = (maxOutputChars - marker.length).coerceAtLeast(0)
        val head = raw.substring(0, minOf(headLen, raw.length))
        return Triple(head + marker, original, headLen)
    }

    private fun trace(
        callId: String?,
        outcome: String,
        outcomeCode: String?,
        severity: BrowserScriptOutcomeSeverity?,
        retryable: Boolean,
        durationMs: Long,
        rawResult: String?,
        errorMessage: String?,
        original: Int,
        truncated: Int,
    ) {
        val sink = traceSink ?: return
        sink.record(
            BrowserScriptTraceMetadata(
                callId = callId,
                script = script,
                timeoutMs = timeoutMs,
                durationMs = durationMs,
                outcome = outcome,
                outcomeCode = outcomeCode,
                severity = severity,
                retryable = retryable,
                rawResultJson = rawResult,
                errorMessage = errorMessage,
                originalChars = original,
                truncatedChars = truncated,
            ),
        )
    }

    private enum class ReturnKind { SUCCESS, FAILURE, CANCELLED }

    private data class OutcomeProfile(
        val outcome: String,
        val code: String?,
        val severity: BrowserScriptOutcomeSeverity?,
        val retryable: Boolean,
    ) {
        fun errorMessage(result: ScriptResult): String? = when (result) {
            is ScriptResult.Ok -> null
            is ScriptResult.Failure -> result.message
            is ScriptResult.Timeout -> "browser_script timed out after ${result.timeoutMs}ms"
            is ScriptResult.Cancelled -> result.reason
            is ScriptResult.HostError -> result.cause.message ?: result.cause::class.java.simpleName
        }
    }

    private fun profileOf(result: ScriptResult): OutcomeProfile = when (result) {
        is ScriptResult.Ok -> OutcomeProfile(BrowserScriptOutcome.OK, null, null, false)
        is ScriptResult.Failure -> OutcomeProfile(
            BrowserScriptOutcome.SCRIPT_FAILURE, null,
            BrowserScriptOutcomeSeverity.PERMANENT, false,
        )
        is ScriptResult.Timeout -> OutcomeProfile(
            BrowserScriptOutcome.RUNNER_TIMEOUT, null,
            BrowserScriptOutcomeSeverity.TRANSIENT, true,
        )
        is ScriptResult.Cancelled -> OutcomeProfile(
            BrowserScriptOutcome.CANCELLATION, null,
            BrowserScriptOutcomeSeverity.TRANSIENT, false,
        )
        is ScriptResult.HostError -> OutcomeProfile(
            BrowserScriptOutcome.HOST_ERROR, result.cause::class.java.simpleName,
            BrowserScriptOutcomeSeverity.TRANSIENT, true,
        )
    }

    private fun userFacing(result: ScriptResult): Pair<String, ReturnKind> = when (result) {
        is ScriptResult.Ok -> (result.resultJson ?: "null") to ReturnKind.SUCCESS
        is ScriptResult.Failure -> formatFailure(result) to ReturnKind.FAILURE
        is ScriptResult.Timeout ->
            "browser_script timed out after ${result.timeoutMs}ms" to ReturnKind.FAILURE
        is ScriptResult.Cancelled -> result.reason to ReturnKind.CANCELLED
        is ScriptResult.HostError -> {
            val c = result.cause
            "browser_script host error: ${c.message ?: c::class.java.simpleName}" to ReturnKind.FAILURE
        }
    }

    private fun formatFailure(result: ScriptResult.Failure): String {
        val base = "browser_script error: ${result.message}"
        val stack = result.stack?.trim().orEmpty()
        return if (stack.isEmpty()) base else "$base\n$stack"
    }
}
