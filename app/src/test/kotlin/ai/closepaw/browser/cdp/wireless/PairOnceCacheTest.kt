package ai.closepaw.browser.cdp.wireless

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Coverage for [PairOnceCache] (round-trip through SharedPreferences) plus its
 * [WirelessAdbSelfPairTransport.ensurePaired] integration. The transport tests reuse the same
 * fake [SharedPreferences] backing as the cache tests so the integration runs against a real
 * [PairOnceCache] — no subclass overrides, no parallel "in-memory cache" stand-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairOnceCacheTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── PairOnceCache itself (SharedPreferences-backed) ──

    @Test
    fun `getCachedFingerprint returns null on cold cache`() = runTest {
        val cache = PairOnceCache(fakeContext(mutableMapOf()), testDispatcher)
        assertThat(cache.getCachedFingerprint()).isNull()
    }

    @Test
    fun `recordSuccessfulPair persists fingerprint and timestamp`() = runTest {
        val backing = mutableMapOf<String, Any?>()
        val cache = PairOnceCache(fakeContext(backing), testDispatcher)

        val fp = PairOnceCache.fingerprintOf(SAMPLE_PUBKEY)
        cache.recordSuccessfulPair(fp)

        assertThat(backing["pubkey_fingerprint_sha256_b64"]).isEqualTo(fp)
        assertThat(backing["last_paired_at_ms"] as Long).isGreaterThan(0L)
    }

    @Test
    fun `recordSuccessfulPair then getCachedFingerprint round-trips`() = runTest {
        val cache = PairOnceCache(fakeContext(mutableMapOf()), testDispatcher)

        val fp = PairOnceCache.fingerprintOf(SAMPLE_PUBKEY)
        cache.recordSuccessfulPair(fp)

        assertThat(cache.getCachedFingerprint()).isEqualTo(fp)
    }

    @Test
    fun `invalidate clears persisted fingerprint`() = runTest {
        val backing = mutableMapOf<String, Any?>()
        val cache = PairOnceCache(fakeContext(backing), testDispatcher)
        cache.recordSuccessfulPair(PairOnceCache.fingerprintOf(SAMPLE_PUBKEY))

        cache.invalidate()

        assertThat(cache.getCachedFingerprint()).isNull()
        assertThat(backing).doesNotContainKey("pubkey_fingerprint_sha256_b64")
        assertThat(backing).doesNotContainKey("last_paired_at_ms")
    }

    @Test
    fun `recordSuccessfulPair rejects empty fingerprint`() = runTest {
        val cache = PairOnceCache(fakeContext(mutableMapOf()), testDispatcher)
        try {
            cache.recordSuccessfulPair("")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("fingerprintBase64")
        }
    }

    @Test
    fun `fingerprintOf is deterministic and matches manual SHA-256 base64`() {
        val fp1 = PairOnceCache.fingerprintOf(SAMPLE_PUBKEY)
        val fp2 = PairOnceCache.fingerprintOf(SAMPLE_PUBKEY)
        assertThat(fp1).isEqualTo(fp2)

        val expected = Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(SAMPLE_PUBKEY.toByteArray(Charsets.US_ASCII))
        )
        assertThat(fp1).isEqualTo(expected)
    }

    @Test
    fun `fingerprintOf differs across distinct pubkeys`() {
        val fpA = PairOnceCache.fingerprintOf(SAMPLE_PUBKEY)
        val fpB = PairOnceCache.fingerprintOf(SAMPLE_PUBKEY + "_rotated")
        assertThat(fpA).isNotEqualTo(fpB)
    }

    @Test
    fun `fingerprintOf is invariant to surrounding whitespace`() {
        // Defensive: a pubkey with a trailing newline (common when read from a file written by
        // another tool) must produce the same fingerprint as the bare token, otherwise the
        // cache forks per-writer.
        val bare = PairOnceCache.fingerprintOf(SAMPLE_PUBKEY)
        val padded = PairOnceCache.fingerprintOf("  ${SAMPLE_PUBKEY}\n")
        assertThat(padded).isEqualTo(bare)
    }

    @Test
    fun `fingerprintOf rejects empty pubkey`() {
        try {
            PairOnceCache.fingerprintOf("")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("pubkeyBase64")
        }
    }

    // ── WirelessAdbSelfPairTransport ↔ PairOnceCache integration ──

    @Test
    fun `transport pairs and records fingerprint on cold cache when adb_keys is unreadable`() = runTest {
        // Cold device: pubkey persisted (carry-over from a previous install) but adb_keys is
        // unreadable (locked OEM) and the cache is empty. Must fall through to a real pair.
        val ctx = TransportCtx()
        ctx.stubPair()
        coEvery { ctx.wireless.isPubkeyAuthorized(any()) } returns false
        coEvery { ctx.wireless.pubkeyAuthorizationStatus(any()) } returns
            AdbWirelessManager.AuthorizationStatus.UNREADABLE

        ctx.runFirstExchange(returns = byteArrayOf(0x42))

        coVerify(exactly = 1) { ctx.pairing.pair(any(), FAKE_PAIR_PORT, any()) }
        assertThat(ctx.cache.getCachedFingerprint())
            .isEqualTo(PairOnceCache.fingerprintOf(SAMPLE_PUBKEY))
    }

    @Test
    fun `transport short-circuits pair on cache hit when adb_keys is unreadable`() = runTest {
        val ctx = TransportCtx()
        ctx.cache.recordSuccessfulPair(PairOnceCache.fingerprintOf(SAMPLE_PUBKEY))
        coEvery { ctx.wireless.isPubkeyAuthorized(any()) } returns false
        coEvery { ctx.wireless.pubkeyAuthorizationStatus(any()) } returns
            AdbWirelessManager.AuthorizationStatus.UNREADABLE

        ctx.runFirstExchange(returns = byteArrayOf(0x42))

        // Cache hit + UNREADABLE → no pair, no openPairPort call.
        coVerify(exactly = 0) { ctx.wireless.openPairPort(any(), any()) }
        coVerify(exactly = 0) { ctx.pairing.pair(any(), any(), any()) }
    }

    @Test
    fun `transport re-pairs when fingerprint mismatches even with adb_keys unreadable`() = runTest {
        val ctx = TransportCtx()
        // Stale fingerprint from a previous keypair — local key has rotated since.
        ctx.cache.recordSuccessfulPair(
            PairOnceCache.fingerprintOf("stale_pubkey_from_previous_install"),
        )
        ctx.stubPair()
        coEvery { ctx.wireless.isPubkeyAuthorized(any()) } returns false
        coEvery { ctx.wireless.pubkeyAuthorizationStatus(any()) } returns
            AdbWirelessManager.AuthorizationStatus.UNREADABLE

        ctx.runFirstExchange(returns = byteArrayOf(0x42))

        coVerify(exactly = 1) { ctx.pairing.pair(any(), FAKE_PAIR_PORT, any()) }
        // Cache overwritten with the current key's fingerprint.
        assertThat(ctx.cache.getCachedFingerprint())
            .isEqualTo(PairOnceCache.fingerprintOf(SAMPLE_PUBKEY))
    }

    @Test
    fun `transport ignores cache when adb_keys is readable and key is absent`() = runTest {
        // The cache short-circuit must NOT fire when the file is genuinely readable: if our key
        // isn't there, adbd will reject us and we have to pair regardless of any prior witness.
        val ctx = TransportCtx()
        ctx.cache.recordSuccessfulPair(PairOnceCache.fingerprintOf(SAMPLE_PUBKEY))
        ctx.stubPair()
        coEvery { ctx.wireless.isPubkeyAuthorized(any()) } returns false
        coEvery { ctx.wireless.pubkeyAuthorizationStatus(any()) } returns
            AdbWirelessManager.AuthorizationStatus.NOT_AUTHORIZED

        ctx.runFirstExchange(returns = byteArrayOf(0x42))

        coVerify(exactly = 1) { ctx.pairing.pair(any(), FAKE_PAIR_PORT, any()) }
    }

    @Test
    fun `transport records fingerprint on the authorized happy path`() = runTest {
        // Even when adb_keys is readable and contains our key, populate the cache so a future
        // cold session that loses readability can reuse the witness.
        val ctx = TransportCtx()
        coEvery { ctx.wireless.isPubkeyAuthorized(any()) } returns true

        ctx.runFirstExchange(returns = byteArrayOf(0x42))

        coVerify(exactly = 0) { ctx.wireless.openPairPort(any(), any()) }
        assertThat(ctx.cache.getCachedFingerprint())
            .isEqualTo(PairOnceCache.fingerprintOf(SAMPLE_PUBKEY))
    }

    @Test
    fun `transport invalidates cache and re-pairs after exchange failure following short-circuit`() = runTest {
        // Cache hit + UNREADABLE → first ensureBootstrapped short-circuits. wireClient.exchange
        // then throws (adbd actually doesn't trust us → mTLS rejected). The catch path must
        // invalidate the cache so the second ensureBootstrapped runs a real pair.
        val ctx = TransportCtx()
        ctx.cache.recordSuccessfulPair(PairOnceCache.fingerprintOf(SAMPLE_PUBKEY))
        ctx.stubPair()
        coEvery { ctx.wireless.isPubkeyAuthorized(any()) } returns false
        coEvery { ctx.wireless.pubkeyAuthorizationStatus(any()) } returns
            AdbWirelessManager.AuthorizationStatus.UNREADABLE

        // First exchange call fails (TLS rejected); second succeeds after we re-pair.
        val responses = ArrayDeque<Result<ByteArray>>().apply {
            add(Result.failure(IOException("simulated mTLS handshake failure")))
            add(Result.success(byteArrayOf(0x42)))
        }
        coEvery {
            ctx.wire.exchange(any(), any(), any(), any(), any())
        } answers { responses.removeFirst().getOrThrow() }

        val result = ctx.transport.exchange(byteArrayOf(0x01), timeoutMs = 1_000)

        assertThat(result).isEqualTo(byteArrayOf(0x42))
        // After failure: cache was wiped, then the retry's successful pair re-populated it
        // (commit happens only after the retry exchange succeeds).
        assertThat(ctx.cache.getCachedFingerprint())
            .isEqualTo(PairOnceCache.fingerprintOf(SAMPLE_PUBKEY))
        coVerify(exactly = 1) { ctx.pairing.pair(any(), FAKE_PAIR_PORT, any()) }
    }

    @Test
    fun `transport does NOT cache fingerprint when fresh pair never confirms via mTLS`() = runTest {
        // Critical safety property: a SPAKE2 pair handshake completes locally even if adbd's
        // adb_keys reload is racing or our key gets dropped before the mTLS handshake. The cache
        // MUST only commit after the next [wireClient.exchange] succeeds — otherwise an
        // unconfirmed pair could lock us into a short-circuit loop on cold sessions where
        // adb_keys is unreadable. Both the first and the retry exchange fail here; the cache
        // must remain empty.
        val ctx = TransportCtx()
        ctx.stubPair()
        coEvery { ctx.wireless.isPubkeyAuthorized(any()) } returns false
        coEvery { ctx.wireless.pubkeyAuthorizationStatus(any()) } returns
            AdbWirelessManager.AuthorizationStatus.UNREADABLE
        coEvery { ctx.wire.exchange(any(), any(), any(), any(), any()) } throws
            IOException("simulated mTLS handshake failure")

        try {
            ctx.transport.exchange(byteArrayOf(0x01), timeoutMs = 1_000)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertThat(e.message).contains("simulated mTLS")
        }

        // Pair ran (twice — once per bootstrap attempt) but the cache was never written: the
        // mTLS handshake is the authoritative test of trust and it never succeeded.
        assertThat(ctx.cache.getCachedFingerprint()).isNull()
        coVerify(atLeast = 1) { ctx.pairing.pair(any(), FAKE_PAIR_PORT, any()) }
    }

    // ── AdbWirelessManager EACCES-vs-missing distinction (drives the cache gate) ──

    @Test
    fun `pubkeyAuthorizationStatus returns UNREADABLE only on EACCES`() = runTest {
        val binder = mockk<ai.closepaw.browser.cdp.shizuku.IChromeDevtoolsUserService>(relaxed = true)
        val manager = AdbWirelessManager(
            binderProvider = { binder },
            ioDispatcher = testDispatcher,
        )
        every { binder.adbKeysReadStatus() } returns
            ai.closepaw.browser.cdp.shizuku.ChromeDevtoolsUserService.ADB_KEYS_STATUS_EACCES

        val status = manager.pubkeyAuthorizationStatus(SAMPLE_PUBKEY)

        assertThat(status).isEqualTo(AdbWirelessManager.AuthorizationStatus.UNREADABLE)
        // CRITICAL: must NOT read the file when status is EACCES — there's nothing to read,
        // and the contract is that EACCES alone determines UNREADABLE.
        io.mockk.verify(exactly = 0) { binder.readAdbKeys() }
    }

    @Test
    fun `pubkeyAuthorizationStatus returns NOT_AUTHORIZED when adb_keys is missing`() = runTest {
        val binder = mockk<ai.closepaw.browser.cdp.shizuku.IChromeDevtoolsUserService>(relaxed = true)
        val manager = AdbWirelessManager(
            binderProvider = { binder },
            ioDispatcher = testDispatcher,
        )
        every { binder.adbKeysReadStatus() } returns
            ai.closepaw.browser.cdp.shizuku.ChromeDevtoolsUserService.ADB_KEYS_STATUS_MISSING

        val status = manager.pubkeyAuthorizationStatus(SAMPLE_PUBKEY)

        // adbd has no entries when the file is gone. Cache MUST NOT short-circuit; the caller
        // re-pairs.
        assertThat(status).isEqualTo(AdbWirelessManager.AuthorizationStatus.NOT_AUTHORIZED)
    }

    @Test
    fun `pubkeyAuthorizationStatus returns NOT_AUTHORIZED on other read failures`() = runTest {
        val binder = mockk<ai.closepaw.browser.cdp.shizuku.IChromeDevtoolsUserService>(relaxed = true)
        val manager = AdbWirelessManager(
            binderProvider = { binder },
            ioDispatcher = testDispatcher,
        )
        every { binder.adbKeysReadStatus() } returns
            ai.closepaw.browser.cdp.shizuku.ChromeDevtoolsUserService.ADB_KEYS_STATUS_OTHER

        val status = manager.pubkeyAuthorizationStatus(SAMPLE_PUBKEY)

        assertThat(status).isEqualTo(AdbWirelessManager.AuthorizationStatus.NOT_AUTHORIZED)
    }

    @Test
    fun `pubkeyAuthorizationStatus reads file content when status is READABLE`() = runTest {
        val binder = mockk<ai.closepaw.browser.cdp.shizuku.IChromeDevtoolsUserService>(relaxed = true)
        val manager = AdbWirelessManager(
            binderProvider = { binder },
            ioDispatcher = testDispatcher,
        )
        every { binder.adbKeysReadStatus() } returns
            ai.closepaw.browser.cdp.shizuku.ChromeDevtoolsUserService.ADB_KEYS_STATUS_READABLE
        every { binder.readAdbKeys() } returns "OTHER alice@host\n$SAMPLE_PUBKEY ClosePaw@P0110\n"

        assertThat(manager.pubkeyAuthorizationStatus(SAMPLE_PUBKEY))
            .isEqualTo(AdbWirelessManager.AuthorizationStatus.AUTHORIZED)
    }

    // ── Helpers ──

    private fun fakeContext(backing: MutableMap<String, Any?>): Context {
        val prefs = fakePrefs(backing)
        return mockk(relaxed = true) {
            every { getSharedPreferences(any(), any()) } returns prefs
        }
    }

    private fun fakePrefs(backing: MutableMap<String, Any?>): SharedPreferences {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            backing[firstArg()] = secondArg<String?>(); editor
        }
        every { editor.putLong(any(), any()) } answers {
            backing[firstArg()] = secondArg<Long>(); editor
        }
        every { editor.remove(any()) } answers {
            backing.remove(firstArg<String>()); editor
        }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers {
            backing[firstArg()] as? String ?: secondArg()
        }
        every { prefs.getLong(any(), any()) } answers {
            backing[firstArg()] as? Long ?: secondArg()
        }
        return prefs
    }

    /**
     * Per-test scaffolding for the transport ↔ cache integration. Holds mocked collaborators,
     * a real [PairOnceCache] backed by an in-memory map, and the constructed transport.
     */
    private inner class TransportCtx {
        val wireless = mockk<AdbWirelessManager>(relaxed = true)
        val pairing = mockk<AdbPairingClient>(relaxed = true)
        val wire = mockk<AdbWireProtocolClient>(relaxed = true)
        val keyStore = mockk<AdbCryptoKeyStore>(relaxed = true)
        val cache = PairOnceCache(fakeContext(mutableMapOf()), testDispatcher)
        val transport: WirelessAdbSelfPairTransport

        init {
            coEvery { wireless.enableWirelessDebugging() } returns Result.success(Unit)
            coEvery { wireless.getAdbWirelessPort() } returns FAKE_TLS_PORT
            every { keyStore.androidPubkeyBase64() } returns SAMPLE_PUBKEY
            every { keyStore.isPersisted() } returns true
            transport = WirelessAdbSelfPairTransport(
                wirelessManager = wireless,
                keyStore = keyStore,
                pairingClient = pairing,
                wireClient = wire,
                relayAuthToken = TEST_TOKEN,
                pairOnceCache = cache,
                ioDispatcher = testDispatcher,
            )
        }

        /** Stub the openPairPort call so the real-pair branch can run end-to-end. */
        fun stubPair() {
            coEvery { wireless.openPairPort(any(), any()) } returns FAKE_PAIR_PORT
        }

        /** Trigger one bootstrap+exchange round-trip; returns the bytes the wire mock yielded. */
        suspend fun runFirstExchange(returns: ByteArray): ByteArray {
            coEvery { wire.exchange(any(), any(), any(), any(), any()) } returns returns
            return transport.exchange(byteArrayOf(0x00), timeoutMs = 1_000)
        }
    }

    companion object {
        private const val SAMPLE_PUBKEY = "QAAAAdeadbeefcafe1234_sample_pubkey_blob"
        private const val FAKE_TLS_PORT = 48091
        private const val FAKE_PAIR_PORT = 49123
        private const val TEST_TOKEN = "test-token"
    }
}
