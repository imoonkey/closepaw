package ai.closepaw.browser.cdp.wireless

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistent witness that the local pubkey was, at some recent point, accepted by adbd's pair
 * service on this device. Lets [WirelessAdbSelfPairTransport] skip a SPAKE2 re-pair on every
 * cold session when `/data/misc/adb/adb_keys` is unreadable to the Shizuku-spawned shell uid
 * (locked OEMs like nubia P0110): if the persisted pubkey hasn't rotated and we already paired
 * once, the immediately-following mTLS handshake is the authoritative test of trust — no need
 * to burn a fresh PSK round-trip just to re-confirm something we already know.
 *
 * Stores only a SHA-256 of the base64 pubkey blob, never the pubkey itself: the cache file is
 * world-readable as far as the app's `prefs/` directory goes, and the digest is enough to detect
 * key rotation.
 */
class PairOnceCache(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Returns the previously-recorded fingerprint, or null when the cache is empty. */
    suspend fun getCachedFingerprint(): String? = withContext(ioDispatcher) {
        prefs().getString(KEY_FINGERPRINT, null)
    }

    /** Persist [fingerprintBase64] as proof that pairing for this pubkey succeeded. */
    suspend fun recordSuccessfulPair(fingerprintBase64: String) {
        require(fingerprintBase64.isNotEmpty()) { "fingerprintBase64 must not be empty" }
        withContext(ioDispatcher) {
            prefs().edit()
                .putString(KEY_FINGERPRINT, fingerprintBase64)
                .putLong(KEY_LAST_PAIRED_AT_MS, System.currentTimeMillis())
                .apply()
        }
    }

    /** Drop the cache entry — caller must re-pair on the next bootstrap. */
    suspend fun invalidate() {
        withContext(ioDispatcher) {
            prefs().edit()
                .remove(KEY_FINGERPRINT)
                .remove(KEY_LAST_PAIRED_AT_MS)
                .apply()
        }
    }

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "adb_pair_once_cache"
        private const val KEY_FINGERPRINT = "pubkey_fingerprint_sha256_b64"
        private const val KEY_LAST_PAIRED_AT_MS = "last_paired_at_ms"

        /**
         * SHA-256 of the US-ASCII bytes of [pubkeyBase64], rendered as unpadded base64. Two
         * sessions of the same RSA keypair produce identical fingerprints; rotation of the
         * persisted keypair flips the fingerprint and forces a re-pair.
         */
        fun fingerprintOf(pubkeyBase64: String): String {
            require(pubkeyBase64.isNotEmpty()) { "pubkeyBase64 must not be empty" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(pubkeyBase64.toByteArray(Charsets.US_ASCII))
            return Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
