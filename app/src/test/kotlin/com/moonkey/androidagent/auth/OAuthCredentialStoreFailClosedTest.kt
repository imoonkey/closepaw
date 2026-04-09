package com.moonkey.androidagent.auth

import android.content.Context
import androidx.security.crypto.MasterKey
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Verifies OAuthCredentialStore fail-closed behavior: when encryption is unavailable,
 * OAuth tokens are held in memory only for the current session.
 */
class OAuthCredentialStoreFailClosedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)

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
        val store = OAuthCredentialStore(context)
        store.save(testTokens())

        assertThat(store.encryptionDegraded).isTrue()
    }

    @Test
    fun `encryption failure keeps tokens in memory for current session`() {
        val store = OAuthCredentialStore(context)
        val tokens = testTokens()
        store.save(tokens)

        val loaded = store.load()

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.accessToken).isEqualTo("access-token-123")
        assertThat(loaded.refreshToken).isEqualTo("refresh-token-456")
        assertThat(loaded.email).isEqualTo("user@example.com")
    }

    @Test
    fun `clear removes in-memory tokens when degraded`() {
        val store = OAuthCredentialStore(context)
        store.save(testTokens())

        assertThat(store.load()).isNotNull()

        store.clear()

        assertThat(store.load()).isNull()
    }

    @Test
    fun `load returns null when no tokens saved and degraded`() {
        val store = OAuthCredentialStore(context)

        // Trigger encryption failure
        store.save(testTokens())
        store.clear()

        assertThat(store.load()).isNull()
    }

    private fun testTokens() = OAuthTokens(
        accessToken = "access-token-123",
        refreshToken = "refresh-token-456",
        expiresAt = System.currentTimeMillis() + 3600_000,
        email = "user@example.com",
        idToken = "id-token-789"
    )
}
