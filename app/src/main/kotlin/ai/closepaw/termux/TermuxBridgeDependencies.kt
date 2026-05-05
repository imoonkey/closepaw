package ai.closepaw.termux

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

internal interface TermuxCommandRunner {
    suspend fun runShell(
        command: String,
        stdinBase64: String? = null,
        timeoutMs: Long
    ): RunCommandResult
}

internal fun interface TermuxHealthProbe {
    suspend fun fetch(): HealthProbe
}

internal enum class HealthProbe {
    Ready,
    BridgeOutdated,
    InvalidIdentity,
    Unavailable
}

internal class AndroidTermuxCommandRunner(context: Context) : TermuxCommandRunner {
    private val adapter = TermuxRunCommandAdapter(context)

    override suspend fun runShell(
        command: String,
        stdinBase64: String?,
        timeoutMs: Long
    ): RunCommandResult = adapter.runShell(command, stdinBase64, timeoutMs)
}

internal class HttpTermuxHealthProbe(
    private val healthUrl: String,
    private val expectedIdentity: String,
    private val expectedVersion: String
) : TermuxHealthProbe {
    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()

    override suspend fun fetch(): HealthProbe =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(healthUrl).get().build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext HealthProbe.Unavailable
                    val json = JSONObject(response.body.string())
                    val identity = json.optString("identity")
                    val version = json.optString("version")

                    when {
                        identity != expectedIdentity -> HealthProbe.InvalidIdentity
                        version != expectedVersion -> HealthProbe.BridgeOutdated
                        else -> HealthProbe.Ready
                    }
                }
            } catch (_: IOException) {
                HealthProbe.Unavailable
            } catch (_: JSONException) {
                HealthProbe.Unavailable
            } catch (_: IllegalStateException) {
                HealthProbe.Unavailable
            }
        }
}
