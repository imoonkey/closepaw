package ai.closepaw.ui.overlay

import android.util.Log
import ai.closepaw.protocol.AskUserType
import ai.closepaw.protocol.SessionEndReason
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.TurnPhase
import ai.closepaw.protocol.compactThought
import ai.closepaw.ui.overlay.model.CapsuleContext
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.ui.overlay.model.GlowState
import ai.closepaw.ui.overlay.model.deriveGlowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * CapsuleStateHolder — single source of truth for Smart Capsule state.
 *
 * All UI renderers (CapsuleOverlayHost, SmartCapsuleCompose, IslandOverlayHost)
 * read from this holder. State transitions happen here and only here.
 *
 * ## State Machine
 * See system_design_claude.md Section 3A for the exhaustive state × event matrix.
 * Every transition is guarded — invalid events are silently ignored with a log message.
 *
 * ## Threading
 * All mutations must happen on the Main dispatcher (enforced by StateFlow usage from
 * coroutine scope or direct main-thread calls from ServiceOverlayController).
 */
class CapsuleStateHolder(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "CapsuleStateHolder"
        private const val AUTO_HIDE_DELAY_MS = 3000L
    }

    // ── Core state ──

    private val _mode = MutableStateFlow<CapsuleMode>(CapsuleMode.Hidden)
    val mode: StateFlow<CapsuleMode> = _mode.asStateFlow()

    private val _context = MutableStateFlow(CapsuleContext.MAIN_APP)
    val context: StateFlow<CapsuleContext> = _context.asStateFlow()

    private val _platformMode = MutableStateFlow(PlatformMode.ACCESSIBILITY)
    val platformMode: StateFlow<PlatformMode> = _platformMode.asStateFlow()

    private val _hasIsland = MutableStateFlow(true)
    val hasIsland: StateFlow<Boolean> = _hasIsland.asStateFlow()

    private val _turnPhase = MutableStateFlow<TurnPhase?>(null)
    val turnPhase: StateFlow<TurnPhase?> = _turnPhase.asStateFlow()

    /** True when agent is mid-turn (execution/planning). Used for supplement confirmation. */
    private val _isAgentMidTurn = MutableStateFlow(false)
    val isAgentMidTurn: StateFlow<Boolean> = _isAgentMidTurn.asStateFlow()

    /**
     * Transient stop feedback flag.
     *
     * Not part of CapsuleMode state machine. Drives immediate "Stopping..." disabled UI
     * until a terminal/new-task event clears it.
     */
    private val _isStopPending = MutableStateFlow(false)
    val isStopPending: StateFlow<Boolean> = _isStopPending.asStateFlow()

    /** The mode before the current one, for transition animations. */
    var previousMode: CapsuleMode = CapsuleMode.Hidden
        private set

    /** Derived glow state — no parallel state machine needed. */
    val derivedGlowState: GlowState get() = deriveGlowState(_mode.value, _turnPhase.value)

    /** Whether there's an active task (derived from mode). */
    val hasActiveTask: Boolean
        get() =
            when (_mode.value) {
                is CapsuleMode.Running,
                is CapsuleMode.TakeoverPending,
                is CapsuleMode.Takeover,
                is CapsuleMode.WaitingForInput,
                is CapsuleMode.WaitingForAction,
                is CapsuleMode.WaitingForApproval -> true
                is CapsuleMode.Done,
                is CapsuleMode.Error,
                is CapsuleMode.Hidden -> false
            }

    private var autoHideJob: Job? = null

    // ── Configuration ──

    fun setPlatformMode(mode: PlatformMode) { _platformMode.value = mode }

    fun setTurnPhase(phase: TurnPhase) { _turnPhase.value = phase }

    fun setAgentMidTurn(midTurn: Boolean) { _isAgentMidTurn.value = midTurn }

    fun setContext(ctx: CapsuleContext) { _context.value = ctx }

    fun setHasIsland(enabled: Boolean) { _hasIsland.value = enabled }

    // ── Universal events (any state → target) ──

    fun onTaskStarted(taskId: String, input: String) {
        cancelAutoHide()
        _isStopPending.value = false
        _turnPhase.value = null
        setMode(CapsuleMode.Running(compactThought(input)))
    }

    fun onError(message: String) {
        cancelAutoHide()
        _isStopPending.value = false
        setMode(CapsuleMode.Error(compactThought(message)))
    }

    fun onAskUser(type: AskUserType, message: String, callId: String) {
        setMode(
            when (type) {
                AskUserType.QUESTION -> CapsuleMode.WaitingForInput(
                    question = message, callId = callId,
                )
                AskUserType.ACTION -> CapsuleMode.WaitingForAction(
                    instruction = message, callId = callId,
                )
            }
        )
    }

    fun onApprovalRequired(
        callId: String,
        description: String,
        appLabel: String,
        packageName: String,
        reason: String,
    ) {
        setMode(CapsuleMode.WaitingForApproval(callId, description, appLabel, packageName, reason))
    }

    fun onApprovalResolved(callId: String): Boolean {
        val current = _mode.value as? CapsuleMode.WaitingForApproval ?: run {
            Log.d(TAG, "Ignoring approval resolved in ${_mode.value::class.simpleName}")
            return false
        }
        if (current.callId != callId) {
            Log.d(TAG, "Ignoring approval resolved: callId mismatch expected=${current.callId}, actual=$callId")
            return false
        }
        setMode(CapsuleMode.Running("Processing..."))
        return true
    }

    // ── Guarded events (specific states only) ──

    fun onThoughtUpdate(thought: String) {
        if (_mode.value !is CapsuleMode.Running) return
        setMode(CapsuleMode.Running(thought))
    }

    fun onTakeoverRequested() {
        val current = _mode.value as? CapsuleMode.Running ?: run {
            Log.d(TAG, "Ignoring takeover request in ${_mode.value::class.simpleName}")
            return
        }
        setMode(CapsuleMode.TakeoverPending(current.thought))
    }

    fun onTakeoverConfirmed() {
        val thought = when (val m = _mode.value) {
            is CapsuleMode.TakeoverPending -> m.lastThought
            is CapsuleMode.Running -> m.thought
            else -> {
                Log.d(TAG, "Ignoring takeover confirmed in ${_mode.value::class.simpleName}")
                return
            }
        }
        setMode(CapsuleMode.Takeover(thought))
    }

    fun onResumed() {
        val current = _mode.value
        if (current !is CapsuleMode.Takeover && current !is CapsuleMode.TakeoverPending) {
            Log.d(TAG, "Ignoring resume in ${current::class.simpleName}")
            return
        }
        _turnPhase.value = null
        _isAgentMidTurn.value = false
        setMode(CapsuleMode.Running("Thinking..."))
    }

    fun onUserResponseSent(callId: String): Boolean {
        val current = _mode.value
        if (current !is CapsuleMode.WaitingForInput && current !is CapsuleMode.WaitingForAction) {
            Log.d(TAG, "Ignoring user response in ${current::class.simpleName}")
            return false
        }
        val expectedCallId = when (current) {
            is CapsuleMode.WaitingForInput -> current.callId
            is CapsuleMode.WaitingForAction -> current.callId
        }
        if (expectedCallId != callId) {
            Log.d(TAG, "Ignoring user response due to callId mismatch: expected=$expectedCallId, actual=$callId")
            return false
        }
        setMode(CapsuleMode.Running("Processing response..."))
        return true
    }

    /**
     * Mark stop as pending for immediate UI feedback.
     * Valid only when current mode has a Stop action.
     */
    fun onStopRequested(): Boolean {
        val mode = _mode.value
        if (mode !is CapsuleMode.Running &&
            mode !is CapsuleMode.TakeoverPending &&
            mode !is CapsuleMode.Takeover &&
            mode !is CapsuleMode.WaitingForInput &&
            mode !is CapsuleMode.WaitingForAction &&
            mode !is CapsuleMode.WaitingForApproval
        ) {
            return false
        }
        if (_isStopPending.value) return false
        _isStopPending.value = true
        return true
    }

    fun onTaskCompleted(outcome: TaskOutcome, message: String? = null) {
        val current = _mode.value
        if (current is CapsuleMode.Hidden || current is CapsuleMode.Done || current is CapsuleMode.Error) {
            Log.d(TAG, "Ignoring task completed in ${current::class.simpleName}")
            return
        }
        _isStopPending.value = false
        val mode = when (outcome) {
            TaskOutcome.GOAL_ACHIEVED -> {
                val completionMessage = message?.takeIf { it.isNotBlank() } ?: "Task completed"
                CapsuleMode.Done(completionMessage)
            }
            TaskOutcome.TASK_IMPOSSIBLE -> CapsuleMode.Done("Task impossible")
            TaskOutcome.USER_STOPPED -> CapsuleMode.Done("Stopped")
            TaskOutcome.ERROR -> CapsuleMode.Error(
                message?.takeIf { it.isNotBlank() }?.let(::compactThought) ?: "Error occurred"
            )
        }
        setMode(mode)
        if (mode is CapsuleMode.Done) scheduleAutoHide()
    }

    fun onSessionEnded(reason: SessionEndReason) {
        cancelAutoHide()
        _isStopPending.value = false
        when (reason) {
            SessionEndReason.USER_STOPPED,
            SessionEndReason.INTERRUPTED,
            SessionEndReason.IDLE_TIMEOUT -> {
                setMode(CapsuleMode.Hidden)
            }
        }
    }

    fun onDismissError() {
        if (_mode.value !is CapsuleMode.Error) return
        _isStopPending.value = false
        setMode(CapsuleMode.Hidden)
    }

    // ── Internal ──

    private fun setMode(new: CapsuleMode) {
        previousMode = _mode.value
        _mode.value = new
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        autoHideJob = scope.launch {
            delay(AUTO_HIDE_DELAY_MS)
            if (_mode.value is CapsuleMode.Done) {
                setMode(CapsuleMode.Hidden)
            }
        }
    }

    private fun cancelAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = null
    }
}
