package ai.closepaw.auth

import ai.closepaw.llm.LLMProvider
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.unmockkAll
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for AuthStore. Uses [FakeSharedPreferences] via the
 * [AuthStore.prefsProvider] hook, so no Android Keystore is needed.
 */
class AuthStoreTest {

    private lateinit var context: Context
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        fakePrefs = FakeSharedPreferences()
    }

    @After
    fun tearDown() = unmockkAll()

    private fun newStore(
        now: () -> Long = System::currentTimeMillis,
        refresher: suspend (String) -> AuthCredential.OAuth = { error("unused") },
    ) = AuthStore(context, refresher, now, prefsProvider = { fakePrefs })

    @Test
    fun `api key set get has clear`() = runBlocking {
        val store = newStore()
        val provider = LLMProvider.OPENAI_API
        assertThat(store.has(provider)).isFalse()
        assertThat(store.get(provider)).isNull()

        store.set(provider, AuthCredential.ApiKey("sk-abc"))
        assertThat(store.has(provider)).isTrue()
        assertThat(store.get(provider)).isEqualTo(AuthCredential.ApiKey("sk-abc"))
        assertThat(store.requireApiKey(provider)).isEqualTo("sk-abc")

        store.clear(provider)
        assertThat(store.has(provider)).isFalse()
        assertThat(store.get(provider)).isNull()
    }

    @Test
    fun `oauth set get has clear`() = runBlocking {
        val store = newStore()
        val provider = LLMProvider.OPENAI_API
        val oauth = AuthCredential.OAuth(
            accessToken = "at",
            refreshToken = "rt",
            expiresAt = System.currentTimeMillis() + 3600_000,
            email = "u@x.com",
            idToken = null,
        )
        store.set(provider, oauth)
        assertThat(store.has(provider)).isTrue()
        assertThat(store.get(provider)).isEqualTo(oauth)

        store.clear(provider)
        assertThat(store.get(provider)).isNull()
    }

    @Test
    fun `requireApiKey on oauth slot throws WrongCredentialType`() = runBlocking {
        val store = newStore()
        val provider = LLMProvider.OPENAI_API
        store.set(provider, AuthCredential.OAuth("a", "r", Long.MAX_VALUE, null, null))

        val err = assertThrows(WrongCredentialType::class.java) {
            store.requireApiKey(provider)
        }
        assertThat(err.provider).isEqualTo(provider)
        assertThat(err.expected).isEqualTo("ApiKey")
        assertThat(err.actual).isEqualTo("OAuth")
    }

    @Test
    fun `requireApiKey on empty slot throws MissingCredential`() {
        val store = newStore()
        val err = assertThrows(MissingCredential::class.java) {
            store.requireApiKey(LLMProvider.OPENAI_API)
        }
        assertThat(err.provider).isEqualTo(LLMProvider.OPENAI_API)
    }

    @Test
    fun `codexHeaders on empty slot throws MissingCredential`() {
        val store = newStore()
        assertThrows(MissingCredential::class.java) {
            runBlocking { store.codexHeaders(LLMProvider.OPENAI_API) }
        }
    }

    @Test
    fun `codexHeaders on api key slot throws WrongCredentialType`() {
        val store = newStore()
        val provider = LLMProvider.OPENAI_API
        runBlocking { store.set(provider, AuthCredential.ApiKey("k")) }
        val err = assertThrows(WrongCredentialType::class.java) {
            runBlocking { store.codexHeaders(provider) }
        }
        assertThat(err.expected).isEqualTo("OAuth")
        assertThat(err.actual).isEqualTo("ApiKey")
    }

    @Test
    fun `generation increments on set and clear, stable on read`() = runBlocking {
        val store = newStore()
        val provider = LLMProvider.OPENAI_API
        assertThat(store.generation(provider)).isEqualTo(0)

        store.set(provider, AuthCredential.ApiKey("a"))
        assertThat(store.generation(provider)).isEqualTo(1)

        // reads don't bump
        store.get(provider)
        store.has(provider)
        assertThat(store.generation(provider)).isEqualTo(1)

        store.set(provider, AuthCredential.ApiKey("b"))
        assertThat(store.generation(provider)).isEqualTo(2)

        store.clear(provider)
        assertThat(store.generation(provider)).isEqualTo(3)
    }

    @Test
    fun `codexHeaders returns cached when fresh (no refresh)`() = runBlocking {
        var refreshCalls = 0
        val now = 1_000_000L
        val store = newStore(
            now = { now },
            refresher = { refreshCalls++; error("should not be called") },
        )
        val provider = LLMProvider.OPENAI_API
        val oauth = AuthCredential.OAuth(
            accessToken = "at-fresh",
            refreshToken = "rt",
            expiresAt = now + 3600_000,
            email = "e@x.com",
            idToken = null,
        )
        store.set(provider, oauth)

        val headers = store.codexHeaders(provider)
        assertThat(headers.accessToken).isEqualTo("at-fresh")
        assertThat(headers.email).isEqualTo("e@x.com")
        assertThat(refreshCalls).isEqualTo(0)
    }

    @Test
    fun `codexHeaders refreshes when near expiry`() = runBlocking {
        val now = 1_000_000L
        val refreshCalls = AtomicInteger(0)
        val store = newStore(
            now = { now },
            refresher = {
                refreshCalls.incrementAndGet()
                AuthCredential.OAuth(
                    accessToken = "at-new",
                    refreshToken = "rt-new",
                    expiresAt = now + 3600_000,
                    email = "e@x.com",
                    idToken = null,
                )
            },
        )
        val provider = LLMProvider.OPENAI_API
        store.set(
            provider,
            AuthCredential.OAuth(
                accessToken = "at-old",
                refreshToken = "rt",
                expiresAt = now + 10_000, // within buffer
                email = "e@x.com",
                idToken = null,
            ),
        )

        val headers = store.codexHeaders(provider)
        assertThat(headers.accessToken).isEqualTo("at-new")
        assertThat(refreshCalls.get()).isEqualTo(1)
        assertThat((store.get(provider) as AuthCredential.OAuth).accessToken)
            .isEqualTo("at-new")
    }

    @Test
    fun `codexHeaders throws OAuthRefreshFailed when refresher throws`() {
        val now = 1_000_000L
        val store = newStore(
            now = { now },
            refresher = { throw IllegalStateException("boom") },
        )
        val provider = LLMProvider.OPENAI_API
        runBlocking {
            store.set(provider, AuthCredential.OAuth("old", "rt", now + 10_000, null, null))
        }
        val err = assertThrows(OAuthRefreshFailed::class.java) {
            runBlocking { store.codexHeaders(provider) }
        }
        assertThat(err.provider).isEqualTo(provider)
        assertThat(err.cause).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `concurrent codexHeaders callers serialize via mutex - one refresh`() = runTest {
        val now = 1_000_000L
        val refreshCalls = AtomicInteger(0)
        val store = newStore(
            now = { now },
            refresher = {
                refreshCalls.incrementAndGet()
                delay(50)
                AuthCredential.OAuth(
                    accessToken = "at-new",
                    refreshToken = "rt-new",
                    expiresAt = now + 3600_000,
                    email = null,
                    idToken = null,
                )
            },
        )
        val provider = LLMProvider.OPENAI_API
        store.set(
            provider,
            AuthCredential.OAuth("old", "rt", now + 10_000, null, null),
        )

        val results = (1..5).map { async { store.codexHeaders(provider) } }.awaitAll()
        assertThat(refreshCalls.get()).isEqualTo(1)
        assertThat(results.map { it.accessToken }.toSet()).containsExactly("at-new")
    }

    @Test
    fun `prefsProvider exception bubbles up`() {
        val failing = AuthStore(
            context,
            prefsProvider = { throw RuntimeException("Keystore unavailable") },
        )
        assertThrows(RuntimeException::class.java) {
            runBlocking { failing.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("k1")) }
        }
    }

    @Test
    fun `concurrent refresh and clear - credential stays cleared`() = runTest {
        val now = 1_000_000L
        val refreshGate = CompletableDeferred<Unit>()
        val store = newStore(
            now = { now },
            refresher = {
                refreshGate.await()
                AuthCredential.OAuth(
                    accessToken = "at-new",
                    refreshToken = "rt-new",
                    expiresAt = now + 3600_000,
                    email = null,
                    idToken = null,
                )
            },
        )
        val provider = LLMProvider.OPENAI_API
        store.set(provider, AuthCredential.OAuth("old", "rt", now + 10_000, null, null))

        val refresh = async {
            try {
                store.codexHeaders(provider)
            } catch (e: MissingCredential) {
                null
            }
        }
        // Let codexHeaders enter the mutex and block on refreshGate, then clear.
        delay(10)
        store.clear(provider)
        refreshGate.complete(Unit)
        val result = refresh.await()

        // Clear must win: refreshed credential was discarded.
        assertThat(store.has(provider)).isFalse()
        assertThat(result).isNull()
    }

    @Test
    fun `concurrent refresh and set - new credential wins, refreshed value discarded`() = runTest {
        val now = 1_000_000L
        val refreshGate = CompletableDeferred<Unit>()
        val store = newStore(
            now = { now },
            refresher = {
                refreshGate.await()
                AuthCredential.OAuth(
                    accessToken = "at-refreshed",
                    refreshToken = "rt-refreshed",
                    expiresAt = now + 3600_000,
                    email = "old@x",
                    idToken = null,
                )
            },
        )
        val provider = LLMProvider.OPENAI_API
        store.set(provider, AuthCredential.OAuth("old", "rt", now + 10_000, "old@x", null))

        val refresh = async {
            try {
                store.codexHeaders(provider)
            } catch (_: Throwable) {
                null
            }
        }
        delay(10)
        // User signs in with a new account while refresh is in flight.
        store.set(provider, AuthCredential.OAuth("at-new-account", "rt-new", now + 3600_000, "new@x", null))
        refreshGate.complete(Unit)
        refresh.await()

        // The new account's credential must remain - refresh result is discarded.
        val current = store.get(provider) as AuthCredential.OAuth
        assertThat(current.accessToken).isEqualTo("at-new-account")
        assertThat(current.email).isEqualTo("new@x")
    }

    @Test
    fun `concurrent set produces correct generation count`() = runTest {
        val store = newStore()
        val provider = LLMProvider.OPENAI_API
        val n = 50
        (1..n).map { i ->
            async { store.set(provider, AuthCredential.ApiKey("k$i")) }
        }.awaitAll()
        assertThat(store.generation(provider)).isEqualTo(n.toLong())
    }
}
