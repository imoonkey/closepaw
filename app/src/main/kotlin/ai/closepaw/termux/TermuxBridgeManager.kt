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

class TermuxBridgeManager(context: Context) {
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
        private const val DEPLOY_BRIDGE_COMMAND =
            "mkdir -p ~/.closepaw ~/closepaw/workspace ~/closepaw/artifacts ~/closepaw/logs && " +
                "base64 -d > ~/.closepaw/bridge.py && " +
                "python3 -m py_compile ~/.closepaw/bridge.py && echo CLOSEPAW_DEPLOY=ok"
        private const val BRIDGE_EXISTS_COMMAND =
            "test -f ~/.closepaw/bridge.py && echo CLOSEPAW_BRIDGE=present"

        // Probe first so already-installed runtimes do not depend on networked apt.
        // The hardcoded prefix is needed because RUN_COMMAND shells do not populate $PREFIX.
        private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        private const val INSTALL_COMMAND =
            "command -v python3 && command -v git && command -v rg || " +
                "(([ -L $TERMUX_PREFIX/etc/termux/chosen_mirrors ] || " +
                "ln -sf $TERMUX_PREFIX/etc/termux/mirrors/default $TERMUX_PREFIX/etc/termux/chosen_mirrors) && " +
                "$TERMUX_PREFIX/bin/apt update -y -o Acquire::Check-Valid-Until=false && " +
                "$TERMUX_PREFIX/bin/apt install -y python git ripgrep -o Acquire::Check-Valid-Until=false && " +
                "command -v python3 && command -v git && command -v rg)"
        private const val START_BRIDGE_COMMAND =
            "mkdir -p ~/closepaw/logs || exit 1; " +
                "nohup python3 ~/.closepaw/bridge.py >/dev/null 2>~/closepaw/logs/bridge.err </dev/null & " +
                "pid=${'$'}!; sleep 1; " +
                "if ! kill -0 ${'$'}pid 2>/dev/null; then " +
                "echo CLOSEPAW_START=failed; cat ~/closepaw/logs/bridge.err; exit 1; fi; " +
                "echo CLOSEPAW_START=ok"

