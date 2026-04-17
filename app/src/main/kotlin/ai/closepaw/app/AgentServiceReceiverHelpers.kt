package ai.closepaw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import ai.closepaw.BuildConfig

internal fun registerDebugStopReceiverIfNeeded(service: AgentService, receiver: BroadcastReceiver) {
    if (!BuildConfig.DEBUG) return
    val filter = IntentFilter(AgentService.ACTION_STOP_AGENT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        service.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        service.registerReceiver(receiver, filter)
    }
}

internal fun unregisterDebugStopReceiverIfNeeded(service: AgentService, receiver: BroadcastReceiver) {
    if (!BuildConfig.DEBUG) return
    try {
        service.unregisterReceiver(receiver)
    } catch (e: IllegalArgumentException) {
        Log.w("AgentService", "stopReceiver was not registered: ${e.message}")
    }
}

internal fun registerDebugExecReceiverIfNeeded(service: AgentService, receiver: BroadcastReceiver) {
    if (!BuildConfig.DEBUG) return
    val filter = IntentFilter(ACTION_DEBUG_EXEC)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        service.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        service.registerReceiver(receiver, filter)
    }
}

internal fun unregisterDebugExecReceiverIfNeeded(service: AgentService, receiver: BroadcastReceiver) {
    if (!BuildConfig.DEBUG) return
    try {
        service.unregisterReceiver(receiver)
    } catch (e: IllegalArgumentException) {
        Log.w("AgentService", "debugExecReceiver was not registered: ${e.message}")
    }
}

internal const val ACTION_DEBUG_EXEC = "ai.closepaw.ACTION_DEBUG_EXEC"
