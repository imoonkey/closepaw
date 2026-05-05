package ai.closepaw.termux

import ai.closepaw.R
import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class TermuxBridgeManager internal constructor(
    private val commandRunner: TermuxCommandRunner,
    private val healthProbe: TermuxHealthProbe,
    private val termuxInstallProbe: TermuxInstallProbe,
    private val bridgePayloadBase64: suspend () -> String,
    private val managerScope: CoroutineScope
) {
    companion object {
        private const val TAG = "TermuxBridgeManager"
        private const val BRIDGE_IDENTITY = "closepaw-bridge"
        private const val BRIDGE_VERSION_EXPECTED = "1"
        private const val HEALTH_URL = "http://127.0.0.1:18422/v1/health"
        private const val PROBE_TIMEOUT_MS = 10_000L
        private const val INSTALL_TIMEOUT_MS = 180_000L
        private const val DEPLOY_TIMEOUT_MS = 30_000L
        private const val START_TIMEOUT_MS = 10_000L
        private const val HEALTH_READY_TIMEOUT_MS = 10_000L
        private const val DEPLOY_BRIDGE_COMMAND =
            "mkdir -p ~/.closepaw ~/closepaw/workspace ~/closepaw/artifacts ~/closepaw/logs && " +
                "tmpf=${'$'}(mktemp ~/.closepaw/bridge.py.XXXXXX) && " +
                "trap 'rm -f \"${'$'}tmpf\"' EXIT && " +
                "base64 -d > \"${'$'}tmpf\" && " +
                "python3 -m py_compile \"${'$'}tmpf\" && " +
                "mv \"${'$'}tmpf\" ~/.closepaw/bridge.py && " +
                "trap - EXIT && echo CLOSEPAW_DEPLOY=ok"
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

        private suspend fun loadBridgePayloadBase64(context: Context): String =
            withContext(Dispatchers.IO) {
                context.resources.openRawResource(R.raw.closepaw_bridge_py).use { stream ->
                    Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
                }
            }
    }

    private val _state = MutableStateFlow<TermuxBridgeStatus>(TermuxBridgeStatus.NotInstalled)
    val state: StateFlow<TermuxBridgeStatus> = _state.asStateFlow()
    private val mutex = Mutex()
    private val inFlightLock = Any()
    private val inFlightJobs = mutableMapOf<OperationKind, Deferred<TermuxBridgeStatus>>()

    constructor(context: Context) : this(
        commandRunner = AndroidTermuxCommandRunner(context.applicationContext),
        healthProbe = HttpTermuxHealthProbe(HEALTH_URL, BRIDGE_IDENTITY, BRIDGE_VERSION_EXPECTED),
        termuxInstallProbe = AndroidTermuxInstallProbe(context.applicationContext.packageManager),
        bridgePayloadBase64 = suspend { loadBridgePayloadBase64(context.applicationContext) },
        managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    suspend fun setup(): TermuxBridgeStatus =
        awaitInFlight(OperationKind.Setup) { mutex.withLock { setupLocked() } }

    suspend fun healthCheck(): TermuxBridgeStatus = mutex.withLock {
        unavailableInstallStatus()?.let { return@withLock emit(it) }
        emit(fetchHealth().toPassiveStatus())
    }

    suspend fun detectInstalled(): TermuxBridgeStatus = mutex.withLock { detectInstalledLocked() }

    suspend fun ensureReadyForSession(timeoutMs: Long): TermuxBridgeStatus =
        awaitInFlight(OperationKind.EnsureReady) {
            withTimeoutOrNull(timeoutMs) { mutex.withLock { ensureReadyForSessionLocked() } }
                ?: emit(needsSetup(NeedsSetupReason.HEALTH_TIMEOUT))
        }

    suspend fun restart(): TermuxBridgeStatus =
        awaitInFlight(OperationKind.Restart) { mutex.withLock { restartLocked() } }

    // No background health polling here: /v1/health refreshes the bridge idle timer.
    // Session creation recovers with ensureReadyForSession(); Settings observes with one-shot probes.

    /** Captures the current bridge capability for a new session. */
    fun snapshot(enabled: Boolean): TermuxCapabilitySnapshot {
        val status = _state.value
        return TermuxCapabilitySnapshot(
            available = status is TermuxBridgeStatus.Ready && enabled,
            enabled = enabled,
            status = status
        )
    }

    private suspend fun awaitInFlight(
        kind: OperationKind,
        block: suspend () -> TermuxBridgeStatus
    ): TermuxBridgeStatus {
        val deferred =
            synchronized(inFlightLock) {
                val current = inFlightJobs[kind]
                if (current?.isActive == true) {
                    current
                } else {
                    managerScope.async { block() }.also { job ->
                        inFlightJobs[kind] = job
                        job.invokeOnCompletion {
                            synchronized(inFlightLock) {
                                if (inFlightJobs[kind] === job) inFlightJobs.remove(kind)
                            }
                        }
                    }
                }
            }
        return deferred.await()
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
        unavailableInstallStatus()?.let { return it }

        val probe =
            try {
                commandRunner.runShell("echo CLOSEPAW_PROBE=ok", timeoutMs = PROBE_TIMEOUT_MS)
            } catch (e: RunCommandError) {
                return needsSetup(e.toProbeReason())
            }
        if (!probe.stdout.contains("CLOSEPAW_PROBE=ok")) {
            return needsSetup(NeedsSetupReason.UNKNOWN)
        }

        val install =
            try {
                commandRunner.runShell(INSTALL_COMMAND, timeoutMs = INSTALL_TIMEOUT_MS)
            } catch (e: RunCommandError) {
                return needsSetup(e.toReason(NeedsSetupReason.PACKAGES_MISSING))
            }
        if (install.exitCode != 0 || !install.stdout.hasInstalledBinaries()) {
            return needsSetup(NeedsSetupReason.PACKAGES_MISSING)
        }

        val deploy =
            try {
                commandRunner.runShell(
                    DEPLOY_BRIDGE_COMMAND,
                    stdinBase64 = bridgePayloadBase64(),
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
        unavailableInstallStatus()?.let { return it }

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
        unavailableInstallStatus()?.let { return emit(it) }
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
        emit(unavailableInstallStatus() ?: needsSetup(NeedsSetupReason.UNKNOWN))

    private fun unavailableInstallStatus(): TermuxBridgeStatus? =
        when (detectTermuxInstall()) {
            TermuxInstallState.NotInstalled -> TermuxBridgeStatus.NotInstalled
            TermuxInstallState.RunCommandUnavailable ->
                needsSetup(NeedsSetupReason.TERMUX_RUN_COMMAND_UNAVAILABLE)
            TermuxInstallState.Available -> null
        }

    private fun detectTermuxInstall(): TermuxInstallState = termuxInstallProbe.inspect()

    private suspend fun hasDeployedBridge(): Boolean {
        val result = commandRunner.runShell(BRIDGE_EXISTS_COMMAND, timeoutMs = PROBE_TIMEOUT_MS)
        return result.exitCode == 0 && result.stdout.contains("CLOSEPAW_BRIDGE=present")
    }

    private suspend fun startBridge(): StartResult {
        val result =
            try {
                commandRunner.runShell(START_BRIDGE_COMMAND, timeoutMs = START_TIMEOUT_MS)
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

    private suspend fun fetchHealth(): HealthProbe = healthProbe.fetch()

    private fun RunCommandError.toReason(fallback: NeedsSetupReason): NeedsSetupReason =
        when (this) {
            RunCommandError.PermissionMissing -> NeedsSetupReason.PERMISSION_MISSING
            RunCommandError.AllowExternalAppsMissing -> NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING
            RunCommandError.TermuxProcessNotRunning -> NeedsSetupReason.TERMUX_NOT_RUNNING
            RunCommandError.TermuxNotAvailable -> NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING
            is RunCommandError.Timeout -> NeedsSetupReason.TERMUX_TIMEOUT
            is RunCommandError.Other -> fallback
        }

    private fun RunCommandError.toProbeReason(): NeedsSetupReason {
        val canBeUnsupportedVariant =
            this == RunCommandError.PermissionMissing || this == RunCommandError.TermuxNotAvailable
        return if (canBeUnsupportedVariant && detectTermuxInstall() == TermuxInstallState.RunCommandUnavailable) {
            NeedsSetupReason.TERMUX_RUN_COMMAND_UNAVAILABLE
        } else {
            toReason(NeedsSetupReason.UNKNOWN)
        }
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

    private sealed class StartResult {
        object Started : StartResult(); data class Failed(val reason: NeedsSetupReason) : StartResult()
    }

    private enum class OperationKind {
        Setup,
        EnsureReady,
        Restart
    }

    private object TermuxBridgeManagerHolder {
        @Volatile private var INSTANCE: TermuxBridgeManager? = null

        fun instance(context: Context): TermuxBridgeManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TermuxBridgeManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
