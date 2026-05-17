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
 * `memoryEditLocked` is `true` for [SessionState.Created], [SessionState.Running],
 * [SessionState.Idle], [SessionState.TakeoverPending], and [SessionState.Paused].
 * It is `false` only when no session exists or the session has [SessionState.Shutdown].
 *
 * Initial value is `true` (safe default while the upstream flow has not yet
 * emitted) — readers should never assume an unlocked state until the flow
 * has produced a value.
 */
class MemoryEditGate(
    sessionCoordinator: SessionCoordinator,
    scope: CoroutineScope,
) {
    val memoryEditLocked: StateFlow<Boolean> =
        sessionCoordinator.currentSessionState
            .map { state -> state != null && state != SessionState.Shutdown }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = true)
}
