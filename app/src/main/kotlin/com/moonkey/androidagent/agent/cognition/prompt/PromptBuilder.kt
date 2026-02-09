package com.moonkey.androidagent.agent.cognition.prompt

import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.session.AgentSessionState
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputImage
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText

/**
 * Builds the complete input items list for one LLM turn.
 *
 * Prompt is a sequential narrative the LLM reads left-to-right:
 *   1. HISTORY  — past turns with screen observation compression
 *   2. MEMORY   — working memory (scratchpad + todos)
 *   3. OBSERVATION — current screen state + warnings + screenshot
 *
 * Replaces the fragmented pipeline of PromptContext → PromptUtils → Turn.buildInputItems().
 */
internal class PromptBuilder(
    private val historyManager: HistoryManager,
    private val sessionState: AgentSessionState,
    private val llmBackend: LLMBackendType,
    private val recentFullScreenTurns: Int = 3
) {

    /**
     * Assemble all input items for one LLM call.
     *
     * @param snapshot  Current screen capture
     * @param image     Optional screenshot (attached when using OpenAI backend)
     * @param warnings  Plain-text warning strings (loop detection, final turn, etc.)
     */
    fun buildInputItems(
        snapshot: ScreenSnapshot,
        image: ScreenImage?,
        warnings: List<String> = emptyList()
    ): List<ResponseInputItem> = buildList {
        addAll(buildHistorySection())
        buildMemorySection()?.let { add(it) }
        add(buildObservationSection(snapshot, image, warnings))
    }

    // ── History ──────────────────────────────────────────────────────────

    private fun buildHistorySection(): List<ResponseInputItem> {
        val history = historyManager.forPrompt()
        val compressed = compressOldScreenObservations(history)
        return compressed.mapNotNull { it.toResponseInputItem() }
    }

    // ── Memory ──────────────────────────────────────────────────────────

    /**
     * Build a single "Working Memory" user message (todos + scratchpad).
     * Returns null when both are empty — no noise for early turns.
     */
    private fun buildMemorySection(): ResponseInputItem? {
        val text = buildMemoryText() ?: return null
        return textUserMessage(text)
    }

    /**
     * Produces the text body for the memory message.
     * Package-visible for testing.
     */
    internal fun buildMemoryText(): String? {
        val todoContext = sessionState.todos.toPromptContext()
        val scratchpadContext = sessionState.scratchpad.toPromptContext()
        val hasTodos = todoContext.isNotEmpty()
        val hasScratchpad = !scratchpadContext.startsWith("- (empty)")

        if (!hasTodos && !hasScratchpad) return null

        return buildString {
            appendLine("## Working Memory")
            if (hasTodos) {
                appendLine()
                appendLine("### Todo List")
                append(todoContext)
            }
            if (hasScratchpad) {
                if (hasTodos) appendLine()
                appendLine()
                appendLine("### Scratchpad")
                append(scratchpadContext)
            }
        }.trim()
    }

    // ── Current Observation ─────────────────────────────────────────────

    private fun buildObservationSection(
        snapshot: ScreenSnapshot,
        image: ScreenImage?,
        warnings: List<String>
    ): ResponseInputItem {
        val text = buildObservationText(snapshot, image, warnings)
        return if (image != null && llmBackend == LLMBackendType.OPENAI) {
            imageUserMessage(text, image)
        } else {
            textUserMessage(text)
        }
    }

    /**
     * Produces the text body for the current-observation message.
     * Package-visible for testing.
     */
    internal fun buildObservationText(
        snapshot: ScreenSnapshot,
        image: ScreenImage?,
        warnings: List<String>
    ): String {
        val screenJson = Perceptor.toPromptJson(snapshot)
        return buildString {
            // Warnings first — prime interpretation before JSON
            for (warning in warnings) {
                appendLine(warning)
            }
            if (warnings.isNotEmpty()) appendLine()

            appendLine("Screen state (${snapshot.elements.size} elements):")
            appendLine("```json")
            appendLine(screenJson)
            append("```")

            if (image != null && llmBackend == LLMBackendType.OPENAI) {
                appendLine()
                appendLine()
                append("Screenshot attached (compressed).")
            }
        }.trim()
    }

    // ── Screen Compression ──────────────────────────────────────────────

    /**
     * Keep the last [recentFullScreenTurns] screen observations as-is;
     * compress older ones to a one-line summary.
     * Package-visible for testing.
     */
    internal fun compressOldScreenObservations(
        items: List<ResponseItem>
    ): List<ResponseItem> {
        val screenIndices = items.withIndex()
            .filter { (_, item) ->
                item is ResponseItem.Message && item.isScreenObservation
            }
            .map { it.index }

        if (screenIndices.size <= recentFullScreenTurns) return items

        val toCompress = screenIndices.dropLast(recentFullScreenTurns).toSet()
        return items.mapIndexed { idx, item ->
            if (idx in toCompress) {
                val msg = item as ResponseItem.Message
                msg.copy(content = compressScreenContent(msg.content))
            } else {
                item
            }
        }
    }

    /**
     * Distill a full screen-state message to a compact summary.
     * Package-visible for testing.
     */
    internal fun compressScreenContent(fullContent: String): String {
        val count = ELEMENT_COUNT_REGEX.find(fullContent)
            ?.groupValues?.get(1) ?: "?"
        return "Screen: $count elements (compressed)"
    }

    // ── ResponseItem → ResponseInputItem ────────────────────────────────

    private fun ResponseItem.toResponseInputItem(): ResponseInputItem? = when (this) {
        is ResponseItem.Message -> {
            val easyRole = when (role) {
                "user" -> EasyInputMessage.Role.USER
                "assistant" -> EasyInputMessage.Role.ASSISTANT
                else -> null
            }
            easyRole?.let { role ->
                ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder().role(role).content(content).build()
                )
            }
        }
        is ResponseItem.FunctionCall -> ResponseInputItem.ofFunctionCall(
            ResponseFunctionToolCall.builder()
                .callId(id)
                .name(name)
                .arguments(arguments.toString())
                .build()
        )
        is ResponseItem.FunctionCallOutput -> ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder()
                .callId(callId)
                .output(content)
                .build()
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun textUserMessage(text: String): ResponseInputItem =
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content(text)
                .build()
        )

    private fun imageUserMessage(text: String, image: ScreenImage): ResponseInputItem =
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .contentOfResponseInputMessageContentList(
                    listOf(
                        ResponseInputContent.ofInputText(
                            ResponseInputText.builder().text(text).build()
                        ),
                        ResponseInputContent.ofInputImage(
                            ResponseInputImage.builder()
                                .detail(ResponseInputImage.Detail.AUTO)
                                .imageUrl(image.toDataUrl())
                                .build()
                        )
                    )
                )
                .build()
        )

    companion object {
        private val ELEMENT_COUNT_REGEX = Regex("""Screen state \((\d+) elements\)""")
    }
}
