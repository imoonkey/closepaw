package com.moonkey.androidagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.moonkey.androidagent.BuildConfig

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
