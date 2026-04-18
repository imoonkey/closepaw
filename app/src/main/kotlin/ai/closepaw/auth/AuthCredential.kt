package ai.closepaw.auth

sealed class AuthCredential {
    data class ApiKey(val key: String) : AuthCredential()

    data class OAuth(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val email: String?,
        val idToken: String?,
    ) : AuthCredential()
}

data class CodexHeaders(
    val accessToken: String,
    val chatgptAccountId: String?,
    val email: String?,
)
