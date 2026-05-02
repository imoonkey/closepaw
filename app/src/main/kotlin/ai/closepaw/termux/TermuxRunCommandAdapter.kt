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
                            continuation.resume(intent.toRunCommandResult())
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
                if (appContext.startService(runCommandIntent) == null) {
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
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
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
    }
}
