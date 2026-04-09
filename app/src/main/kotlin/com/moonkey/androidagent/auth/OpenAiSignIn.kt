package com.moonkey.androidagent.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "OpenAiSignIn"

/** Result of an OpenAI OAuth sign-in attempt. */
sealed interface OpenAiSignInResult {
    data class Success(val tokens: OAuthTokens) : OpenAiSignInResult
    data class Error(val message: String) : OpenAiSignInResult
}

/**
 * Run the full OpenAI OAuth sign-in sequence as a suspend function.
 *
 * Steps: PKCE generation -> callback server start -> browser launch ->
 * callback wait -> auth-code exchange -> Codex validation -> cleanup.
 *
 * [launchBrowser] is called with the authorization URL; the host is
 * responsible for opening it (e.g. via an Activity intent or effect channel).
 *
 * Cancellation closes the server socket immediately, unblocking the
 * blocking `accept()` call and freeing the port for retry.
 */
suspend fun openAiSignIn(
    launchBrowser: suspend (url: String) -> Unit,
): OpenAiSignInResult {
    val pkce = generatePkce()
    val state = generateOAuthState()
    val server = OAuthCallbackServer(state)

    return try {
        // 1. Start localhost callback server
        val started = withContext(Dispatchers.IO) { server.start() }
        if (!started) {
            return OpenAiSignInResult.Error(
                "Could not start local server. Is port ${OAuthConfig.CALLBACK_PORT} in use?"
            )
        }

        // 2. Let the host open the browser
        val url = buildAuthorizeUrl(pkce.challenge, state)
        launchBrowser(url)

        // 3. Wait for callback — cancellation closes the socket to unblock accept()
        val callbackResult = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { server.stop() }
            Thread {
                val result = server.waitForCallback()
                if (cont.isActive) cont.resume(result)
            }.start()
        }

        // 4. Process callback
        when (callbackResult) {
            is OAuthCallbackServer.CallbackResult.Error -> {
                OpenAiSignInResult.Error(callbackResult.message)
            }
            is OAuthCallbackServer.CallbackResult.Success -> {
                // 5. Exchange code for tokens
                when (val exchange = OAuthTokenExchange.exchange(callbackResult.code, pkce.verifier)) {
                    is OAuthTokenExchange.Result.Error -> {
                        OpenAiSignInResult.Error(exchange.message)
                    }
                    is OAuthTokenExchange.Result.Success -> {
                        // 6. Validate against Codex backend
                        val validation = OAuthCodexValidator.validate(exchange.tokens.accessToken)
                        if (validation is OAuthCodexValidator.Result.Invalid) {
                            Log.w(TAG, "Codex validation failed: ${validation.message}")
                            OpenAiSignInResult.Error(validation.message)
                        } else {
                            Log.d(TAG, "Sign-in complete")
                            OpenAiSignInResult.Success(exchange.tokens)
                        }
                    }
                }
            }
        }
    } finally {
        server.stop()
    }
}

/**
 * Refresh an OAuth access token. Thin wrapper around [OAuthTokenExchange.refresh]
 * that maps the result to [OpenAiSignInResult].
 */
suspend fun refreshOAuthToken(refreshToken: String): OpenAiSignInResult {
    return when (val result = OAuthTokenExchange.refresh(refreshToken)) {
        is OAuthTokenExchange.Result.Success -> OpenAiSignInResult.Success(result.tokens)
        is OAuthTokenExchange.Result.Error -> OpenAiSignInResult.Error(result.message)
    }
}
