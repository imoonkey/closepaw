package ai.closepaw.ui.settings

import ai.closepaw.llm.ApiType
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelPickerTest {

    @Test
    fun `groups by id prefix - anthropic openai google pinned, others alphabetical`() {
        val entries = listOf(
            entry("a/qwen-1", "qwen/qwen-1"),
            entry("a/anthropic-x", "anthropic/x"),
            entry("a/zai-1", "zai/y"),
            entry("a/openai-1", "openai/a"),
            entry("a/google-1", "google/a"),
            entry("a/meta-1", "meta/a"),
        )
        val state = ModelPicker.buildState(entries, query = "", selectedName = null, expandedKeys = emptySet())
        val keys = state.groups.map { it.key }
        assertThat(keys).containsExactly("anthropic", "openai", "google", "meta", "qwen", "zai").inOrder()
    }

    @Test
    fun `OTHER entries without slash land in other group, which tails`() {
        val entries = listOf(
            entry("openrouter:openai/gpt-5", "openai/gpt-5"),
            entry("other:bare-id", "bare-id"),
            entry("other:vendor/x", "vendor/x"),
        )
        val state = ModelPicker.buildState(entries, query = "", selectedName = null, expandedKeys = emptySet())
        val keys = state.groups.map { it.key }
        assertThat(keys.last()).isEqualTo("(other)")
        assertThat(keys).contains("openai")
        assertThat(keys).contains("vendor")
    }

    @Test
    fun `within group, sort by created desc with displayName tiebreaker`() {
        val entries = listOf(
            entry("a/b", "anthropic/b", displayName = "Bee", created = 100L),
            entry("a/c", "anthropic/c", displayName = "Cee", created = 300L),
            entry("a/a", "anthropic/a", displayName = "Aye", created = 200L),
        )
        val state = ModelPicker.buildState(entries, query = "", selectedName = null, expandedKeys = emptySet())
        val anthropic = state.groups.single { it.key == "anthropic" }
        assertThat(anthropic.rows.map { it.displayName }).containsExactly("Cee", "Aye", "Bee").inOrder()
    }

    @Test
    fun `search filters case-insensitively across displayName and modelId, flattens groups`() {
        val entries = listOf(
            entry("a/claude-opus", "anthropic/claude-opus-4.7", displayName = "Claude Opus 4.7"),
            entry("a/gpt-5", "openai/gpt-5", displayName = "GPT-5"),
            entry("a/qwen", "qwen/qwen-1", displayName = "Qwen 1"),
        )
        val state = ModelPicker.buildState(entries, query = "OPUS", selectedName = null, expandedKeys = emptySet())
        assertThat(state.isSearching).isTrue()
        assertThat(state.groups).hasSize(1)
        assertThat(state.groups.single().key).isEqualTo("(search)")
        assertThat(state.groups.single().rows).hasSize(1)
        assertThat(state.groups.single().rows.single().modelId).isEqualTo("anthropic/claude-opus-4.7")

        val byId = ModelPicker.buildState(entries, query = "qwen", selectedName = null, expandedKeys = emptySet())
        assertThat(byId.groups.single().rows.single().modelId).isEqualTo("qwen/qwen-1")
    }

    @Test
    fun `search with no matches yields no groups, still flagged isSearching`() {
        val entries = listOf(entry("a/b", "anthropic/b"))
        val state = ModelPicker.buildState(entries, query = "zzz", selectedName = null, expandedKeys = emptySet())
        assertThat(state.isSearching).isTrue()
        assertThat(state.groups).isEmpty()
    }

    @Test
    fun `selected group auto-expands on open even if not in expandedKeys`() {
        val entries = listOf(
            entry("a/a", "anthropic/a"),
            entry("a/b", "openai/b"),
        )
        val state = ModelPicker.buildState(
            allEntries = entries,
            query = "",
            selectedName = "a/b",
            expandedKeys = emptySet(),
        )
        assertThat(state.expandedKeys).contains("openai")
    }

    @Test
    fun `selectedRowIndex points at the visible flat position of the selected row`() {
        val entries = listOf(
            entry("a/0", "anthropic/0"),
            entry("a/1", "anthropic/1"),
            entry("o/0", "openai/0"),
        )
        val state = ModelPicker.buildState(
            allEntries = entries,
            query = "",
            selectedName = "o/0",
            expandedKeys = setOf("anthropic"),
        )
        // anthropic header (0), row a/0 (1), row a/1 (2),
        // openai header (3) — auto-expanded because it contains the selection — row o/0 (4).
        assertThat(state.selectedRowIndex).isEqualTo(4)
    }

    @Test
    fun `selectedRowIndex is -1 when selection not in current visible result set`() {
        val entries = listOf(entry("a/0", "anthropic/0"))
        val state = ModelPicker.buildState(
            allEntries = entries,
            query = "zzz",
            selectedName = "a/0",
            expandedKeys = emptySet(),
        )
        assertThat(state.selectedRowIndex).isEqualTo(-1)
    }

    private fun entry(
        name: String,
        modelId: String,
        displayName: String = name,
        created: Long = 0L,
    ): ModelEntry = ModelEntry(
        name = name,
        displayName = displayName,
        provider = LLMProvider.OPENROUTER,
        api = ApiType.CHAT,
        modelId = modelId,
        contextWindow = 128_000,
        baseUrl = null,
        apiKeyEnv = null,
        supportsVision = false,
        created = created,
    )
}
