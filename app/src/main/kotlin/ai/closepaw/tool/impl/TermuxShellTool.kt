package ai.closepaw.tool.impl

import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolObservation
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TermuxShellTool(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val bridgeBaseUrl: String = DEFAULT_BRIDGE_BASE_URL,
) : ToolSpec {
    override val name: String = "termux_shell"

    override val description: String =
        """
        Execute a full Linux bash command through Termux. Supports pipes, redirects, git,
        python, node, and installed Termux packages. Working directory defaults to the
        Termux workspace root.
        """.trimIndent()

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("command", JSONObject().apply {
                    put("type", "string")
                    put("description", "Bash command to execute in Termux")
                })
                put("cwd", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional working directory inside the Termux workspace")
                })
                put("timeout_seconds", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Command timeout in seconds")
                    put("default", DEFAULT_TIMEOUT_SECONDS)
                    put("maximum", MAX_TIMEOUT_SECONDS)
                })
                put("env", JSONObject().apply {
                    put("type", "object")
                    put("description", "Optional environment variables to pass through")
                    put("additionalProperties", JSONObject().put("type", "string"))
                })
            })
            put("required", JSONArray(listOf("command")))
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()
        val names = params.names()
        if (names != null) {
            for (index in 0 until names.length()) {
                val key = names.optString(index)
                if (key !in ALLOWED_PARAMS) errors += "Unknown parameter: $key"
            }
        }

        val commandValue = params.opt("command")
        val command = commandValue as? String
        if (command == null || command.trim().isEmpty()) {
            errors += "Missing required parameter: command"
        }

        if (params.has("cwd") && !params.isNull("cwd") && params.opt("cwd") !is String) {
            errors += "Parameter cwd must be a string"
        }

        if (params.has("timeout_seconds") && !params.isNull("timeout_seconds")) {
            val timeout = parseTimeoutSeconds(params)
            if (timeout == null || timeout <= 0) {
                errors += "Parameter timeout_seconds must be a positive integer"
            }
        }

        val env = params.opt("env")
        if (env != null && env != JSONObject.NULL) {
            if (env !is JSONObject) {
                errors += "Parameter env must be an object"
            } else {
                validateEnv(env, errors)
            }
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val validation = validate(params)
        val validationErrors = (validation as? ValidationResult.Invalid)?.errors.orEmpty()
        val command = params.optString("command", "").trim()
        val cwd = params.optString("cwd").trim().takeIf { params.has("cwd") && it.isNotEmpty() }
        val timeoutSeconds = parseTimeoutSeconds(params) ?: DEFAULT_TIMEOUT_SECONDS
        val timeoutMs = timeoutSeconds.coerceAtMost(MAX_TIMEOUT_SECONDS) * 1_000L
        val env = (params.opt("env") as? JSONObject)?.copy()

        val invocationParams = JSONObject().apply {
            put("command", command)
            cwd?.let { put("cwd", it) }
            put("timeout_seconds", timeoutSeconds)
            env?.let { put("env", it) }
        }
        return TermuxShellInvocation(
            command = command,
            cwd = cwd,
            timeoutMs = timeoutMs,
            env = env,
            validationErrors = validationErrors,
            params = invocationParams,
            httpClient = httpClient,
            bridgeBaseUrl = bridgeBaseUrl.trimEnd('/')
        )
    }

    companion object {
        const val DEFAULT_BRIDGE_BASE_URL = "http://127.0.0.1:18422"
        private const val DEFAULT_TIMEOUT_SECONDS = 120
        private const val MAX_TIMEOUT_SECONDS = 120
        private const val HTTP_TIMEOUT_GRACE_MS = 5_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ALLOWED_PARAMS = setOf("command", "cwd", "timeout_seconds", "env")

        private fun validateEnv(env: JSONObject, errors: MutableList<String>) {
            val names = env.names() ?: return
            for (index in 0 until names.length()) {
                val key = names.optString(index)
                if (env.opt(key) !is String) {
                    errors += "Environment variable '$key' must be a string"
                }
            }
        }

        private fun parseTimeoutSeconds(params: JSONObject): Int? {
            if (!params.has("timeout_seconds") || params.isNull("timeout_seconds")) {
                return DEFAULT_TIMEOUT_SECONDS
            }
            return when (val raw = params.opt("timeout_seconds")) {
                is Int -> raw
                is Long -> raw.takeIf { it <= Int.MAX_VALUE }?.toInt()
                else -> null
            }
        }
    }

    private class TermuxShellInvocation(
        private val command: String,
        private val cwd: String?,
        private val timeoutMs: Long,
        private val env: JSONObject?,
        private val validationErrors: List<String>,
        override val params: JSONObject,
        private val httpClient: OkHttpClient,
        private val bridgeBaseUrl: String,
    ) : ToolInvocation {
        override val toolName: String = "termux_shell"

        override fun getDescription(): String = "Execute in Termux: $command"

        override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
            if (validationErrors.isNotEmpty()) {
                return failure(
                    reason = "invalid_request",
                    message = validationErrors.joinToString("; ")
                )
            }
            if (context.isCancelled()) {
                return ToolExecutionResult.Cancelled("Cancelled before execution")
            }

            val request = buildRequest()
            val client = clientForTimeout()

            return withContext(Dispatchers.IO) {
                try {
                    client.newCall(request).await().use { response ->
                        mapResponse(response)
                    }
                } catch (e: IOException) {
                    failure(
                        reason = "bridge_unavailable",
                        message = "Bridge daemon not reachable on 127.0.0.1:18422; needs setup.",
                        cause = e
                    )
                } catch (e: JSONException) {
                    failure(
                        reason = "internal_error",
                        message = "Bridge returned malformed JSON: ${e.message}",
                        cause = e
                    )
                }
            }
        }

        private fun buildRequest(): Request {
            val bodyJson = JSONObject().apply {
                put("command", command)
                cwd?.let { put("cwd", it) }
                put("timeout_ms", timeoutMs)
                env?.let { put("env", it) }
            }
            return Request.Builder()
                .url("$bridgeBaseUrl/v1/exec")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        private fun clientForTimeout(): OkHttpClient {
            // timeout_ms + 5000ms grace beyond bridge timeout to avoid OkHttp default 10s killing valid responses.
            val httpTimeoutMs = timeoutMs + HTTP_TIMEOUT_GRACE_MS
            return httpClient.newBuilder()
                .readTimeout(httpTimeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(httpTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }

        private fun mapResponse(response: Response): ToolExecutionResult {
            val body = response.body.string()
            if (response.code == 200) {
                return success(execPayload(JSONObject(body)))
            }
            if (response.code == 409) {
                return failure(
                    reason = "bridge_busy",
                    message = "Bridge is executing another command. Try again shortly."
                )
            }

            val errorJson = body.parseJsonOrNull()
            val bridgeError = errorJson?.optString("error").orEmpty()
            val bridgeMessage = errorJson?.optString("message").orEmpty()
            if (response.code == 400 && bridgeError == "workspace_escape") {
                return failure(
                    reason = "workspace_escape",
                    message = bridgeMessage.ifBlank { "Bridge rejected cwd outside workspace." }
                )
            }
            if (response.code == 400 && bridgeError == "invalid_request") {
                return failure(
                    reason = "invalid_request",
                    message = bridgeMessage.ifBlank { "Bridge rejected invalid request." }
                )
            }

            return failure(
                reason = "internal_error",
                message = "Bridge returned HTTP ${response.code}: ${body.take(512)}"
            )
        }

        private fun execPayload(json: JSONObject): JSONObject {
            val timedOut = json.optBoolean("timed_out", false)
            return JSONObject().apply {
                put("exit_code", if (timedOut) JSONObject.NULL else json.nullable("exit_code"))
                put("stdout", json.optString("stdout", ""))
                put("stderr", json.optString("stderr", ""))
                put("stdout_truncated", json.optBoolean("stdout_truncated", false))
                put("stderr_truncated", json.optBoolean("stderr_truncated", false))
                put("stdout_ref", json.nullable("stdout_ref"))
                put("stderr_ref", json.nullable("stderr_ref"))
                put("timed_out", timedOut)
                put("duration_ms", json.nullable("duration_ms"))
                if (timedOut) {
                    put("message", "Command timed out after ${timeoutMs}ms")
                }
            }
        }

        private fun success(payload: JSONObject): ToolExecutionResult.Success =
            ToolExecutionResult.Success(
                output = payload.toString(2),
                observation = ToolObservation.TextOutput(payload.toString(2))
            )

        private fun failure(
            reason: String,
            message: String,
            cause: Throwable? = null,
        ): ToolExecutionResult.Failure {
            val payload = JSONObject().apply {
                put("reason", reason)
                put("message", message)
            }
            return ToolExecutionResult.Failure(payload.toString(), cause)
        }

        private fun JSONObject.nullable(name: String): Any =
            if (has(name) && !isNull(name)) get(name) else JSONObject.NULL

        private fun String.parseJsonOrNull(): JSONObject? =
            try {
                if (isBlank()) null else JSONObject(this)
            } catch (_: JSONException) {
                null
            }

        private suspend fun Call.await(): Response =
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { cancel() }
                enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (cont.isActive) {
                            cont.resume(response)
                        } else {
                            response.close()
                        }
                    }
                })
            }
    }
}

private fun JSONObject.copy(): JSONObject = JSONObject(toString())
