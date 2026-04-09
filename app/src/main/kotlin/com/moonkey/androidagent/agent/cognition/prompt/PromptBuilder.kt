package com.moonkey.androidagent.agent.cognition.prompt

import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.model.ScreenImage
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
 *   1. HISTORY  — past turns (compression handled by HistoryManager)
 *   2. MEMORY   — working memory (scratchpad + todos)
 *   3. APP SKILL — active package guidance loaded per turn
 *   4. OBSERVATION — current screen state + warnings + screenshot
 */
internal class PromptBuilder(
    private val historyManager: HistoryManager,
    private val sessionState: AgentSessionState,
    private val supportsVision: Boolean = true
) {

    /**
     * Assemble all input items for one LLM call.
     *
     * @param observation  Canonical turn observation (screen state + image)
     * @param warnings  Plain-text warning strings (loop detection, final turn, etc.)
     * @param turnNumber Current turn number (1-based)
     * @param maxTurns  Maximum turns allowed for this session
     * @param appSkill Optional app-specific skill block for the foreground package
     * @param recalledMemory Optional recalled long-term memory block
     */
    fun buildInputItems(
        observation: TurnObservation,
        warnings: List<String> = emptyList(),
        turnNumber: Int = 0,
        maxTurns: Int = 0,
        appSkill: String? = null,
        recalledMemory: String? = null
    ): List<ResponseInputItem> = buildList {
        addAll(buildHistorySection())
        buildMemorySection()?.let { add(it) }
        recalledMemory?.trim()?.takeIf { it.isNotEmpty() }?.let { add(textUserMessage(it)) }
        appSkill?.trim()?.takeIf { it.isNotEmpty() }?.let { add(textUserMessage(it)) }
        add(buildObservationSection(observation, warnings, turnNumber, maxTurns))
    }

    // ── History ──────────────────────────────────────────────────────────

    /**
     * History section is a direct pass-through of [HistoryManager.forPrompt].
     * Screen compression is handled proactively by HistoryManager on addItem().
     */
    private fun buildHistorySection(): List<ResponseInputItem> {
        return historyManager.forPrompt().mapNotNull { it.toResponseInputItem() }
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
        val hasScratchpad = !scratchpadContext.startsWith("(empty)")

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
        observation: TurnObservation,
        warnings: List<String>,
        turnNumber: Int,
        maxTurns: Int
    ): ResponseInputItem {
        val text = buildObservationText(observation, warnings, turnNumber, maxTurns)
        return if (observation.image != null && supportsVision) {
            imageUserMessage(text, observation.image)
        } else {
            textUserMessage(text)
        }
    }

    /**
     * Produces the text body for the current-observation message.
     *
     * Turn-specific decorations (budget, warnings, screenshot note, no-a11y guidance)
     * are layered around the canonical [TurnObservation.screenBlock].
     *
     * Package-visible for testing.
     */
    internal fun buildObservationText(
        observation: TurnObservation,
        warnings: List<String>,
        turnNumber: Int = 0,
        maxTurns: Int = 0
    ): String {
        return buildString {
            // Turn budget — always shown so agent can plan resource usage
            if (turnNumber > 0 && maxTurns > 0) {
                appendLine("Turn $turnNumber/$maxTurns")
                appendLine()
            }

            // Warnings — prime interpretation before JSON
            for (warning in warnings) {
                appendLine(warning)
            }
            if (warnings.isNotEmpty()) appendLine()

            // Screen state — canonical block shared with history.
            append(observation.screenBlock)
            if (!observation.hasAccessibility) {
                appendLine()
                append("Use coordinate-based actions (x, y) or analyze the screenshot visually.")
            }

            // Screenshot section
            if (observation.image != null && supportsVision) {
                if (observation.hasAccessibility) appendLine()
                appendLine()
                append("Screenshot attached (analyze visually if needed).")
            }
        }.trim()
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
}
