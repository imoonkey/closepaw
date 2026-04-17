package ai.closepaw.llm

import android.util.Log
import ai.closepaw.BuildConfig
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem

internal object LlmLogger {
    private const val MAX_LOG_LENGTH = 2000
    private val VERBOSE_LOGGING = BuildConfig.DEBUG

    val isVerboseEnabled: Boolean get() = VERBOSE_LOGGING

    fun logInput(
        tag: String,
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>
    ) {
        if (!VERBOSE_LOGGING) return

        Log.i(tag, "╔══════════════════════════════════════════════════════════════")
        Log.i(tag, "║ LLM INPUT")
        Log.i(tag, "╠══════════════════════════════════════════════════════════════")

        Log.i(tag, "║ SYSTEM PROMPT (${systemPrompt.length} chars):")
        logLongMessage(tag, "SYSTEM", systemPrompt)

        Log.i(tag, "║ INPUT ITEMS (${inputItems.size} total):")
        inputItems.forEachIndexed { idx, item ->
            val itemSummary = when {
                item.isEasyInputMessage() -> {
                    val msg = item.asEasyInputMessage()
                    val role = msg.role().toString()
                    val content = ChatCompletionInterop.extractStringContent(msg.content())
                    "[$idx] $role: ${content.take(200)}${if (content.length > 200) "..." else ""}"
                }
                item.isFunctionCall() -> {
                    val call = item.asFunctionCall()
                    "[$idx] FUNCTION_CALL: ${call.name()}(${call.arguments().take(100)})"
                }
                item.isFunctionCallOutput() -> {
                    val output = item.asFunctionCallOutput()
                    val content = output.output().toString()
                    "[$idx] FUNCTION_OUTPUT: ${content.take(200)}${if (content.length > 200) "..." else ""}"
                }
                else -> "[$idx] Unknown type: ${item::class.simpleName}"
            }
            Log.i(tag, "║ $itemSummary")
        }

        Log.i(tag, "║ TOOLS (${tools.size} registered):")
        tools.forEach { tool ->
            Log.i(tag, "║   - ${tool.name()}: ${tool.description().orElse("").take(100)}")
        }

        Log.i(tag, "╚══════════════════════════════════════════════════════════════")
    }

    fun logOutput(tag: String, result: ResponsesResult) {
        if (!VERBOSE_LOGGING) return

        Log.i(tag, "╔══════════════════════════════════════════════════════════════")
        Log.i(tag, "║ LLM OUTPUT")
        Log.i(tag, "╠══════════════════════════════════════════════════════════════")

        if (result.textContent != null) {
            Log.i(tag, "║ TEXT CONTENT (${result.textContent.length} chars):")
            logLongMessage(tag, "OUTPUT", result.textContent)
        } else {
            Log.i(tag, "║ TEXT CONTENT: (none)")
        }

        Log.i(tag, "║ TOOL CALLS (${result.toolCalls.size}):")
        result.toolCalls.forEach { call ->
            Log.i(tag, "║   - ${call.name}(${call.arguments})")
        }

        Log.i(tag, "╚══════════════════════════════════════════════════════════════")
    }

    private fun logLongMessage(tag: String, prefix: String, message: String) {
        val truncated = if (message.length > MAX_LOG_LENGTH) {
            message.take(MAX_LOG_LENGTH) + "...[truncated ${message.length - MAX_LOG_LENGTH} chars]"
        } else {
            message
        }

        truncated.split("\n").forEach { line ->
            if (line.length > 1000) {
                line.chunked(1000).forEach { chunk ->
                    Log.i(tag, "║ [$prefix] $chunk")
                }
            } else {
                Log.i(tag, "║ [$prefix] $line")
            }
        }
    }
}
