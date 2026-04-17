package ai.closepaw.tool.impl

import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.textToolSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ShellTool(
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : ToolSpec {
    override val name: String = "shell"

    override val description: String =
        """
        Execute a shell command on the device for file-oriented inspection only (cat, ls, stat).
        Do NOT use for UI control, app launching, protected app storage, or state-changing commands.
        """.trimIndent()

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("command", JSONObject().apply {
                    put("type", "string")
                    put("description", "Shell command to execute")
                })
            })
            put("required", JSONArray(listOf("command")))
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult {
        val command = params.optString("command", "").trim()
        if (command.isEmpty()) {
            return ValidationResult.Invalid("Missing required parameter: command")
        }
        // Reject shell metacharacters that enable chaining/bypassing
        val metaMatch = SHELL_METACHAR_PATTERN.find(command)
        if (metaMatch != null) {
            return ValidationResult.Invalid(
                "Shell metacharacters not allowed: ${metaMatch.value}"
            )
        }
        // Reject destructive commands by first token
        val firstToken = command.split(Regex("\\s+"), limit = 2).first()
            .substringAfterLast('/') // handle full paths like /system/bin/rm
        if (firstToken in BLOCKED_COMMANDS) {
            return ValidationResult.Invalid("Blocked destructive command: $firstToken")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val command = params.getString("command").trim()
        return ShellInvocation(command = command, params = params, timeoutSeconds = timeoutSeconds)
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 10L
        private const val MAX_OUTPUT_CHARS = 4096

        private val BLOCKED_COMMANDS = setOf("am", "pm", "reboot", "su", "env", "xargs", "find")

        // Rejects: ; | & ` > < newline/CR, and any $ (variable expansion/substitution)
        private val SHELL_METACHAR_PATTERN = Regex("[;|&`><\\n\\r\$]")
    }

    private class ShellInvocation(
        private val command: String,
        override val params: JSONObject,
        private val timeoutSeconds: Long,
    ) : ToolInvocation {
        override val toolName: String = "shell"

        override fun getDescription(): String = "Execute: $command"

        override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
            if (context.isCancelled()) {
                return ToolExecutionResult.Cancelled("Cancelled before execution")
            }

            return withContext(Dispatchers.IO) {
                try {
                    val process = ProcessBuilder("sh", "-c", command)
                        .redirectErrorStream(true)
                        .start()

                    // Read output concurrently to prevent pipe deadlock.
                    // Must read BEFORE/during waitFor — if the process fills the
                    // OS pipe buffer (~64KB) before we read, it blocks and waitFor
                    // never returns.
                    val outputDeferred = async(Dispatchers.IO) {
                        process.inputStream.bufferedReader().use { reader ->
                            buildString {
                                val buf = CharArray(1024)
                                while (length < MAX_OUTPUT_CHARS) {
                                    val n = reader.read(
                                        buf, 0,
                                        minOf(buf.size, MAX_OUTPUT_CHARS - length)
                                    )
                                    if (n < 0) break
                                    append(buf, 0, n)
                                }
                            }
                        }
                    }

                    val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                    if (!completed) {
                        process.destroyForcibly()
                        outputDeferred.cancel()
                        return@withContext ToolExecutionResult.Failure(
                            "Command timed out after ${timeoutSeconds}s"
                        )
                    }

                    val output = outputDeferred.await()
                    val exitCode = process.exitValue()
                    val truncationNote = if (output.length >= MAX_OUTPUT_CHARS)
                        "\n[output truncated at $MAX_OUTPUT_CHARS chars]" else ""
                    textToolSuccess(output = "exit=$exitCode\n$output$truncationNote")
                } catch (e: Exception) {
                    ToolExecutionResult.Failure("Shell execution failed: ${e.message}", e)
                }
            }
        }
    }
}