        fun get(context: Context): TermuxBridgeManager = TermuxBridgeManagerHolder.instance(context)
    }

    private val context: Context = context.applicationContext
    private val _state = MutableStateFlow<TermuxBridgeStatus>(TermuxBridgeStatus.NotInstalled)
    val state: StateFlow<TermuxBridgeStatus> = _state.asStateFlow()
    private val mutex = Mutex()
    private val adapter = TermuxRunCommandAdapter(this.context)
    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    private val healthPollingLock = Any()
    private var healthPollingJob: Job? = null

    suspend fun setup(): TermuxBridgeStatus = mutex.withLock { setupLocked() }

    suspend fun healthCheck(): TermuxBridgeStatus = mutex.withLock {
        if (!detectTermuxInstalled()) emit(TermuxBridgeStatus.NotInstalled) else emit(fetchHealth().toPassiveStatus())
    }

    suspend fun detectInstalled(): TermuxBridgeStatus = mutex.withLock { detectInstalledLocked() }

    suspend fun ensureReadyForSession(timeoutMs: Long): TermuxBridgeStatus = mutex.withLock {
        withTimeoutOrNull(timeoutMs) { ensureReadyForSessionLocked() }
            ?: emit(needsSetup(NeedsSetupReason.HEALTH_TIMEOUT))
    }

    suspend fun restart(): TermuxBridgeStatus = mutex.withLock { restartLocked() }

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
        return runWithSetupState { doBootstrap() }
    }

    private suspend fun restartLocked(): TermuxBridgeStatus {
        return runWithSetupState { doRestart() }
    }

    private suspend fun runWithSetupState(block: suspend () -> TermuxBridgeStatus): TermuxBridgeStatus {
        val priorState = _state.value
        emit(TermuxBridgeStatus.SetupInProgress)
        return try {
            emit(block())
        } catch (ce: CancellationException) {
            _state.value = priorState.restoredAfterCancellation()
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "Termux setup failed: ${e.message}", e)
            emit(needsSetup(NeedsSetupReason.UNKNOWN))
        }
    }

    private suspend fun doBootstrap(): TermuxBridgeStatus {
        if (!detectTermuxInstalled()) {
            return TermuxBridgeStatus.NotInstalled
        }

        val probe =
            try {
                adapter.runShell("echo CLOSEPAW_PROBE=ok", timeoutMs = PROBE_TIMEOUT_MS)
            } catch (e: RunCommandError) {
                return needsSetup(e.toReason(NeedsSetupReason.UNKNOWN))
            }
        if (!probe.stdout.contains("CLOSEPAW_PROBE=ok")) {
            return needsSetup(NeedsSetupReason.UNKNOWN)
        }

        val install =
            try {
                adapter.runShell(INSTALL_COMMAND, timeoutMs = INSTALL_TIMEOUT_MS)
            } catch (e: RunCommandError) {
                return needsSetup(e.toReason(NeedsSetupReason.PACKAGES_MISSING))
            }
        if (install.exitCode != 0 || !install.stdout.hasInstalledBinaries()) {
            return needsSetup(NeedsSetupReason.PACKAGES_MISSING)
        }

        val deploy =
            try {
                adapter.runShell(
                    DEPLOY_BRIDGE_COMMAND,
                    stdinBase64 = bridgeResourceBase64(),
                    timeoutMs = DEPLOY_TIMEOUT_MS
                )
            } catch (e: RunCommandError) {
                return needsSetup(e.toReason(NeedsSetupReason.UNKNOWN))
            }
        if (deploy.exitCode != 0 || !deploy.stdout.contains("CLOSEPAW_DEPLOY=ok")) {
            return needsSetup(NeedsSetupReason.UNKNOWN)
        }

        return when (val start = startBridge()) {
            StartResult.Started -> waitForReadyHealth().toStartupStatus()
            is StartResult.Failed -> needsSetup(start.reason)
        }
    }

    private suspend fun doRestart(): TermuxBridgeStatus {
        if (!detectTermuxInstalled()) {
            return TermuxBridgeStatus.NotInstalled
        }

        return when (val start = startBridge()) {
            StartResult.Started ->
                when (waitForReadyHealth()) {
                    HealthProbe.Ready -> TermuxBridgeStatus.Ready
                    HealthProbe.BridgeOutdated -> doBootstrap()
                    HealthProbe.InvalidIdentity,
                    HealthProbe.Unavailable -> needsSetup(NeedsSetupReason.HEALTH_TIMEOUT)
                }
            is StartResult.Failed ->
                if (start.reason == NeedsSetupReason.PORT_IN_USE) {
                    needsSetup(NeedsSetupReason.PORT_IN_USE)
                } else {
                    doBootstrap()
                }
        }
    }

    private suspend fun ensureReadyForSessionLocked(): TermuxBridgeStatus {
        val health = fetchHealth()
        if (health == HealthProbe.Ready) return emit(TermuxBridgeStatus.Ready)
        if (!detectTermuxInstalled()) return emit(TermuxBridgeStatus.NotInstalled)
        if (health == HealthProbe.BridgeOutdated) {
            return emit(needsSetup(NeedsSetupReason.BRIDGE_OUTDATED))
        }

        val passiveStatus = emit(health.toPassiveStatus())
        val bridgeExists =
            try {
                hasDeployedBridge()
            } catch (e: RunCommandError) {
                return emit(needsSetup(e.toReason(NeedsSetupReason.UNKNOWN)))
            }
        if (!bridgeExists) return passiveStatus

        return when (val start = startBridge()) {
            StartResult.Started -> emit(waitForReadyHealth().toStartupStatus())
            is StartResult.Failed -> emit(needsSetup(start.reason))
        }
    }

    private fun detectInstalledLocked(): TermuxBridgeStatus =
        if (detectTermuxInstalled()) emit(needsSetup(NeedsSetupReason.UNKNOWN)) else emit(TermuxBridgeStatus.NotInstalled)

    private fun detectTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private suspend fun hasDeployedBridge(): Boolean {
        val result = adapter.runShell(BRIDGE_EXISTS_COMMAND, timeoutMs = PROBE_TIMEOUT_MS)
        return result.exitCode == 0 && result.stdout.contains("CLOSEPAW_BRIDGE=present")
    }

    private suspend fun bridgeResourceBase64(): String =
        withContext(Dispatchers.IO) {
            context.resources.openRawResource(R.raw.closepaw_bridge_py).use { stream ->
                Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
            }
        }

    private suspend fun startBridge(): StartResult {
        val result =
            try {
                adapter.runShell(START_BRIDGE_COMMAND, timeoutMs = START_TIMEOUT_MS)
            } catch (e: RunCommandError) {
                return StartResult.Failed(e.toReason(NeedsSetupReason.UNKNOWN))
            }

        val output = "${result.stdout}\n${result.stderr}"
        val failed = result.exitCode != 0 || result.stdout.contains("CLOSEPAW_START=failed")
        if (failed) {
            val reason =
                if (output.contains("port_in_use", ignoreCase = true)) {
                    NeedsSetupReason.PORT_IN_USE
                } else {
                    NeedsSetupReason.UNKNOWN
                }
            Log.w(TAG, "Termux bridge start failed: $output")
            return StartResult.Failed(reason)
        }

        if (!result.stdout.contains("CLOSEPAW_START=ok")) {
            Log.w(TAG, "Termux bridge start did not report success: $output")
            return StartResult.Failed(NeedsSetupReason.UNKNOWN)
        }
        return StartResult.Started
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
            RunCommandError.AllowExternalAppsMissing -> NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING
            RunCommandError.TermuxNotAvailable -> NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING
            is RunCommandError.Timeout -> fallback
            is RunCommandError.Other -> fallback
        }

    private fun String.hasInstalledBinaries(): Boolean {
        val binaries = lineSequence().map { it.trim().substringAfterLast('/') }.toSet()
        return listOf("python3", "git", "rg").all { it in binaries }
    }

    private fun HealthProbe.toPassiveStatus(): TermuxBridgeStatus =
        when (this) {
            HealthProbe.Ready -> TermuxBridgeStatus.Ready
            HealthProbe.BridgeOutdated -> needsSetup(NeedsSetupReason.BRIDGE_OUTDATED)
            HealthProbe.InvalidIdentity,
            HealthProbe.Unavailable -> needsSetup(NeedsSetupReason.HEALTH_TIMEOUT)
        }

    private fun HealthProbe.toStartupStatus(): TermuxBridgeStatus =
        when (this) {
            HealthProbe.Ready -> TermuxBridgeStatus.Ready
            HealthProbe.BridgeOutdated -> needsSetup(NeedsSetupReason.BRIDGE_OUTDATED)
            HealthProbe.InvalidIdentity,
            HealthProbe.Unavailable -> needsSetup(NeedsSetupReason.HEALTH_TIMEOUT)
        }

    private fun TermuxBridgeStatus.restoredAfterCancellation(): TermuxBridgeStatus =
        when (this) {
            TermuxBridgeStatus.Ready,
            TermuxBridgeStatus.SetupInProgress -> needsSetup(NeedsSetupReason.UNKNOWN)
            else -> this
        }

    private fun needsSetup(reason: NeedsSetupReason): TermuxBridgeStatus.NeedsSetup =
        TermuxBridgeStatus.NeedsSetup(reason)

    private fun emit(status: TermuxBridgeStatus): TermuxBridgeStatus {
        _state.value = status
        return status
    }

    private sealed class HealthProbe {
        object Ready : HealthProbe(); object BridgeOutdated : HealthProbe()
        object InvalidIdentity : HealthProbe(); object Unavailable : HealthProbe()
    }

    private sealed class StartResult {
        object Started : StartResult(); data class Failed(val reason: NeedsSetupReason) : StartResult()
    }

    private object TermuxBridgeManagerHolder {
        @Volatile private var INSTANCE: TermuxBridgeManager? = null

        fun instance(context: Context): TermuxBridgeManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TermuxBridgeManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
