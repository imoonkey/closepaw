package ai.closepaw.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class ModelDiscoveryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Field-priority reader: three fixture types ───────────────────────

    @Test
    fun `OpenRouter fixture parses name + context_length + image modality + tool support`() {
        val body = """
            {"data":[
              {
                "id":"anthropic/claude-opus-4.7",
                "name":"Anthropic Claude Opus 4.7",
                "context_length":200000,
                "created":1700000000,
                "architecture":{"input_modalities":["text","image"],"modality":"text->text"},
                "supported_parameters":["temperature","tools","tool_choice"]
              }
            ]}
        """.trimIndent()

        val entries = ModelDiscovery.parse(LLMProvider.OPENROUTER, BASE, body)

        assertThat(entries).hasSize(1)
        val e = entries.single().entry
        assertThat(e.modelId).isEqualTo("anthropic/claude-opus-4.7")
        assertThat(e.displayName).isEqualTo("Anthropic Claude Opus 4.7")
        assertThat(e.contextWindow).isEqualTo(200000)
        assertThat(e.supportsVision).isTrue()
        assertThat(e.api).isEqualTo(ApiType.CHAT)
        assertThat(e.baseUrl).isEqualTo(BASE)
        assertThat(entries.single().created).isEqualTo(1700000000L)
    }

    @Test
    fun `Novita-style fixture parses display_name + context_size + endpoints`() {
        val body = """
            {"data":[
              {
                "id":"zai-org/autoglm-phone-9b-multilingual",
                "display_name":"AutoGLM Phone 9B",
                "context_size":131072,
                "model_type":"chat",
                "endpoints":["chat/completions"]
              }
            ]}
        """.trimIndent()

        val entries = ModelDiscovery.parse(LLMProvider.OTHER, BASE, body)
        assertThat(entries).hasSize(1)
        val e = entries.single().entry
        assertThat(e.displayName).isEqualTo("AutoGLM Phone 9B")
        assertThat(e.contextWindow).isEqualTo(131072)
        assertThat(e.supportsVision).isFalse()
        assertThat(e.modelId).isEqualTo("zai-org/autoglm-phone-9b-multilingual")
        assertThat(e.api).isEqualTo(ApiType.CHAT)
    }

    @Test
    fun `OpenAI-bare fixture falls back to id and defaults`() {
        val body = """
            {"data":[
              {"id":"gpt-4o-mini","object":"model","created":1721000000,"owned_by":"openai"}
            ]}
        """.trimIndent()

        val entries = ModelDiscovery.parse(LLMProvider.OPENAI_API, BASE, body)
        assertThat(entries).hasSize(1)
        val e = entries.single().entry
        assertThat(e.modelId).isEqualTo("gpt-4o-mini")
        assertThat(e.displayName).isEqualTo("gpt-4o-mini")
        assertThat(e.contextWindow).isEqualTo(128_000)
        assertThat(e.supportsVision).isFalse()
    }

    // ── Mandatory tool-calling filter ────────────────────────────────────

    @Test
    fun `entry without tools in supported_parameters is dropped`() {
        val body = """
            {"data":[
              {"id":"chat/no-tools","supported_parameters":["temperature"]},
              {"id":"chat/with-tools","supported_parameters":["temperature","tools"]}
            ]}
        """.trimIndent()

        val ids = ModelDiscovery.parse(LLMProvider.OPENROUTER, BASE, body)
            .map { it.entry.modelId }
        assertThat(ids).containsExactly("chat/with-tools")
    }

    @Test
    fun `entry without supported_parameters field is accepted (upstream lacks declaration)`() {
        val body = """{"data":[{"id":"chat/unknown"}]}"""
        val entries = ModelDiscovery.parse(LLMProvider.OPENAI_API, BASE, body)
        assertThat(entries.map { it.entry.modelId }).containsExactly("chat/unknown")
    }

    // ── Non-chat filter ──────────────────────────────────────────────────

    @Test
    fun `OpenRouter embedding model dropped by id substring`() {
        val body = """
            {"data":[
              {"id":"openai/text-embedding-3-small","supported_parameters":["tools"]},
              {"id":"openai/gpt-5","supported_parameters":["tools"]}
            ]}
        """.trimIndent()
        val ids = ModelDiscovery.parse(LLMProvider.OPENROUTER, BASE, body)
            .map { it.entry.modelId }
        assertThat(ids).containsExactly("openai/gpt-5")
    }

    @Test
    fun `Novita embedding model dropped by model_type`() {
        val body = """
            {"data":[
              {"id":"baai/bge-large","model_type":"embedding"},
              {"id":"qwen/chat-7b","model_type":"chat"}
            ]}
        """.trimIndent()
        val ids = ModelDiscovery.parse(LLMProvider.OTHER, BASE, body)
            .map { it.entry.modelId }
        assertThat(ids).containsExactly("qwen/chat-7b")
    }

    @Test
    fun `OpenAI-bare embedding model dropped by id substring fallback`() {
        val body = """
            {"data":[
              {"id":"text-embedding-3-small","object":"model"},
              {"id":"gpt-4o","object":"model"}
            ]}
        """.trimIndent()
        val ids = ModelDiscovery.parse(LLMProvider.OPENAI_API, BASE, body)
            .map { it.entry.modelId }
        assertThat(ids).containsExactly("gpt-4o")
    }

    // ── Namespacing ──────────────────────────────────────────────────────

    @Test
    fun `discovered name is provider colon modelId, lowercase enum`() {
        val body = """{"data":[{"id":"vendor/x"}]}"""
        val e = ModelDiscovery.parse(LLMProvider.OPENROUTER, BASE, body).single().entry
        assertThat(e.name).isEqualTo("openrouter:vendor/x")
    }

    @Test
    fun `discovered openrouter gpt-5 does not collide with seed gpt-5`() {
        val seedBody = """
            {"gpt-5":{"display_name":"Seed GPT-5","provider":"OPENAI_API","api":"chat","model_id":"gpt-5"}}
        """.trimIndent()
        val seed = ModelCatalog.fromJson(seedBody)

        val discoBody = """{"data":[{"id":"gpt-5"}]}"""
        val disco = ModelDiscovery.parse(LLMProvider.OPENROUTER, BASE, discoBody)
        val merged = seed.withExtraEntries(disco.map { it.entry })

        assertThat(merged.names()).containsExactly("gpt-5", "openrouter:gpt-5")
        assertThat(merged.resolveOrNull("gpt-5")!!.displayName).isEqualTo("Seed GPT-5")
        assertThat(merged.resolveOrNull("openrouter:gpt-5")!!.provider).isEqualTo(LLMProvider.OPENROUTER)
    }

    @Test
    fun `modelId rejected if it contains whitespace or starts with colon or slash`() {
        val body = """
            {"data":[
              {"id":"with space"},
              {"id":":colon-start"},
              {"id":"/slash-start"},
              {"id":"vendor/ok-model"}
            ]}
        """.trimIndent()
        val ids = ModelDiscovery.parse(LLMProvider.OPENROUTER, BASE, body)
            .map { it.entry.modelId }
        assertThat(ids).containsExactly("vendor/ok-model")
    }

    // ── displayName sanitization ─────────────────────────────────────────

    @Test
    fun `displayName strips control characters and caps at 80`() {
        val longName = "A".repeat(120)
        val body = """
            {"data":[
              {"id":"vendor/a","name":"HelloWorld"},
              {"id":"vendor/b","name":"$longName"}
            ]}
        """.trimIndent()
        val entries = ModelDiscovery.parse(LLMProvider.OPENROUTER, BASE, body)
            .associate { it.entry.modelId to it.entry.displayName }
        assertThat(entries["vendor/a"]).isEqualTo("HelloWorld")
        assertThat(entries["vendor/b"]?.length).isEqualTo(80)
    }

    // ── baseUrl always == sourceBaseUrl ───────────────────────────────────

    @Test
    fun `every discovered ModelEntry baseUrl equals sourceBaseUrl for OTHER`() {
        val body = """
            {"data":[
              {"id":"a","name":"A"},
              {"id":"b","name":"B"}
            ]}
        """.trimIndent()
        val entries = ModelDiscovery.parse(LLMProvider.OTHER, BASE, body)
        assertThat(entries).hasSize(2)
        entries.forEach { assertThat(it.entry.baseUrl).isEqualTo(BASE) }
    }

    // ── Vision default ───────────────────────────────────────────────────

    @Test
    fun `supportsVision defaults false, true only when modality declares image`() {
        val body = """
            {"data":[
              {"id":"a/plain"},
              {"id":"a/with-image","architecture":{"input_modalities":["text","image"]}}
            ]}
        """.trimIndent()
        val map = ModelDiscovery.parse(LLMProvider.OPENROUTER, BASE, body)
            .associate { it.entry.modelId to it.entry.supportsVision }
        assertThat(map["a/plain"]).isFalse()
        assertThat(map["a/with-image"]).isTrue()
    }

    // ── HTTP integration via MockWebServer ───────────────────────────────

    @Test
    fun `discover hits baseUrl slash models with bearer auth`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":[{"id":"vendor/x"}]}"""
            )
        )
        val base = server.url("/v1").toString().trimEnd('/')
        val out = ModelDiscovery.discover(LLMProvider.OTHER, base, "sk-test")
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/models")
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer sk-test")
        assertThat(out.single().entry.modelId).isEqualTo("vendor/x")
        assertThat(out.single().entry.baseUrl).isEqualTo(base)
    }

    companion object {
        private const val BASE = "https://api.example.com/v1"
    }
}
