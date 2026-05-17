package ai.closepaw.llm

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import ai.closepaw.app.AppSettingsStore
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.After
import org.junit.Test

class ModelCatalogRepositoryTest {

    private val seedAssetFile = File("src/main/assets/llm_models.json")

    @After
    fun tearDown() {
        ModelCatalogRepositoryHolder.resetForTest()
    }

    @Test
    fun `initial seed load matches assets llm_models json`() {
        val seedBytes = seedAssetFile.readBytes()
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes),
            settingsStore = mockk(relaxed = true),
            discoveryCache = mockk(relaxed = true),
        )

        val catalog = repo.catalog.value
        val expectedKeys = ModelCatalog.fromJson(String(seedBytes)).names()
        assertThat(catalog.names()).isEqualTo(expectedKeys)
        assertThat(catalog.size).isEqualTo(expectedKeys.size)
    }

    @Test
    fun `invalidate emits a new catalog value`() {
        val first = """{"a":{"display_name":"A","provider":"OPENROUTER","api":"chat","model_id":"x/a"}}"""
        val second =
            """{"a":{"display_name":"A","provider":"OPENROUTER","api":"chat","model_id":"x/a"},""" +
                """"b":{"display_name":"B","provider":"OPENROUTER","api":"chat","model_id":"x/b"}}"""
        val context = contextReturningSequentialAssets(first.toByteArray(), second.toByteArray())
        val repo = ModelCatalogRepository(
            context = context,
            settingsStore = mockk(relaxed = true),
            discoveryCache = mockk(relaxed = true),
        )

        val before = repo.catalog.value
        assertThat(before.names()).containsExactly("a")

        repo.invalidate()

        val after = repo.catalog.value
        assertThat(after.names()).containsExactly("a", "b")
        assertThat(after).isNotSameInstanceAs(before)
    }

    @Test
    fun `resetForTest clears the singleton instance`() {
        // mockkConstructor so the holder's lazy build of AppSettingsStore in `get()` doesn't
        // touch real SharedPreferences (mock Context can't satisfy it otherwise).
        mockkConstructor(AppSettingsStore::class)
        try {
            val seedBytes = seedAssetFile.readBytes()
            val ctxA = contextReturningAsset(seedBytes)
            val ctxB = contextReturningAsset(seedBytes)

            val first = ModelCatalogRepositoryHolder.get(ctxA)
            val cached = ModelCatalogRepositoryHolder.get(ctxA)
            assertThat(cached).isSameInstanceAs(first)

            ModelCatalogRepositoryHolder.resetForTest()

            val second = ModelCatalogRepositoryHolder.get(ctxB)
            assertThat(second).isNotSameInstanceAs(first)
        } finally {
            unmockkConstructor(AppSettingsStore::class)
        }
    }

    // ── Fixtures ──

    private fun contextReturningAsset(bytes: ByteArray): Context {
        val assets = mockk<AssetManager>()
        every { assets.open("llm_models.json") } answers { ByteArrayInputStream(bytes) }
        return contextWith(assets)
    }

    private fun contextReturningSequentialAssets(vararg payloads: ByteArray): Context {
        val assets = mockk<AssetManager>()
        var idx = 0
        every { assets.open("llm_models.json") } answers {
            val payload = payloads[idx.coerceAtMost(payloads.lastIndex)]
            idx++
            ByteArrayInputStream(payload)
        }
        return contextWith(assets)
    }

    private fun contextWith(assets: AssetManager): Context {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.assets } returns assets
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }
}
