package ai.closepaw.auth

import ai.closepaw.llm.LLMProvider

sealed class AuthError(message: String, cause: Throwable? = null) : Exception(message, cause)

class MissingCredential(val provider: LLMProvider) :
    AuthError("No credential for provider ${provider.name}")

class OAuthRefreshFailed(val provider: LLMProvider, cause: Throwable) :
    AuthError("OAuth refresh failed for ${provider.name}: ${cause.message}", cause)

class WrongCredentialType(
    val provider: LLMProvider,
    val expected: String,
    val actual: String,
) : AuthError("Provider ${provider.name} expected $expected credential, got $actual")
