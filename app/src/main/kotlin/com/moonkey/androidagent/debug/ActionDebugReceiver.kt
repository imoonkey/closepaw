package com.moonkey.androidagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moonkey.androidagent.app.ACTION_DEBUG_EXEC
import com.moonkey.androidagent.app.AgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug-only BroadcastReceiver for direct action execution testing.
 *
 * Receives an intent with action parameters, executes the action via
 * [DebugActionExecutor], and writes results to device storage.
 * Registered dynamically in [AgentService.onServiceConnected], gated by BuildConfig.DEBUG.
 */
class ActionDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEBUG_EXEC) return

        val service = AgentService.instance
        if (service == null) {
            Log.e(TAG, "Accessibility service not running")
            DebugActionExecutor.writeErrorResult(context, "Accessibility service not running")
            return
        }

        if (service.getActiveSession() != null) {
            Log.e(TAG, "Agent session active — rejecting debug exec")
            DebugActionExecutor.writeErrorResult(context, "Agent session active — stop agent first")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    DebugActionExecutor(service).execute(intent, context)
                } ?: run {
                    Log.e(TAG, "Debug action timed out after ${TIMEOUT_MS}ms")
                    DebugActionExecutor.writeErrorResult(context, "Timed out after ${TIMEOUT_MS}ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Debug action failed", e)
                DebugActionExecutor.writeErrorResult(context, "Exception: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ActionDebugReceiver"
        // goAsync() grants ~30s before the system kills the broadcast.
        // Our timeout must be well under that.
        private const val TIMEOUT_MS = 10_000L
    }
}
