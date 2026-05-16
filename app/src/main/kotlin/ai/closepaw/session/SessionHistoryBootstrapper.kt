package ai.closepaw.session

import android.content.Context
import android.util.Log
import ai.closepaw.history.HistoryConfig
import ai.closepaw.history.HistoryManager
import ai.closepaw.history.SessionRecordingService
import ai.closepaw.history.TruncationPolicy
import ai.closepaw.history.storage.SessionStorage
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
                )
        val historyManager = HistoryManager(historyConfig)

        val storage = SessionStorage(context, Dispatchers.IO)
        val recordingService = SessionRecordingService(storage, scope)

        Log.d(TAG, "Created history stack (truncation=${historyConfig.defaultTruncationPolicy})")

        return SessionHistoryBootstrap(
                historyManager = historyManager,
                recordingService = recordingService
        )
    }
}
