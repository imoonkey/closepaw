package ai.closepaw.termux

import ai.closepaw.R
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

class TermuxBridgeManager(private val context: Context) {
    companion object {
        private const val TAG = "TermuxBridgeManager"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val BRIDGE_IDENTITY = "closepaw-bridge"
        private const val BRIDGE_VERSION_EXPECTED = "1"
        private const val HEALTH_URL = "http://127.0.0.1:18422/v1/health"
        private const val PROBE_TIMEOUT_MS = 10_000L
        private const val INSTALL_TIMEOUT_MS = 180_000L
        private const val DEPLOY_TIMEOUT_MS = 30_000L
        private const val START_TIMEOUT_MS = 10_000L
        private const val HEALTH_READY_TIMEOUT_MS = 10_000L
        private const val HEALTH_POLL_INTERVAL_MS = 30_000L

        fun get(context: Context): TermuxBridgeManager = TermuxBridgeManagerHolder.instance(context)
    }

    private val _state = MutableStateFlow<TermuxBridgeStatus>(TermuxBridgeStatus.NotInstalled)
    val state: StateFlow<TermuxBridgeStatus> = _state.asStateFlow()
    private val mutex = Mutex()
    private val adapter = TermuxRunCommandAdapter(context.applicationContext)
    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    private val healthPollingLock = Any()
    private var healthPollingJob: Job? = null

    suspend fun setup(): TermuxBridgeStatus {
        val current = _state.value
        if (current is TermuxBridgeStatus.SetupInProgress) return current

        return mutex.withLock {
            val locked = _state.value
            if (locked is TermuxBridgeStatus.SetupInProgress) locked else setupLocked()
        }
    }

    suspend fun healthCheck(): TermuxBridgeStatus {
        val current = _state.value
        if (current is TermuxBridgeStatus.SetupInProgress) return current

        return mutex.withLock {
            val locked = _state.value
            if (locked is TermuxBridgeStatus.SetupInProgress) return@withLock locked
            if (!detectTermuxInstalled()) return@withLock emit(TermuxBridgeStatus.NotInstalled)

            when (fetchHealth()) {
                HealthProbe.Ready -> emit(TermuxBridgeStatus.Ready)
                HealthProbe.BridgeOutdated,
                HealthProbe.InvalidIdentity,
                HealthProbe.Unavailable -> setupLocked()
            }
        }
    }

    suspend fun restart(): TermuxBridgeStatus {
        return setup()
    }

