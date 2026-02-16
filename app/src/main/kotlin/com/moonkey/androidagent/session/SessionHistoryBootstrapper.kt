package com.moonkey.androidagent.session

import android.content.Context
import android.util.Log
import com.moonkey.androidagent.history.HistoryConfig
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.SessionRecordingService
import com.moonkey.androidagent.history.TruncationPolicy
import com.moonkey.androidagent.history.storage.SessionStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

internal data class SessionHistoryBootstrap(
        val historyManager: HistoryManager,
        val recordingService: SessionRecordingService
)

/** Creates history manager + session recorder for a session. */
internal object SessionHistoryBootstrapper {
    private const val TAG = "SessionHistoryBootstrap"

    fun create(context: Context, scope: CoroutineScope): SessionHistoryBootstrap {
        val historyConfig =
                HistoryConfig(
                        defaultTruncationPolicy = TruncationPolicy.AGGRESSIVE,
                        autoCompress = true,
                        maxTokenBudget = 18_000
                )
        val historyManager = HistoryManager(historyConfig)

        val storage = SessionStorage(context, Dispatchers.IO)
        val recordingService = SessionRecordingService(storage, scope)

        Log.d(TAG, "Created history stack with token budget=${historyConfig.maxTokenBudget}")

        return SessionHistoryBootstrap(
                historyManager = historyManager,
                recordingService = recordingService
        )
    }
}
