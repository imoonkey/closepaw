package ai.closepaw.session

import android.util.Log
import ai.closepaw.history.model.SessionInfo
import ai.closepaw.protocol.Op
import ai.closepaw.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Coordinates session lifecycle and input queuing.
 *
 * Replaces timer-loop drain in MainActivity with event-driven approach:
 * - Inputs during busy state (Running/Paused/TakeoverPending) are queued
 * - Queue drains automatically when session transitions to Idle/Created
 * - Session creation is serialized via internal [Mutex]
 *
 * Threading: All public methods must be called from the main thread (Dispatchers.Main).
 * The mutex serializes concurrent coroutine access but all field reads/writes
 * rely on main-thread confinement for safety.
 */
class SessionCoordinator(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "SessionCoordinator"
        private const val SHUTDOWN_GRACE_DELAY_MS = 100L
    }

    private val mutex = Mutex()
    private val pendingInputs = mutableListOf<String>()
    private var stateObserverJob: Job? = null

    var currentSession: AgentSession? = null
        private set

    private val _currentSessionState = MutableStateFlow<SessionState?>(null)

    /**
     * State of the currently-owned session, or `null` when no session exists.
     *
     * Emits [SessionState.Created] eagerly at the start of [createAndSubmit]
     * — before the suspending `create` block runs — so the "creation in
     * progress" window is observable as a locked state. Callers that gate
     * memory edits (see `MemoryEditGate`) rely on this invariant.
     */
    val currentSessionState: StateFlow<SessionState?> = _currentSessionState.asStateFlow()

    var selectedSessionForReload: SessionInfo? = null

    /** File name of the last session that died (Shutdown). Used for auto-reload. */
    private var lastDeadSessionFileName: String? = null

    /**
     * Submit user input to the current session.
     *
     * - Idle/Created: sends immediately
     * - Running/Paused/TakeoverPending: queues for automatic event-driven drain
     * - No session or Shutdown: returns appropriate result for caller to handle
     */
    suspend fun submit(text: String): SubmitResult {
        mutex.lock()
        try {
            val session = currentSession
                ?: return SubmitResult.NO_SESSION
            return when (session.state.value) {
                SessionState.Shutdown -> {
                    lastDeadSessionFileName =
                        session.getServices().recordingService.getCurrentFileName()
                    teardownLocked()
                    SubmitResult.SESSION_DEAD
                }
                SessionState.Running, SessionState.Paused, SessionState.TakeoverPending -> {
                    pendingInputs.add(text)
                    SubmitResult.QUEUED
                }
                else -> {
                    session.submit(Op.UserInput(text))
                    SubmitResult.SENT
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Create a session and submit the first input, all under the creation lock.
     *
     * Returns:
     * - [CreateResult.Success]: session created and first input submitted.
     * - [CreateResult.LockBusy]: lock already held (another creation in progress);
     *   caller should fall back to [enqueue] so input is delivered once the
     *   in-progress creation completes.
     * - [CreateResult.Aborted]: [create] returned null (explicit abort, e.g. a
     *   non-reloadable checkpoint). Pending inputs are cleared so the aborted
     *   text does not auto-run in the next fresh session.
     *
     * Concurrency note: [observeSessionState] launches a collector that calls
     * [drainPending] on state transitions. Since the collector runs in a separate
     * coroutine, it will suspend on the mutex until this method's finally block
     * releases it. The initial StateFlow emission is handled after release, and
     * any redundant drain is a safe no-op (empty queue check).
     *
     * @param text First user input to send after creation.
     * @param create Factory that creates and configures the session. Return null to abort.
     */
    suspend fun createAndSubmit(
        text: String,
        create: suspend () -> AgentSession?
    ): CreateResult {
        if (!mutex.tryLock()) return CreateResult.LockBusy
        try {
            // Lock the memory-edit gate before the suspending create block runs.
            // Without this, an `append` racing with creation could fire while
            // currentSessionState is still null.
            _currentSessionState.value = SessionState.Created
            val session = create()
            if (session == null) {
                _currentSessionState.value = null
                pendingInputs.clear()
                return CreateResult.Aborted
            }
            currentSession = session
            observeSessionState(session)
            session.submit(Op.UserInput(text))
            drainLocked(session)
            return CreateResult.Success
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Directly queue input for delivery when the next session becomes available.
     *
     * Use when [createAndSubmit] returns [CreateResult.LockBusy] (another
     * creation is in-progress). The input will be drained when the session
     * transitions to Idle/Created.
     *
     * Must be called from the main thread.
     */
    fun enqueue(text: String) {
        pendingInputs.add(text)
    }

    /**
     * Attach an externally-managed session (e.g., rebound from service).
     * Starts state observation for event-driven drain.
     *
     * Must be called from the main thread. Not guarded by the mutex —
     * callers must ensure no concurrent [submit]/[clearSession] calls.
     */
    fun attachSession(session: AgentSession) {
        currentSession = session
        observeSessionState(session)
    }

    /**
     * Detach the current session without shutting it down.
     * Used when switching to history viewing mode.
     *
     * Must be called from the main thread. Not guarded by the mutex —
     * callers must ensure no concurrent [submit]/[clearSession] calls.
     */
    fun detachSession() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        currentSession = null
        pendingInputs.clear()
        lastDeadSessionFileName = null
        _currentSessionState.value = null
    }

    /**
     * Consume the file name of the last session that died (Shutdown).
     * Returns the file name and clears it so it's only used once.
     * Used by callers to set up auto-reload from checkpoint.
     */
    fun consumeDeadSessionFileName(): String? {
        val f = lastDeadSessionFileName
        lastDeadSessionFileName = null
        return f
    }

    /**
     * Shutdown and clear the current session.
     */
    suspend fun clearSession() {
        mutex.lock()
        try {
            val session = currentSession ?: return
            try {
                session.submit(Op.Shutdown)
                delay(SHUTDOWN_GRACE_DELAY_MS)
                Log.d(TAG, "Session shutdown completed")
            } catch (e: Exception) {
                Log.w(TAG, "Error shutting down session: ${e.message}")
            }
            teardownLocked()
            lastDeadSessionFileName = null
        } finally {
            mutex.unlock()
        }
    }

    private fun teardownLocked() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        currentSession = null
        pendingInputs.clear()
        _currentSessionState.value = null
    }

    private fun observeSessionState(session: AgentSession) {
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            session.state.collect { state ->
                _currentSessionState.value = state
                if (state == SessionState.Idle || state == SessionState.Created) {
                    drainPending()
                }
            }
        }
    }

    private suspend fun drainPending() {
        mutex.lock()
        try {
            val session = currentSession ?: return
            drainLocked(session)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun drainLocked(session: AgentSession) {
        while (pendingInputs.isNotEmpty()) {
            val state = session.state.value
            if (state != SessionState.Idle && state != SessionState.Created) break
            session.submit(Op.UserInput(pendingInputs.removeAt(0)))
        }
    }
}

/** Result of [SessionCoordinator.submit]. */
enum class SubmitResult {
    /** Input was sent to the session immediately. */
    SENT,
    /** Session is busy; input queued for automatic drain. */
    QUEUED,
    /** No session exists; caller should create one. */
    NO_SESSION,
    /** Session was dead (shutdown); caller should create a new one. */
    SESSION_DEAD
}

/** Result of [SessionCoordinator.createAndSubmit]. */
sealed class CreateResult {
    /** Session created; first input submitted. */
    data object Success : CreateResult()
    /** Creation lock held by another caller; caller should [SessionCoordinator.enqueue]. */
    data object LockBusy : CreateResult()
    /** Creation factory returned null (explicit abort); pending inputs cleared. */
    data object Aborted : CreateResult()
}
