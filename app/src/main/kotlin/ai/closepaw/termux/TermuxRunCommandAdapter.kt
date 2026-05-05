package ai.closepaw.termux

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class RunCommandResult(val stdout: String, val stderr: String, val exitCode: Int?)

sealed class RunCommandError : Exception() {
    object PermissionMissing : RunCommandError()
    object AllowExternalAppsMissing : RunCommandError()
    object TermuxNotAvailable : RunCommandError()
    data class Timeout(val ms: Long) : RunCommandError()
    data class Other(override val cause: Throwable?) : RunCommandError()
}

class TermuxRunCommandAdapter(private val context: Context) {

    suspend fun run(
        executable: String,
        args: List<String>,
        stdinBase64: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): RunCommandResult {
        return withTimeoutOrNull(timeoutMs) {
            awaitRunCommand(executable, args, stdinBase64)
        } ?: throw RunCommandError.Timeout(timeoutMs)
    }

    suspend fun runShell(
        command: String,
        stdinBase64: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): RunCommandResult =
        run(TERMUX_BASH_PATH, listOf("-c", command), stdinBase64, timeoutMs)

    private suspend fun awaitRunCommand(
        executable: String,
        args: List<String>,
        stdinBase64: String?
    ): RunCommandResult =
        suspendCancellableCoroutine { continuation ->
            val appContext = context.applicationContext
            val requestId = UUID.randomUUID().toString()
            val resultAction = "$ACTION_RESULT_PREFIX.$requestId"
            val receiverRegistered = AtomicBoolean(false)

            lateinit var receiver: BroadcastReceiver

            fun unregisterReceiverIfNeeded() {
                if (!receiverRegistered.compareAndSet(true, false)) return
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "RUN_COMMAND receiver was not registered: ${e.message}")
                }
            }

            fun fail(error: RunCommandError) {
                unregisterReceiverIfNeeded()
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action != resultAction) return
                        if (intent.getStringExtra(EXTRA_REQUEST_ID) != requestId) return

                        unregisterReceiverIfNeeded()
                        if (continuation.isActive) {
                            val error = intent.toRunCommandError()
                            if (error != null) {
                                continuation.resumeWithException(error)
                            } else {
                                continuation.resume(intent.toRunCommandResult())
                            }
                        }
                    }
                }

            continuation.invokeOnCancellation { unregisterReceiverIfNeeded() }

            val pendingIntent =
                try {
                    PendingIntent.getBroadcast(
                        appContext,
                        requestId.hashCode(),
                        Intent(resultAction)
                            .setPackage(appContext.packageName)
                            .putExtra(EXTRA_REQUEST_ID, requestId),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                } catch (t: Throwable) {
                    fail(RunCommandError.Other(t))
                    return@suspendCancellableCoroutine
                }

            val runCommandIntent =
                Intent()
                    .setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                    .setAction(ACTION_RUN_COMMAND)
                    .putExtra(EXTRA_RUN_COMMAND_PATH, executable)
                    .putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, args.toTypedArray())
                    .putExtra(EXTRA_RUN_COMMAND_BACKGROUND, true)
                    .putExtra(EXTRA_RUN_COMMAND_SESSION_NAME, "closepaw-bootstrap-$requestId")
                    .putExtra(EXTRA_RUN_COMMAND_PENDING_INTENT, pendingIntent)

            if (stdinBase64 != null) {
                // Stdin is already base64-encoded so binary bridge content avoids shell quoting issues.
                runCommandIntent.putExtra(EXTRA_RUN_COMMAND_STDIN, stdinBase64)
            }

            try {
                registerResultReceiver(appContext, receiver, IntentFilter(resultAction))
                receiverRegistered.set(true)
            } catch (t: Throwable) {
                fail(RunCommandError.Other(t))
                return@suspendCancellableCoroutine
            }

            try {
                // Use startForegroundService on Android 8+ — Android 13's BG-FGS-START
                // restrictions reject plain startService for cross-app service starts even
                // when the caller is a foreground Activity. Termux's RunCommandService
                // declares dataSync FGS type and calls startForeground() during onStartCommand.
                val started =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(runCommandIntent)
                    } else {
                        appContext.startService(runCommandIntent)
                    }
                if (started == null) {
                    fail(RunCommandError.TermuxNotAvailable)
                }
            } catch (e: SecurityException) {
                fail(RunCommandError.PermissionMissing)
            } catch (e: ActivityNotFoundException) {
                fail(RunCommandError.TermuxNotAvailable)
            } catch (e: IllegalArgumentException) {
                fail(RunCommandError.TermuxNotAvailable)
            } catch (t: Throwable) {
                fail(RunCommandError.Other(t))
            }
        }

    private fun registerResultReceiver(
        appContext: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Termux invokes our PendingIntent via Binder, so the resulting broadcast appears
            // to come from Termux's UID; RECEIVER_NOT_EXPORTED rejects it. The PendingIntent
            // already restricts the sender to whoever holds the PendingIntent reference, and
            // we set its target package to our own, so this broadcast surface stays narrow.
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun Intent.toRunCommandError(): RunCommandError? {
        val result = getBundleExtra(EXTRA_RESULT_BUNDLE)
        val err = result.errorCodeResult(this)
        val errmsg = result.stringResult(this, RESULT_ERROR_MESSAGE).trim()
        val combined = listOfNotNull(err, errmsg.takeIf { it.isNotBlank() }).joinToString("\n")

        return when {
            errmsg.contains("allow-external-apps", ignoreCase = true) ->
                RunCommandError.AllowExternalAppsMissing
            combined.contains("PluginErrorCode_PERMISSION", ignoreCase = true) ->
                RunCommandError.PermissionMissing
            // Termux v0.118+ uses err=-1 to mean "no execution-stage error"; only treat
            // positive err codes (or any non-empty errmsg) as a transport-level failure.
            errmsg.isNotBlank() || err.isPositiveErrorCode() ->
                RunCommandError.Other(
                    IllegalStateException("Termux RUN_COMMAND error $err: $errmsg")
                )
            else -> null
        }
    }

    private fun Intent.toRunCommandResult(): RunCommandResult {
        val result = getBundleExtra(EXTRA_RESULT_BUNDLE)
        return RunCommandResult(
            stdout = result.stringResult(this, RESULT_STDOUT),
            stderr = result.stringResult(this, RESULT_STDERR),
            exitCode = result.exitCodeResult(this)
        )
    }

    private fun Bundle?.stringResult(intent: Intent, key: String): String {
        return this?.getString(key) ?: intent.getStringExtra(key).orEmpty()
    }

    private fun Bundle?.exitCodeResult(intent: Intent): Int? {
        if (this?.containsKey(RESULT_EXIT_CODE) == true) {
            return getInt(RESULT_EXIT_CODE)
        }
        return if (intent.hasExtra(RESULT_EXIT_CODE)) {
            intent.getIntExtra(RESULT_EXIT_CODE, 0)
        } else {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun Bundle?.errorCodeResult(intent: Intent): String? {
        val value = if (this?.containsKey(RESULT_ERROR_CODE) == true) {
            get(RESULT_ERROR_CODE)
        } else {
            intent.extras?.takeIf { it.containsKey(RESULT_ERROR_CODE) }?.get(RESULT_ERROR_CODE)
        }
        return when (value) {
            null -> null
            is Number -> value.toInt().toString()
            is String -> value.trim()
            else -> value.toString()
        }
    }

    private fun String?.isPositiveErrorCode(): Boolean {
        val normalized = this?.trim().orEmpty()
        val code = normalized.toIntOrNull() ?: return false
        return code > 0
    }

    companion object {
        private const val TAG = "TermuxRunCommand"
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"

        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val ACTION_RESULT_PREFIX = "ai.closepaw.termux.RUN_COMMAND_RESULT"

        private const val EXTRA_REQUEST_ID = "ai.closepaw.termux.requestId"
        private const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_RUN_COMMAND_SESSION_NAME = "com.termux.RUN_COMMAND_SESSION_NAME"
        private const val EXTRA_RUN_COMMAND_STDIN = "com.termux.RUN_COMMAND_STDIN"
        private const val EXTRA_RUN_COMMAND_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        private const val EXTRA_RESULT_BUNDLE = "result"
        private const val RESULT_STDOUT = "stdout"
        private const val RESULT_STDERR = "stderr"
        private const val RESULT_EXIT_CODE = "exitCode"
        private const val RESULT_ERROR_CODE = "err"
        private const val RESULT_ERROR_MESSAGE = "errmsg"
    }
}
