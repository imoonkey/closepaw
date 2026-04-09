package com.moonkey.androidagent.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for OAuth credentials (access token, refresh token, expiry, email).
 *
 * Separate from AppSettingsStore — only stores OAuth-specific data.
 * The access token is also saved to AppSettingsState.apiKey for pipeline compatibility.
 *
 * When encryption is unavailable, credentials are held in memory only for the current
 * process lifetime. A restart requires re-authentication.
 */
class OAuthCredentialStore(private val context: Context) {

    companion object {
        private const val TAG = "OAuthCredentialStore"
        private const val PREFS_NAME = "oauth_credentials"
        private const val KEY_ACCESS_TOKEN = "oauth_access_token"
        private const val KEY_REFRESH_TOKEN = "oauth_refresh_token"
        private const val KEY_EXPIRES_AT = "oauth_expires_at"
        private const val KEY_EMAIL = "oauth_email"
        private const val KEY_ID_TOKEN = "oauth_id_token"
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L // 5 minutes
    }

    private var _prefs: SharedPreferences? = null
    private var _encryptionDegraded = false

    /** True when encrypted storage is unavailable. Credentials exist only in memory. */
    val encryptionDegraded: Boolean get() = _encryptionDegraded

    /** In-memory cache for current-session tokens when encryption is unavailable. */
    private var memoryTokens: OAuthTokens? = null

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
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { _prefs = it }
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, credentials will be memory-only: ${e.message}")
            _encryptionDegraded = true
            null
        }
    }

    fun save(tokens: OAuthTokens) {
        val p = prefs()
        if (p == null) {
            memoryTokens = tokens
            return
        }
        try {
            p.edit()
                .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
                .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
                .putLong(KEY_EXPIRES_AT, tokens.expiresAt)
                .putString(KEY_EMAIL, tokens.email)
                .putString(KEY_ID_TOKEN, tokens.idToken)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save OAuth credentials: ${e.message}")
            memoryTokens = tokens
        }
    }

    fun load(): OAuthTokens? {
        val p = prefs() ?: return memoryTokens
        return try {
            val accessToken = p.getString(KEY_ACCESS_TOKEN, null) ?: return memoryTokens
            val refreshToken = p.getString(KEY_REFRESH_TOKEN, null) ?: return memoryTokens
            OAuthTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = p.getLong(KEY_EXPIRES_AT, 0),
                email = p.getString(KEY_EMAIL, null),
                idToken = p.getString(KEY_ID_TOKEN, null)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load OAuth credentials: ${e.message}")
            memoryTokens
        }
    }

    fun clear() {
        memoryTokens = null
        try {
            prefs()?.edit()?.clear()?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear OAuth credentials: ${e.message}")
        }
    }

    /** True if token is missing or will expire within the buffer window. */
    fun isExpiringSoon(): Boolean {
        val tokens = load() ?: return false
        return tokens.expiresAt - System.currentTimeMillis() < REFRESH_BUFFER_MS
    }
}
