package ai.closepaw.app

import ai.closepaw.protocol.SessionState
import ai.closepaw.session.SessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Single-writer guard for memory file edits from Settings.
 *
 * Memory files (`user.md`, `device.md`, `apps/<pkg>.md`) are written by both
 * the running agent session (`append` tool) and the Settings UI (free-text
 * editor). We avoid concurrent writers by locking the editor whenever any
 * non-Shutdown session exists — or while a session is being created.
 *
 * Two views of the same predicate:
 *  - [memoryEditLocked] is a derived [StateFlow] suitable for Compose
 *    `collectAsStateWithLifecycle` — it lags the upstream by one map-collector
 *    tick, but recomposes cleanly. Use it for UI observation.
 *  - [isLockedNow] is a synchronous snapshot that reads the underlying
 *    `currentSessionState` directly, with no map-collector delay. Use it for
 *    action-time TOCTOU re-checks inside IO blocks before a write/delete.
 *
 * The two paths can disagree for a brief window after `currentSessionState`
 * transitions: [isLockedNow] reflects the change immediately, while
 * [memoryEditLocked] catches up on the next collector tick. That's intentional
 * — UI stability prefers the derived flow, write-safety prefers the snapshot.
 *
 * Initial [memoryEditLocked] value is `true` (safe default while the upstream
 * flow has not yet emitted).
 */
class MemoryEditGate(
    private val sessionCoordinator: SessionCoordinator,
    scope: CoroutineScope,
) {
    val memoryEditLocked: StateFlow<Boolean> =
        sessionCoordinator.currentSessionState
            .map { state -> state != null && state != SessionState.Shutdown }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = true)

    /**
     * Synchronous, race-free locked check. Reads the underlying
     * [SessionCoordinator.currentSessionState] directly so callers performing
     * action-time TOCTOU checks (e.g. inside `withContext(IO)` immediately
     * before `MemoryStore.write`) cannot observe a stale unlocked value
     * during the map-collector window.
     */
    fun isLockedNow(): Boolean {
        val state = sessionCoordinator.currentSessionState.value
        return state != null && state != SessionState.Shutdown
    }
}
