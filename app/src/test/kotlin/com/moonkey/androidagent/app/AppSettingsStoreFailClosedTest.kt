package com.moonkey.androidagent.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.MasterKey
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Verifies fail-closed behavior: when EncryptedSharedPreferences is unavailable,
 * secrets are held in memory only — never written to plain SharedPreferences.
 */
class AppSettingsStoreFailClosedTest {

    private lateinit var context: Context
    private lateinit var plainPrefs: SharedPreferences
    private lateinit var plainEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        plainEditor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } returns this
            every { putInt(any(), any()) } returns this
            every { putBoolean(any(), any()) } returns this
            every { putLong(any(), any()) } returns this
            every { remove(any()) } returns this
        }
        plainPrefs = mockk<SharedPreferences>(relaxed = true) {
            every { edit() } returns plainEditor
        }
        context = mockk<Context>(relaxed = true) {
            every { getSharedPreferences(any(), any()) } returns plainPrefs
        }

        // Make MasterKey.Builder.build() throw to simulate encryption failure
        mockkConstructor(MasterKey.Builder::class)
        every {
            anyConstructed<MasterKey.Builder>().setKeyScheme(any())
        } returns mockk(relaxed = true) {
            every { build() } throws RuntimeException("Keystore unavailable")
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `encryption failure sets encryptionDegraded flag`() {
        val store = AppSettingsStore(context)
        store.saveApiKey("sk-test-key")

        assertThat(store.encryptionDegraded).isTrue()
    }

    @Test
    fun `encryption failure keeps api key in memory only`() {
        val store = AppSettingsStore(context)
        store.saveApiKey("sk-test-key")

        // Secret must NOT be written to plain SharedPreferences
        verify(exactly = 0) { plainEditor.putString("api_key", "sk-test-key") }
    }

    @Test
    fun `encryption failure keeps all secret keys in memory only`() {
        val store = AppSettingsStore(context)
        store.saveApiKey("sk-api")
        store.saveOpenRouterApiKey("or-key")
        store.saveNovitaApiKey("nv-key")
        store.saveOpenAiManualApiKey("sk-manual")

        verify(exactly = 0) { plainEditor.putString("api_key", any()) }
        verify(exactly = 0) { plainEditor.putString("openrouter_api_key", any()) }
        verify(exactly = 0) { plainEditor.putString("novita_api_key", any()) }
        verify(exactly = 0) { plainEditor.putString("openai_manual_api_key", any()) }
    }

    @Test
    fun `in-memory secrets survive read after write when degraded`() {
        val store = AppSettingsStore(context)
        store.saveApiKey("sk-test-key")
        store.saveOpenRouterApiKey("or-key")
        store.saveNovitaApiKey("nv-key")
        store.saveOpenAiManualApiKey("sk-manual")

        val settings = store.load()

        assertThat(settings.apiKey).isEqualTo("sk-test-key")
        assertThat(settings.openRouterApiKey).isEqualTo("or-key")
        assertThat(settings.novitaApiKey).isEqualTo("nv-key")
        assertThat(settings.openAiManualApiKey).isEqualTo("sk-manual")
    }

    @Test
    fun `migration skips gracefully when encryption unavailable`() {
        // Migration calls securePrefs() which returns null — should not throw
        val store = AppSettingsStore(context)
        val settings = store.load() // triggers migrateApiKeysIfNeeded

        assertThat(store.encryptionDegraded).isTrue()
        assertThat(settings.apiKey).isEmpty() // no keys migrated, file fallback gated
    }

    @Test
    fun `non-secret settings still use plain prefs when degraded`() {
        val store = AppSettingsStore(context)
        store.saveApiKey("sk-test") // triggers encryption failure

        // Non-secret writes should still go to plain prefs
        store.saveModel("gpt-4o")
        verify { plainEditor.putString("model", "gpt-4o") }

        store.saveMaxTurns(30)
        verify { plainEditor.putInt("max_turns", 30) }
    }
}
