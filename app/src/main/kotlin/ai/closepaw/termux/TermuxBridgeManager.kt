package ai.closepaw.termux

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TermuxBridgeManager(private val context: Context) {
    companion object {
        fun get(context: Context): TermuxBridgeManager = TermuxBridgeManagerHolder.instance(context)
    }

    private val _state = MutableStateFlow<TermuxBridgeStatus>(TermuxBridgeStatus.NotInstalled)
    val state: StateFlow<TermuxBridgeStatus> = _state.asStateFlow()
    private val mutex = Mutex()

    suspend fun setup(): TermuxBridgeStatus {
        return mutex.withLock { TODO("phase3-bootstrap") }
    }

    suspend fun healthCheck(): TermuxBridgeStatus {
        return mutex.withLock { TODO("phase3-health") }
    }

    suspend fun restart(): TermuxBridgeStatus {
        return mutex.withLock { TODO("phase3-bootstrap") }
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

    private object TermuxBridgeManagerHolder {
        @Volatile private var INSTANCE: TermuxBridgeManager? = null

        fun instance(context: Context): TermuxBridgeManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TermuxBridgeManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
