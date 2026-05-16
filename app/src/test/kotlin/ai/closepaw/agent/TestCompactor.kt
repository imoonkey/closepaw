package ai.closepaw.agent

import ai.closepaw.history.Compactor
import ai.closepaw.llm.ApiType
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.ModelEntry
import ai.closepaw.llm.ResponsesResult
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Builds a no-op-in-practice [Compactor] for tests that aren't focused on
 * compaction. The model carries a generous `contextWindow`, so
 * [Compactor.maybeCompact] returns `Skipped` for any realistically-sized test
 * history and never invokes the LLM. Prompts are empty strings.
 */
internal fun noopCompactor(
    llmClient: LLMClient = SkippedLLMClient,
    contextWindow: Int = 128_000,
): Compactor = Compactor(
    llmClient = llmClient,
    model = ModelEntry(
        name = "test-model",
        displayName = "Test Model",
        provider = LLMProvider.OPENAI_API,
        api = ApiType.RESPONSE,
        modelId = "test-model",
        contextWindow = contextWindow,
    ),
    initialPrompt = "",
    updatePrompt = "",
)

internal object SkippedLLMClient : LLMClient() {
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String,
        maxOutputTokens: Long?,
    ): ResponsesResult = ResponsesResult(textContent = null, toolCalls = emptyList(), responseId = "noop")

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): Flow<LLMStreamEvent> = flow { emit(LLMStreamEvent.Completed) }
}
