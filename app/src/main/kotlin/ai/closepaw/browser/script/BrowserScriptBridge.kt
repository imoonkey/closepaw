package ai.closepaw.browser.script

import ai.closepaw.browser.cdp.CdpException
import ai.closepaw.browser.cdp.CdpOptions
import ai.closepaw.browser.cdp.ChromeCdpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

fun interface JsEvaluator {
    fun evaluate(script: String)
}

sealed class ScriptResult {
    data class Ok(val resultJson: String?) : ScriptResult()
    data class Failure(val message: String, val stack: String?) : ScriptResult()
    data class Timeout(val timeoutMs: Long) : ScriptResult()
    data class Cancelled(val reason: String) : ScriptResult()
    data class HostError(val cause: Throwable) : ScriptResult()
}

class BrowserScriptBridge(
    private val cdpClient: ChromeCdpClient,
    private val evaluator: JsEvaluator,
    private val scope: CoroutineScope,
) {
    private val pendingJobs = ConcurrentHashMap<Int, Job>()
    private val terminated = AtomicBoolean(false)
    private val resultDeferred = CompletableDeferred<ScriptResult>()

    val isTerminated: Boolean get() = terminated.get()

    fun handleSend(message: String) {
        if (terminated.get()) return
        when (val outcome = parseRequest(message)) {
            is ParseOutcome.Bad -> {
                if (outcome.id != null) {
                    evaluator.evaluate(
                        rejectScript(
                            id = outcome.id,
                            name = "BrowserScriptError",
                            code = MALFORMED_REQUEST_CODE,
                            message = "Malformed cdp() request: ${outcome.reason}",
                        ),
                    )
                }
                return
            }
            is ParseOutcome.Ok -> dispatch(outcome.request)
        }
    }

    private fun dispatch(request: Request) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = cdpClient.send(request.method, request.params, request.options)
                if (terminated.get()) return@launch
                evaluator.evaluate(resolveScript(request.id, result))
            } catch (ce: CancellationException) {
                throw ce
            } catch (cdp: CdpException) {
                if (terminated.get()) return@launch
                evaluator.evaluate(
                    rejectScript(
                        id = request.id,
                        name = "CdpException",
                        code = cdp.code,
                        message = cdp.message,
                        cause = cdp.cause?.toCausePair(),
                    ),
                )
            } catch (t: Throwable) {
                if (terminated.get()) return@launch
                evaluator.evaluate(
                    rejectScript(
                        id = request.id,
                        name = t::class.java.simpleName ?: "Error",
                        code = null,
                        message = t.message ?: t.toString(),
                        cause = t.cause?.toCausePair(),
                    ),
                )
            } finally {
                pendingJobs.remove(request.id)
            }
        }
        pendingJobs[request.id] = job
        if (terminated.get()) {
            job.cancel()
            pendingJobs.remove(request.id)
        } else {
            job.start()
        }
    }

    fun handleDone(message: String) {
        if (!terminated.compareAndSet(false, true)) return
        val outcome = parseDone(message)
        cancelPendingJobs()
        resultDeferred.complete(outcome)
    }

    fun cancelPending(reason: String) {
        if (!terminated.compareAndSet(false, true)) return
        cancelPendingJobs()
        resultDeferred.complete(ScriptResult.Cancelled(reason))
    }

    fun completeWithTimeout(timeoutMs: Long) {
        if (!terminated.compareAndSet(false, true)) return
        cancelPendingJobs()
        resultDeferred.complete(ScriptResult.Timeout(timeoutMs))
    }

    fun completeWithHostError(cause: Throwable) {
        if (!terminated.compareAndSet(false, true)) return
        cancelPendingJobs()
        resultDeferred.complete(ScriptResult.HostError(cause))
    }

    suspend fun awaitResult(): ScriptResult = resultDeferred.await()

    private fun cancelPendingJobs() {
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
    }

    private data class Request(
        val id: Int,
        val method: String,
        val params: JsonObject,
        val options: CdpOptions,
    )

    private sealed class ParseOutcome {
        data class Ok(val request: Request) : ParseOutcome()
        data class Bad(val id: Int?, val reason: String) : ParseOutcome()
    }

    private fun parseRequest(text: String): ParseOutcome {
        val obj = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Throwable) {
            return ParseOutcome.Bad(null, "not a JSON object")
        }
        val id = obj["id"]?.jsonPrimitive?.intOrNull
            ?: return ParseOutcome.Bad(null, "missing or non-integer id")
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
            ?: return ParseOutcome.Bad(id, "missing or non-string method")

        val params = when (val p = obj["params"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> p
            else -> return ParseOutcome.Bad(id, "params must be a JSON object")
        }
        val optionsObj = when (val o = obj["options"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> o
            else -> return ParseOutcome.Bad(id, "options must be a JSON object")
        }
        return ParseOutcome.Ok(
            Request(
                id = id,
                method = method,
                params = params,
                options = CdpOptions(
                    sessionId = optionsObj["sessionId"]?.jsonPrimitive?.contentOrNull,
                    targetId = optionsObj["targetId"]?.jsonPrimitive?.contentOrNull,
                ),
            ),
        )
    }

    private fun parseDone(text: String): ScriptResult = try {
        val obj = Json.parseToJsonElement(text).jsonObject
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (ok) {
            ScriptResult.Ok(obj["result"]?.toString())
        } else {
            val err = (obj["error"] as? JsonObject)
            val message = err?.get("message")?.jsonPrimitive?.contentOrNull ?: "Unknown error"
            val stack = err?.get("stack")?.jsonPrimitive?.contentOrNull
            ScriptResult.Failure(message, stack)
        }
    } catch (t: Throwable) {
        ScriptResult.Failure("Malformed done payload: ${t.message}", null)
    }

    companion object {
        const val MALFORMED_REQUEST_CODE: Int = -32602
    }
}

internal fun resolveScript(id: Int, result: JsonElement): String =
    "globalThis.__cdpResolve($id, $result);"

internal fun rejectScript(
    id: Int,
    message: String,
    code: Int? = null,
    name: String? = null,
    cause: Pair<String, String>? = null,
): String {
    val payload = buildJsonObject {
        put("message", message)
        if (code != null) put("code", code)
        if (name != null) put("name", name)
        if (cause != null) {
            put(
                "cause",
                buildJsonObject {
                    put("name", cause.first)
                    put("message", cause.second)
                },
            )
        }
    }
    return "globalThis.__cdpReject($id, $payload);"
}

private fun Throwable.toCausePair(): Pair<String, String> =
    (this::class.java.simpleName ?: "Throwable") to (message ?: toString())
