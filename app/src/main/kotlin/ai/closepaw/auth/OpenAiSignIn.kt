package ai.closepaw.auth

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
 * callback wait -> auth-code exchange -> cleanup.
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
                        // Skip Codex backend validation here — Run Demo (step 5) exercises a
                        // real LLM call; a separate validation adds 5-15s of SSE wait for no gain.
                        Log.d(TAG, "Sign-in complete")
                        OpenAiSignInResult.Success(exchange.tokens)
                    }
                }
            }
        }
    } finally {
        server.stop()
    }
}
