package ai.closepaw.trace

import ai.closepaw.agent.ToolCallRequest
import ai.closepaw.agent.TurnResult
import ai.closepaw.history.ResponseItem
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.tool.ToolObservation
import com.openai.models.responses.ResponseInputItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal class AgentTraceArtifacts(private val trace: TraceRecorder) {
    fun llmRequestArtifacts(
        turnNumber: Int,
        snapshot: ScreenSnapshot,
        systemPrompt: String,
        userContextText: String,
        history: List<ResponseItem>,
        inputItems: List<ResponseInputItem>
    ): List<TraceArtifactRef> {
        val historyJson = HistoryTraceSerializer.toJson(history)
        val historyArtifact =
            storeRedactedText(
                kind = "llm_history",
                filenameHint = "turn_${turnNumber}_history.json",
                content = encodeRedactedJson(historyJson),
                mimeType = "application/json"
            )
        val systemArtifact =
            storeRedactedText(
                kind = "llm_system_prompt",
                filenameHint = "turn_${turnNumber}_system.txt",
                content = systemPrompt,
                mimeType = "text/plain"
            )
        val contextArtifact =
            storeRedactedText(
                kind = "llm_user_context",
                filenameHint = "turn_${turnNumber}_user_context.txt",
                content = userContextText,
                mimeType = "text/plain"
            )
        val fullPromptArtifact =
            storeRedactedText(
                kind = "llm_full_prompt",
                filenameHint = "turn_${turnNumber}_full_prompt.txt",
                content =
                    """
                    === SYSTEM PROMPT ===
                    $systemPrompt

                    === USER CONTEXT ===
                    $userContextText
                    """.trimIndent(),
                mimeType = "text/plain"
            )
        val inputItemsArtifact =
            storeRedactedText(
                kind = "llm_input_items",
                filenameHint = "turn_${turnNumber}_llm_input_items.json",
                content = encodeRedactedJson(LlmInputItemsTraceSerializer.toJson(inputItems)),
                mimeType = "application/json"
            )
        return listOfNotNull(
            historyArtifact,
            systemArtifact,
            contextArtifact,
            fullPromptArtifact,
            inputItemsArtifact
        ) + snapshotArtifacts(snapshot, postAction = false)
    }

    fun llmResponseArtifacts(turnNumber: Int, result: TurnResult): List<TraceArtifactRef> {
        val toolCallsJson =
            buildJsonArray {
                result.toolCalls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(call.id))
                            put("name", JsonPrimitive(call.name))
                            put("arguments_json", JsonPrimitive(call.arguments.toString()))
                        }
                    )
                }
            }
        val responseTextArtifact =
            result.content?.let {
                storeRedactedText(
                    kind = "llm_response_text",
                    filenameHint = "turn_${turnNumber}_assistant.txt",
                    content = it,
                    mimeType = "text/plain"
                )
            }
        val toolCallsArtifact =
            storeRedactedText(
                kind = "llm_tool_calls",
                filenameHint = "turn_${turnNumber}_tool_calls.json",
                content = encodeRedactedJson(toolCallsJson),
                mimeType = "application/json"
            )
        return listOfNotNull(responseTextArtifact, toolCallsArtifact)
    }

    fun toolResultArtifacts(
        turnNumber: Int,
        toolCall: ToolCallRequest,
        formattedResult: String,
        observation: ToolObservation,
        observedSnapshot: ScreenSnapshot?
    ): List<TraceArtifactRef> {
        val resultArtifact =
            storeRedactedText(
                kind = "tool_result",
                filenameHint = "turn_${turnNumber}_${toolCall.name}_${toolCall.id}_result.txt",
                content = formattedResult,
                mimeType = "text/plain"
            )
        val observationArtifact =
            when (observation) {
                is ToolObservation.ScreenState ->
                    storeRedactedText(
                        kind = "tool_observation_screen",
                        filenameHint = "turn_${turnNumber}_${toolCall.name}_${toolCall.id}_screen.json",
                        content = observation.accessibilityTree,
                        mimeType = "application/json"
                    )
                is ToolObservation.TextOutput ->
                    storeRedactedText(
                        kind = "tool_observation_text",
                        filenameHint = "turn_${turnNumber}_${toolCall.name}_${toolCall.id}_obs.txt",
                        content = observation.content,
                        mimeType = "text/plain"
                    )
            }
        val artifacts = mutableListOf<TraceArtifactRef>()
        listOfNotNull(resultArtifact, observationArtifact).forEach { artifacts.add(it) }
        observedSnapshot?.let { artifacts.addAll(snapshotArtifacts(it, postAction = true)) }
        return artifacts
    }

    fun snapshotArtifacts(snapshot: ScreenSnapshot, postAction: Boolean): List<TraceArtifactRef> {
        val descriptionPrefix = if (postAction) "Post-action " else null
        return buildList {
            snapshot.debug?.rawA11yTreePath?.let {
                add(
                    TraceArtifactRef(
                        kind = "raw_a11y_tree",
                        path = it,
                        mimeType = "application/json",
                        description = descriptionPrefix?.plus("raw tree")
                    )
                )
            }
            snapshot.debug?.sanitizedA11yTreePath?.let {
                add(
                    TraceArtifactRef(
                        kind = "sanitized_a11y_tree",
                        path = it,
                        mimeType = "application/json",
                        description = descriptionPrefix?.plus("sanitized tree")
                    )
                )
            }
            snapshot.debug?.screenshotPath?.let {
                add(
                    TraceArtifactRef(
                        kind = "screenshot",
                        path = it,
                        mimeType = "image/jpeg",
                        description = descriptionPrefix?.plus("screenshot")
                    )
                )
            }
        }
    }

    fun storeRedactedText(
        kind: String,
        filenameHint: String,
        content: String,
        mimeType: String
    ): TraceArtifactRef? {
        return trace.storeText(
            kind = kind,
            filenameHint = filenameHint,
            content = CognitionTraceRedactor.redactText(content),
            mimeType = mimeType
        )
    }

    fun encodeRedactedJson(element: JsonElement): String {
        return TraceJson.instance.encodeToString(CognitionTraceRedactor.redactJson(element))
    }
}
