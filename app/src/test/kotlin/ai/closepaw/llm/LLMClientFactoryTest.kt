package ai.closepaw.llm

import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.CodexHeaders
import ai.closepaw.auth.MissingCredential
import android.content.Context
import androidx.security.crypto.MasterKey
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LLMClientFactoryTest {

    private val catalogJson = """
        {
          "gpt-5.2": {
            "display_name": "GPT-5.2",
            "provider":"OPENAI_API",
            "api": "response",
            "model_id": "gpt-5.2"
          },
          "gpt-5.2-chat": {
            "display_name": "GPT-5.2 (Chat)",
            "provider":"OPENAI_API",
            "api": "chat",
            "model_id": "gpt-5.2"
          },
          "gpt-5.2-codex": {
            "display_name": "GPT-5.2 (ChatGPT sign-in)",
            "provider": "OPENAI_CODEX",
            "api": "response",
            "model_id": "gpt-5.2"
          },
          "glm-4.7": {
            "display_name": "GLM-4.7",
            "provider": "OPENROUTER",
            "api": "chat",
            "model_id": "zhipu-ai/glm-4.7"
          }
        }
    """.trimIndent()

    private val catalog = ModelCatalog.fromJson(catalogJson)
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        // Force AuthStore into encryption-degraded (memory-only) mode.
        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } returns
                mockk(relaxed = true) {
                    every { build() } throws RuntimeException("Keystore unavailable")
                }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun realStore(): AuthStore = AuthStore(context)

    @Test
    fun `create returns OpenAIResponseClient for response api`() = runBlocking {
        val store = realStore()
        store.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-test"))
        val factory = LLMClientFactory(catalog, store)

        val client = factory.create("gpt-5.2")
        assertTrue(client is OpenAIResponseClient)
    }

    @Test
    fun `create returns ChatCompletionClient for chat api`() = runBlocking {
        val store = realStore()
        store.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-test"))
        val factory = LLMClientFactory(catalog, store)

        val client = factory.create("gpt-5.2-chat")
        assertTrue(client is ChatCompletionClient)
    }

    @Test
    fun `create caches clients per model name`() = runBlocking {
        val store = realStore()
        store.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-test"))
        val factory = LLMClientFactory(catalog, store)

        val c1 = factory.create("gpt-5.2")
        val c2 = factory.create("gpt-5.2")
        assertSame(c1, c2)
    }

    @Test
    fun `different providers get different clients`() = runBlocking {
        val store = realStore()
        store.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-test"))
        store.set(LLMProvider.OPENROUTER, AuthCredential.ApiKey("sk-or-test"))
        val factory = LLMClientFactory(catalog, store)

        val openai = factory.create("gpt-5.2-chat")
        val openrouter = factory.create("glm-4.7")
        assertNotSame(openai, openrouter)
    }

    @Test
    fun `OPENAI_CODEX routes to CodexResponseClient with header supplier`() = runBlocking {
        val store = mockk<AuthStore>(relaxed = true)
        every { store.generation(any()) } returns 0L
        val captured = CodexHeaders(accessToken = "acc-123", chatgptAccountId = "acct", email = "x@y")
        coEvery { store.codexHeaders(LLMProvider.OPENAI_CODEX) } returns captured

        val factory = LLMClientFactory(catalog, store)
        val client = factory.create("gpt-5.2-codex")

        assertTrue(client is CodexResponseClient)

        // Supplier should pull from AuthStore per invocation.
        val supplier = extractHeaderSupplier(client as CodexResponseClient)
        val headers = runBlocking { supplier.invoke() }
        assertEquals(captured, headers)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create throws for unknown model`() {
        val factory = LLMClientFactory(catalog, realStore())
        factory.create("nonexistent-model")
    }

    @Test
    fun `create throws MissingCredential when store has nothing`() {
        val factory = LLMClientFactory(catalog, realStore())
        assertThrows(MissingCredential::class.java) { factory.create("gpt-5.2") }
    }

    @Test
    fun `generation bump invalidates cached client`() = runBlocking {
        val store = realStore()
        store.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-one"))
        val factory = LLMClientFactory(catalog, store)

        val c1 = factory.create("gpt-5.2")
        // Rotate the key — bumps generation — factory must rebuild.
        store.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-two"))
        val c2 = factory.create("gpt-5.2")

        assertNotSame(c1, c2)
    }

    @Test
    fun `concurrent create across set bump never returns stale client`() = runBlocking {
        val store = realStore()
        store.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-0"))
        val factory = LLMClientFactory(catalog, store)
        val c0 = factory.create("gpt-5.2")

        // Two reader threads torture-test against a writer. After every bump, any
        // create() that observes the post-bump store must never return c0.
        val iterations = 200
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val startGate = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(3)

        val writer = Runnable {
            try {
                startGate.await()
                repeat(iterations) { i ->
                    runBlocking {
                        store.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-${i + 1}"))
                    }
                }
            } catch (t: Throwable) { errors += t } finally { done.countDown() }
        }
        val reader = Runnable {
            try {
                startGate.await()
                // After a few initial iterations, any client must be != c0 because the
                // writer has already bumped generation at least once.
                var seenNonStale = false
                repeat(iterations * 2) {
                    val c = factory.create("gpt-5.2")
                    if (c !== c0) seenNonStale = true
                    // Strict invariant: once we've seen a post-bump client, we must
                    // never see c0 again (no regression to stale).
                    if (seenNonStale && c === c0) {
                        error("returned stale c0 after observing a post-bump client")
                    }
                }
            } catch (t: Throwable) { errors += t } finally { done.countDown() }
        }

        pool.submit(writer)
        pool.submit(reader)
        pool.submit(reader)
        startGate.countDown()
        done.await()
        pool.shutdown()

        if (errors.isNotEmpty()) throw errors.first()

        // Final state: generation matches latest writer bump → client must be fresh.
        val finalClient = factory.create("gpt-5.2")
        assertNotSame(c0, finalClient)
    }

    @Test
    fun `catalog resolution returns correct model entry`() {
        val entry = catalog.resolve("glm-4.7")
        assertEquals(LLMProvider.OPENROUTER, entry.provider)
        assertEquals(ApiType.CHAT, entry.api)
        assertEquals("zhipu-ai/glm-4.7", entry.modelId)
        assertEquals("https://openrouter.ai/api/v1", entry.effectiveBaseUrl)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractHeaderSupplier(client: CodexResponseClient): suspend () -> CodexHeaders {
        val f = CodexResponseClient::class.java.getDeclaredField("headerSupplier")
        f.isAccessible = true
        return f.get(client) as suspend () -> CodexHeaders
    }
}
