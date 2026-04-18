package ai.closepaw.auth

import ai.closepaw.llm.LLMProvider
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Unified credential store for all cloud LLM providers.
 *
 * Keyed by [LLMProvider]`.name`. Backed by [EncryptedSharedPreferences] with an
 * in-memory fallback when platform encryption is unavailable (same degraded-mode
 * semantics as the legacy `OAuthCredentialStore`).
 *
 * OAuth token refresh for Codex is serialized through [refreshMutex] so concurrent
 * callers don't race. Network refresh is delegated to an injected [refresher].
 */
class AuthStore(
    private val context: Context,
    private val refresher: suspend (refreshToken: String) -> AuthCredential.OAuth = {
        throw NotImplementedError("refresher not configured")
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "AuthStore"
        private const val PREFS_NAME = "auth_store"
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L
    }

    private var _prefs: SharedPreferences? = null
    @Volatile private var _encryptionDegraded = false
    private val memory = ConcurrentHashMap<String, AuthCredential>()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val encryptionDegraded: Boolean get() = _encryptionDegraded

    private fun prefs(): SharedPreferences? {
        if (_encryptionDegraded) return null
        _prefs?.let { return it }
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { _prefs = it }
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, memory-only: ${e.message}")
            _encryptionDegraded = true
            null
        }
    }

    suspend fun get(provider: LLMProvider): AuthCredential? = read(provider.name)

    suspend fun set(provider: LLMProvider, cred: AuthCredential) {
        write(provider.name, cred)
        bump(provider.name)
    }

    suspend fun clear(provider: LLMProvider) {
        write(provider.name, null)
        bump(provider.name)
    }

    fun has(provider: LLMProvider): Boolean = read(provider.name) != null

    fun generation(provider: LLMProvider): Long =
        generations[provider.name]?.get() ?: 0L

    /** Return the API key for [provider] or throw a typed error. */
    fun requireApiKey(provider: LLMProvider): String {
        val cred = read(provider.name) ?: throw MissingCredential(provider)
        return when (cred) {
            is AuthCredential.ApiKey -> cred.key
            is AuthCredential.OAuth -> throw WrongCredentialType(
                provider = provider,
                expected = "ApiKey",
                actual = "OAuth",
            )
        }
    }

    /**
     * Return fresh Codex headers for [provider]. Refreshes under [refreshMutex]
     * if the cached access token is within [REFRESH_BUFFER_MS] of expiry.
     */
    suspend fun codexHeaders(provider: LLMProvider): CodexHeaders {
        // Fast path: fresh token, no lock.
        val current = read(provider.name) ?: throw MissingCredential(provider)
        if (current !is AuthCredential.OAuth) {
            throw WrongCredentialType(provider, expected = "OAuth", actual = "ApiKey")
        }
        if (!isExpiringSoon(current)) {
            return current.toHeaders()
        }

        return refreshMutex.withLock {
            val genBefore = generation(provider)
            val latest = read(provider.name) ?: throw MissingCredential(provider)
            if (latest !is AuthCredential.OAuth) {
                throw WrongCredentialType(provider, expected = "OAuth", actual = "ApiKey")
            }
            if (!isExpiringSoon(latest)) {
                return@withLock latest.toHeaders()
            }
            val refreshed = try {
                refresher(latest.refreshToken)
            } catch (t: Throwable) {
                throw OAuthRefreshFailed(provider, t)
            }
            // If set/clear ran concurrently during the refresh network call, do NOT
            // clobber the caller's write. Yield to whatever they committed.
            if (generation(provider) == genBefore) {
                write(provider.name, refreshed)
                bump(provider.name)
                refreshed.toHeaders()
            } else {
                val now = read(provider.name) ?: throw MissingCredential(provider)
                if (now !is AuthCredential.OAuth) {
                    throw WrongCredentialType(provider, expected = "OAuth", actual = "ApiKey")
                }
                now.toHeaders()
            }
        }
    }

    private fun isExpiringSoon(cred: AuthCredential.OAuth): Boolean =
        cred.expiresAt - nowMs() < REFRESH_BUFFER_MS

    private fun AuthCredential.OAuth.toHeaders(): CodexHeaders = CodexHeaders(
        accessToken = accessToken,
        chatgptAccountId = idToken?.let(::parseChatgptAccountId),
        email = email,
    )

    private fun bump(key: String) {
        generations.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
    }

    private fun read(key: String): AuthCredential? {
        val p = prefs()
        if (p == null) return memory[key]
        return try {
            val raw = p.getString(key, null) ?: return memory[key]
            json.decodeFromString<StoredCredential>(raw).toDomain()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $key: ${e.message}")
            memory[key]
        }
    }

    private fun write(key: String, cred: AuthCredential?) {
        val p = prefs()
        if (p == null) {
            if (cred == null) memory.remove(key) else memory[key] = cred
            return
        }
        try {
            val editor = p.edit()
            if (cred == null) editor.remove(key)
            else editor.putString(key, json.encodeToString(cred.toStored()))
            editor.apply()
            // Keep memory in sync as a write-through (harmless when reading back from prefs).
            if (cred == null) memory.remove(key) else memory[key] = cred
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write $key: ${e.message}")
            if (cred == null) memory.remove(key) else memory[key] = cred
        }
    }

    @Serializable
    private data class StoredCredential(
        val type: String,
        val key: String? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val expiresAt: Long? = null,
        val email: String? = null,
        val idToken: String? = null,
    ) {
        fun toDomain(): AuthCredential = when (type) {
            "api_key" -> AuthCredential.ApiKey(key!!)
            "oauth" -> AuthCredential.OAuth(
                accessToken = accessToken!!,
                refreshToken = refreshToken!!,
                expiresAt = expiresAt!!,
                email = email,
                idToken = idToken,
            )
            else -> error("Unknown credential type: $type")
        }
    }

    private fun AuthCredential.toStored(): StoredCredential = when (this) {
        is AuthCredential.ApiKey -> StoredCredential(type = "api_key", key = key)
        is AuthCredential.OAuth -> StoredCredential(
            type = "oauth",
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
            email = email,
            idToken = idToken,
        )
    }
}

/**
 * Best-effort extraction of the `chatgpt_account_id` claim from a JWT payload.
 * Returns null if the token is malformed or the claim is absent.
 */
private fun parseChatgptAccountId(idToken: String): String? {
    val parts = idToken.split('.')
    if (parts.size < 2) return null
    return try {
        val payload = java.util.Base64.getUrlDecoder().decode(padBase64(parts[1]))
        val json = String(payload, Charsets.UTF_8)
        Regex("\"chatgpt_account_id\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    } catch (_: Exception) {
        null
    }
}

private fun padBase64(s: String): String {
    val pad = (4 - s.length % 4) % 4
    return s + "=".repeat(pad)
}
