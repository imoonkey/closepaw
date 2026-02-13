package com.moonkey.androidagent.ui.overlay

import com.moonkey.androidagent.protocol.AskUserType
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import com.moonkey.androidagent.ui.overlay.model.sanitizeThought
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CapsuleStateHolder — single source of truth for Smart Capsule state.
 *
 * All UI renderers (SmartCapsuleManager, SmartCapsuleCompose, StatusIslandManager)
 * read from this holder. State transitions happen here and only here.
 * Renderers are pure — they display what this holder tells them.
 *
 * Exposed as StateFlow for Compose consumers and direct reads for View consumers.
 */
class CapsuleStateHolder {

    private val _mode = MutableStateFlow<CapsuleMode>(CapsuleMode.Hidden)
    val mode: StateFlow<CapsuleMode> = _mode.asStateFlow()

    private val _context = MutableStateFlow(CapsuleContext.MAIN_APP)
    val context: StateFlow<CapsuleContext> = _context.asStateFlow()

    /** The mode before the current one, for transition animations. */
    var previousMode: CapsuleMode = CapsuleMode.Hidden
        private set

    private val _platformMode = MutableStateFlow(PlatformMode.ACCESSIBILITY)
    val platformMode: StateFlow<PlatformMode> = _platformMode.asStateFlow()

    /** True when agent is mid-turn (execution/planning). Used for supplement confirmation text. */
    private val _isAgentMidTurn = MutableStateFlow(false)
    val isAgentMidTurn: StateFlow<Boolean> = _isAgentMidTurn.asStateFlow()

    fun setPlatformMode(mode: PlatformMode) {
        _platformMode.value = mode
    }

    fun setAgentMidTurn(midTurn: Boolean) {
        _isAgentMidTurn.value = midTurn
    }

    // ── State transitions ──

    fun onTaskStarted(taskId: String, input: String) {
        setMode(CapsuleMode.Running(sanitizeThought(input)))
    }

    fun onThoughtUpdate(thought: String) {
        setMode(CapsuleMode.Running(thought))
    }

    fun onTakeoverRequested() {
        val lastThought = (_mode.value as? CapsuleMode.Running)?.thought ?: ""
        setMode(CapsuleMode.TakeoverPending(lastThought))
    }

    fun onTakeoverConfirmed() {
        val lastThought = when (val m = _mode.value) {
            is CapsuleMode.TakeoverPending -> m.lastThought
            is CapsuleMode.Running -> m.thought
            else -> ""
        }
        setMode(CapsuleMode.Takeover(lastThought))
    }

    fun onResumed() {
        _isAgentMidTurn.value = false
        setMode(CapsuleMode.Running("思考中..."))
    }

    fun onAskUser(type: AskUserType, message: String, callId: String) {
        setMode(
            when (type) {
                AskUserType.QUESTION -> CapsuleMode.WaitingForInput(question = message, callId = callId)
                AskUserType.ACTION -> CapsuleMode.WaitingForAction(instruction = message, callId = callId)
            }
        )
    }

    fun onUserResponseSent(callId: String) {
        setMode(CapsuleMode.Running("处理答复中..."))
    }

    fun onTaskCompleted(reason: CompletionReason) {
        setMode(
            when (reason) {
                CompletionReason.GOAL_ACHIEVED -> CapsuleMode.Done("已完成")
                CompletionReason.MAX_TURNS -> CapsuleMode.Done("已达到最大步数")
                CompletionReason.TASK_IMPOSSIBLE -> CapsuleMode.Done("无法完成任务")
                CompletionReason.USER_STOPPED -> CapsuleMode.Done("已停止")
                CompletionReason.ERROR -> CapsuleMode.Error("发生错误")
                CompletionReason.INTERRUPTED -> CapsuleMode.Done("已中断")
            }
        )
    }

    fun onDoneAutoHide() {
        setMode(CapsuleMode.Hidden)
    }

    fun onError(message: String) {
        setMode(CapsuleMode.Error(sanitizeThought(message)))
    }

    fun onDismissError() {
        setMode(CapsuleMode.Hidden)
    }

    // ── Context tracking (wired in Stage 7+: VD viewer, status island) ──

    fun setContext(ctx: CapsuleContext) {
        _context.value = ctx
    }

    // ── Internal ──

    private fun setMode(new: CapsuleMode) {
        previousMode = _mode.value
        _mode.value = new
    }
}
