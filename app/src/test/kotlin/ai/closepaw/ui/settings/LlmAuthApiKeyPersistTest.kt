package ai.closepaw.ui.settings

import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.LLMProvider
import android.content.Context
import androidx.security.crypto.MasterKey
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Three rapid persists must collapse to a single write whose payload is the
 * latest keystroke — proves debounce + FIFO mutex defeats the out-of-order
 * race called out in codex review (AuthStore has no write lock).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmAuthApiKeyPersistTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        // Force AuthStore into encryption-degraded (memory-only) mode for JVM.
        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } returns
            mockk(relaxed = true) {
                every { build() } throws RuntimeException("Keystore unavailable")
            }
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `last keystroke wins across three rapid debounced persists`() = runTest {
        val authStore = AuthStore(context)
        val mutex = Mutex()
        val pending = arrayOf<Job?>(null)
        val provider = LLMProvider.OPENAI_API
        val debounce = 300L

        launchDebouncedApiKeyPersist(
            scope = this, authStore = authStore, mutex = mutex, pending = pending,
            provider = provider, key = "sk-a", debounceMs = debounce,
            ioContext = EmptyCoroutineContext,
        )
        advanceTimeBy(50)
        launchDebouncedApiKeyPersist(
            scope = this, authStore = authStore, mutex = mutex, pending = pending,
            provider = provider, key = "sk-ab", debounceMs = debounce,
            ioContext = EmptyCoroutineContext,
        )
        advanceTimeBy(50)
        launchDebouncedApiKeyPersist(
            scope = this, authStore = authStore, mutex = mutex, pending = pending,
            provider = provider, key = "sk-abc", debounceMs = debounce,
            ioContext = EmptyCoroutineContext,
        )
        advanceUntilIdle()

        val stored = runBlocking { authStore.get(provider) }
        assertThat(stored).isEqualTo(AuthCredential.ApiKey("sk-abc"))
    }

    @Test
    fun `mutex preserves launch order when writes overlap past debounce`() = runTest {
        val authStore = AuthStore(context)
        val mutex = Mutex()
        val pending = arrayOf<Job?>(null)
        val provider = LLMProvider.OPENAI_API

        // debounceMs = 0 → every launch races straight into the mutex; FIFO
        // ordering must yield "sk-final" as the last write.
        launchDebouncedApiKeyPersist(
            scope = this, authStore = authStore, mutex = mutex, pending = pending,
            provider = provider, key = "sk-1", debounceMs = 0L,
            ioContext = EmptyCoroutineContext,
        )
        launchDebouncedApiKeyPersist(
            scope = this, authStore = authStore, mutex = mutex, pending = pending,
            provider = provider, key = "sk-2", debounceMs = 0L,
            ioContext = EmptyCoroutineContext,
        )
        launchDebouncedApiKeyPersist(
            scope = this, authStore = authStore, mutex = mutex, pending = pending,
            provider = provider, key = "sk-final", debounceMs = 0L,
            ioContext = EmptyCoroutineContext,
        )
        advanceUntilIdle()

        val stored = runBlocking { authStore.get(provider) }
        assertThat(stored).isEqualTo(AuthCredential.ApiKey("sk-final"))
    }
}
