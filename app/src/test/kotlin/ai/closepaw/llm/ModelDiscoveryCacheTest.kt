package ai.closepaw.llm

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelDiscoveryCacheTest {

    private lateinit var filesDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("disco-cache").toFile()
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `cache key composes provider name and base url`() {
        assertThat(
            ModelDiscoveryCache.cacheKey(LLMProvider.OPENROUTER, "https://openrouter.ai/api/v1")
        ).isEqualTo("OPENROUTER:https://openrouter.ai/api/v1")
    }

    @Test
    fun `write then readAll round-trips entries with created timestamp`() {
        val cache = ModelDiscoveryCache(context)
        val key = ModelDiscoveryCache.cacheKey(LLMProvider.OPENROUTER, "https://x.test/v1")
        val discovered = listOf(
            DiscoveredModel(
                entry = ModelEntry(
                    name = "openrouter:vendor/x",
                    displayName = "Vendor X",
                    provider = LLMProvider.OPENROUTER,
                    api = ApiType.CHAT,
                    modelId = "vendor/x",
                    contextWindow = 100_000,
                    baseUrl = "https://x.test/v1",
                    supportsVision = false,
                ),
                created = 1_700_000_000L,
            )
        )
        cache.write(key, fetchedAt = 12345L, discovered = discovered)

        val all = cache.readAll()
        assertThat(all).containsKey(key)
        val bucket = all.getValue(key)
        assertThat(bucket.fetchedAt).isEqualTo(12345L)
        assertThat(bucket.entries).hasSize(1)
        val restored = bucket.toDiscovered(LLMProvider.OPENROUTER).single()
        assertThat(restored.entry.name).isEqualTo("openrouter:vendor/x")
        assertThat(restored.entry.modelId).isEqualTo("vendor/x")
        assertThat(restored.entry.baseUrl).isEqualTo("https://x.test/v1")
        assertThat(restored.created).isEqualTo(1_700_000_000L)
    }

    @Test
    fun `cache file written to filesDir slash model_discovery_cache json`() {
        val cache = ModelDiscoveryCache(context)
        cache.write(
            key = ModelDiscoveryCache.cacheKey(LLMProvider.OPENROUTER, "https://x.test/v1"),
            fetchedAt = 1L,
            discovered = emptyList(),
        )
        val expected = File(filesDir, ModelDiscoveryCache.FILE_NAME)
        assertThat(expected.exists()).isTrue()
    }

    @Test
    fun `cache restored on new instance simulating process restart`() {
        val first = ModelDiscoveryCache(context)
        val key = ModelDiscoveryCache.cacheKey(LLMProvider.OTHER, "https://api.example.com/v1")
        first.write(key, 1L, listOf(sampleDiscovered("vendor/a", "https://api.example.com/v1")))

        val second = ModelDiscoveryCache(context)
        val restored = second.read(key)
        assertThat(restored).isNotNull()
        assertThat(restored!!.entries.single().modelId).isEqualTo("vendor/a")
    }

    @Test
    fun `cache slim - 263 OpenRouter-style entries compress to under 50KB on disk`() {
        // Synthesize a "raw" OpenRouter response that would be ~440KB if persisted
        // verbatim (pricing, description, modality, etc.). Cache must drop those.
        val cache = ModelDiscoveryCache(context)
        val discovered = (1..263).map { i ->
            DiscoveredModel(
                entry = ModelEntry(
                    name = "openrouter:vendor-$i/model-$i",
                    displayName = "Vendor $i Model $i",
                    provider = LLMProvider.OPENROUTER,
                    api = ApiType.CHAT,
                    modelId = "vendor-$i/model-$i",
                    contextWindow = 128_000,
                    baseUrl = "https://openrouter.ai/api/v1",
                    supportsVision = false,
                ),
                created = 1_700_000_000L + i,
            )
        }
        cache.write(
            key = ModelDiscoveryCache.cacheKey(LLMProvider.OPENROUTER, "https://openrouter.ai/api/v1"),
            fetchedAt = 1L,
            discovered = discovered,
        )
        val size = File(filesDir, ModelDiscoveryCache.FILE_NAME).length()
        assertThat(size).isLessThan(50_000L)
    }

    @Test
    fun `corrupt cache file is treated as empty`() {
        val file = File(filesDir, ModelDiscoveryCache.FILE_NAME)
        file.writeText("not json {{")
        val cache = ModelDiscoveryCache(context)
        assertThat(cache.readAll()).isEmpty()
    }

    private fun sampleDiscovered(modelId: String, baseUrl: String): DiscoveredModel =
        DiscoveredModel(
            entry = ModelEntry(
                name = "${LLMProvider.OTHER.name.lowercase()}:$modelId",
                displayName = modelId,
                provider = LLMProvider.OTHER,
                api = ApiType.CHAT,
                modelId = modelId,
                contextWindow = 128_000,
                baseUrl = baseUrl,
                supportsVision = false,
            ),
            created = 0L,
        )
}