    fun startHealthPolling(scope: CoroutineScope) {
        synchronized(healthPollingLock) {
            healthPollingJob?.cancel()
            healthPollingJob =
                scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        try {
                            healthCheck()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Termux health polling failed: ${e.message}", e)
                            emit(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.UNKNOWN))
                        }
                        delay(HEALTH_POLL_INTERVAL_MS)
                    }
                }
        }
    }

    /** Captures the current bridge capability for a new session. */
    fun snapshot(enabled: Boolean): TermuxCapabilitySnapshot {
        val status = _state.value
        return TermuxCapabilitySnapshot(
            available = status is TermuxBridgeStatus.Ready && enabled,
            enabled = enabled,
            status = status
        )
    }

    private suspend fun setupLocked(): TermuxBridgeStatus {
        emit(TermuxBridgeStatus.SetupInProgress)

        if (!detectTermuxInstalled()) {
            return emit(TermuxBridgeStatus.NotInstalled)
        }

        val probe =
            try {
                adapter.runShell("echo CLOSEPAW_PROBE=ok", timeoutMs = PROBE_TIMEOUT_MS)
            } catch (e: RunCommandError) {
                return emit(needsSetup(e.toReason(NeedsSetupReason.UNKNOWN)))
            }
        if (!probe.stdout.contains("CLOSEPAW_PROBE=ok")) {
            return emit(needsSetup(NeedsSetupReason.UNKNOWN))
        }

        val install =
            try {
                adapter.runShell(
                    "pkg install -y python git ripgrep && which python3 && which git && which rg",
                    timeoutMs = INSTALL_TIMEOUT_MS
                )
            } catch (e: RunCommandError) {
                return emit(needsSetup(e.toReason(NeedsSetupReason.PACKAGES_MISSING)))
            }
        if (install.exitCode != 0 || !install.stdout.hasInstalledBinaries()) {
            return emit(needsSetup(NeedsSetupReason.PACKAGES_MISSING))
        }

        val deploy =
            try {
                adapter.runShell(
                    "mkdir -p ~/.closepaw ~/closepaw/workspace ~/closepaw/artifacts ~/closepaw/logs && cat > ~/.closepaw/bridge.py && echo CLOSEPAW_DEPLOY=ok",
                    stdinBase64 = bridgeResourceBase64(),
                    timeoutMs = DEPLOY_TIMEOUT_MS
                )
            } catch (e: RunCommandError) {
                return emit(needsSetup(e.toReason(NeedsSetupReason.UNKNOWN)))
            }
        if (!deploy.stdout.contains("CLOSEPAW_DEPLOY=ok")) {
            return emit(needsSetup(NeedsSetupReason.UNKNOWN))
        }

        try {
            adapter.runShell(
                "nohup python3 ~/.closepaw/bridge.py >/dev/null 2>&1 &",
                timeoutMs = START_TIMEOUT_MS
            )
        } catch (e: RunCommandError) {
            return emit(needsSetup(e.toReason(NeedsSetupReason.UNKNOWN)))
        }

        return when (waitForReadyHealth()) {
            HealthProbe.Ready -> emit(TermuxBridgeStatus.Ready)
            HealthProbe.BridgeOutdated -> emit(needsSetup(NeedsSetupReason.BRIDGE_OUTDATED))
            HealthProbe.InvalidIdentity,
            HealthProbe.Unavailable -> emit(needsSetup(NeedsSetupReason.HEALTH_TIMEOUT))
        }
    }

    private fun detectTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private suspend fun bridgeResourceBase64(): String =
        withContext(Dispatchers.IO) {
            context.resources.openRawResource(R.raw.closepaw_bridge_py).use { stream ->
                Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
            }
        }

    private suspend fun waitForReadyHealth(): HealthProbe {
        return withTimeoutOrNull(HEALTH_READY_TIMEOUT_MS) {
            while (true) {
                when (val health = fetchHealth()) {
                    HealthProbe.Ready,
                    HealthProbe.BridgeOutdated -> return@withTimeoutOrNull health
                    HealthProbe.InvalidIdentity,
                    HealthProbe.Unavailable -> delay(250)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            HealthProbe.Unavailable
        } ?: HealthProbe.Unavailable
    }

    private suspend fun fetchHealth(): HealthProbe =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(HEALTH_URL).get().build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext HealthProbe.Unavailable
                    val json = JSONObject(response.body.string())
                    val identity = json.optString("identity")
                    val version = json.optString("version")

                    when {
                        identity != BRIDGE_IDENTITY -> HealthProbe.InvalidIdentity
                        version != BRIDGE_VERSION_EXPECTED -> HealthProbe.BridgeOutdated
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

    private fun RunCommandError.toReason(fallback: NeedsSetupReason): NeedsSetupReason =
        when (this) {
            RunCommandError.PermissionMissing -> NeedsSetupReason.PERMISSION_MISSING
            RunCommandError.TermuxNotAvailable -> NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING
            is RunCommandError.Timeout -> fallback
            is RunCommandError.Other -> fallback
        }

    private fun String.hasInstalledBinaries(): Boolean {
        val binaries = lineSequence().map { it.trim().substringAfterLast('/') }.toSet()
        return listOf("python3", "git", "rg").all { it in binaries }
    }

    private fun needsSetup(reason: NeedsSetupReason): TermuxBridgeStatus.NeedsSetup =
        TermuxBridgeStatus.NeedsSetup(reason)

    private fun emit(status: TermuxBridgeStatus): TermuxBridgeStatus {
        _state.value = status
        return status
    }

    private sealed class HealthProbe {
        object Ready : HealthProbe()
        object BridgeOutdated : HealthProbe()
        object InvalidIdentity : HealthProbe()
        object Unavailable : HealthProbe()
    }

    private object TermuxBridgeManagerHolder {
        @Volatile private var INSTANCE: TermuxBridgeManager? = null

        fun instance(context: Context): TermuxBridgeManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TermuxBridgeManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
