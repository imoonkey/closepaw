package com.moonkey.androidagent.tool

import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenImageSource
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.AppTier
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Security regression: AppClassifier load failure must throw, preventing session start.
 * Also covers core classification and masking logic.
 */
class AppClassifierSecurityTest {

    // ── Load failure → IllegalStateException (prevents session start) ──

    @Test
    fun `fromAssets throws when asset file is missing`() {
        val assets = mockk<AssetManager>()
        every { assets.open("security/app_tiers.json") } throws IOException("not found")

        val ex = assertThrows(IllegalStateException::class.java) {
            AppClassifier.fromAssets(assets)
        }
        assertThat(ex.message).contains("app safety tiers unavailable")
    }

    @Test
    fun `fromAssets throws when JSON is corrupt`() {
        val assets = mockk<AssetManager>()
        every { assets.open("security/app_tiers.json") } returns
            ByteArrayInputStream("not valid json".toByteArray())

        val ex = assertThrows(IllegalStateException::class.java) {
            AppClassifier.fromAssets(assets)
        }
        assertThat(ex.message).contains("app safety tiers unavailable")
    }

    @Test
    fun `fromAssets succeeds with valid JSON`() {
        val json = """{"apps":{"com.example.banking":"BLOCKED","com.example.normal":"NORMAL"}}"""
        val assets = mockk<AssetManager>()
        every { assets.open("security/app_tiers.json") } returns
            ByteArrayInputStream(json.toByteArray())

        val classifier = AppClassifier.fromAssets(assets)

        assertThat(classifier.classify("com.example.banking")).isEqualTo(AppTier.BLOCKED)
        assertThat(classifier.classify("com.example.normal")).isEqualTo(AppTier.NORMAL)
    }

    // ── Unknown packages default to CAUTIOUS ───────────────────────

    @Test
    fun `unknown package is CAUTIOUS`() {
        val classifier = AppClassifier(emptyMap())
        assertThat(classifier.classify("com.unknown.app")).isEqualTo(AppTier.CAUTIOUS)
    }

    @Test
    fun `null package is CAUTIOUS`() {
        val classifier = AppClassifier(emptyMap())
        assertThat(classifier.classify(null)).isEqualTo(AppTier.CAUTIOUS)
    }

    // ── maskIfBlocked strips data for BLOCKED apps ─────────────────

    @Test
    fun `maskIfBlocked strips elements and image for BLOCKED app`() {
        val classifier = AppClassifier(mapOf("com.bank" to AppTier.BLOCKED))
        val snapshot = ScreenSnapshot(
            timestamp = 1L,
            elements = listOf(mockk<PerceptionElement>()),
            image = ScreenImage(
                width = 100, height = 200,
                mimeType = "image/png",
                bytes = ByteArray(100),
                source = ScreenImageSource.ACCESSIBILITY_SCREENSHOT
            )
        )

        val masked = classifier.maskIfBlocked(snapshot, "com.bank")

        assertThat(masked.elements).isEmpty()
        assertThat(masked.image).isNull()
    }

    @Test
    fun `maskIfBlocked preserves data for NORMAL app`() {
        val classifier = AppClassifier(mapOf("com.normal" to AppTier.NORMAL))
        val snapshot = ScreenSnapshot(
            timestamp = 1L,
            elements = listOf(mockk<PerceptionElement>()),
            image = ScreenImage(
                width = 100, height = 200,
                mimeType = "image/png",
                bytes = ByteArray(100),
                source = ScreenImageSource.ACCESSIBILITY_SCREENSHOT
            )
        )

        val result = classifier.maskIfBlocked(snapshot, "com.normal")

        assertThat(result.elements).hasSize(1)
        assertThat(result.image).isNotNull()
    }
}
