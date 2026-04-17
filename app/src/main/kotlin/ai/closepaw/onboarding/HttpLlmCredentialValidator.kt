package ai.closepaw.onboarding

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException

/**
 * Validates an API key by sending a minimal inference request to the provider.
 *
 * Uses HttpURLConnection (no extra dependency) for exact HTTP status code mapping
 * and bounded timeouts. No automatic retries — UI has explicit Retry button.
 */
class HttpLlmCredentialValidator(
    private val baseUrl: String,
    private val modelId: String,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : LlmCredentialValidator {

    companion object {
        private const val TAG = "LlmCredValidator"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 20_000
    }

    override suspend fun validate(key: String): LlmCredentialValidator.Result =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
                val body = JSONObject().apply {
                    put("model", modelId)
                    put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", "Reply with OK")
                    ))
                    put("max_tokens", 1)
                }.toString()

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }

                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode

                Log.d(TAG, "Validation response: HTTP $code")

                when {
                    code == 200 -> LlmCredentialValidator.Result.Valid
                    code == 401 || code == 403 ->
                        LlmCredentialValidator.Result.InvalidKey(
                            "That key was rejected. Check the value and try again."
                        )
                    code == 408 || code == 429 || code >= 500 ->
                        LlmCredentialValidator.Result.TransientError(
                            "We couldn't reach the model provider. Try again in a moment."
                        )
                    code == 400 || code == 404 ->
                        LlmCredentialValidator.Result.TransientError(
                            "Provider configuration issue. Please try again."
                        )
                    else ->
                        LlmCredentialValidator.Result.TransientError(
                            "Unexpected response (HTTP $code). Please try again."
                        )
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Validation timeout", e)
                LlmCredentialValidator.Result.TransientError(
                    "Request timed out. Check your internet connection."
                )
            } catch (e: SSLException) {
                Log.w(TAG, "SSL error during validation", e)
                LlmCredentialValidator.Result.TransientError(
                    "Secure connection failed. Check your internet connection."
                )
            } catch (e: IOException) {
                Log.w(TAG, "Network error during validation", e)
                LlmCredentialValidator.Result.TransientError(
                    "Check your internet connection and try again."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected validation error", e)
                LlmCredentialValidator.Result.TransientError(
                    "Validation failed: ${e.message}"
                )
            } finally {
                connection?.disconnect()
            }
        }
}
