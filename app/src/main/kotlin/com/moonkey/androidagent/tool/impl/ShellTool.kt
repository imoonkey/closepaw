package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.textToolSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ShellTool : ToolSpec {
    override val name: String = "shell"

    override val description: String =
        """
        Execute a shell command on the device for file-oriented inspection only.

        Scope:
        - Read file contents: cat /path/to/file
        - List directories: ls /path/to/dir
        - Inspect metadata: stat /path/to/file

        Do NOT use for:
        - UI control, app launching, taps, swipes, or system navigation
        - Protected app-internal storage you are unlikely to access
        - OCR, image reading, or non-file commands such as date, dumpsys, input, am
        - Destructive or state-changing commands
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
        return ShellInvocation(command = command, params = params)
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10L
        private const val MAX_OUTPUT_CHARS = 4096

        private val BLOCKED_COMMANDS = setOf(
            "rm", "mv", "cp", "chmod", "chown",
            "pm", "am", "settings", "reboot",
            "su", "sh", "bash", "eval", "exec"
        )
    }

    private class ShellInvocation(
        private val command: String,
        override val params: JSONObject
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

                    val completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!completed) {
                        process.destroyForcibly()
                        outputDeferred.cancel()
                        return@withContext ToolExecutionResult.Failure(
                            "Command timed out after ${TIMEOUT_SECONDS}s"
                        )
                    }

                    val output = outputDeferred.await()
                    val exitCode = process.exitValue()
                    textToolSuccess(output = "exit=$exitCode\n$output")
                } catch (e: Exception) {
                    ToolExecutionResult.Failure("Shell execution failed: ${e.message}", e)
                }
            }
        }
    }
}
