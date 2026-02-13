package com.moonkey.androidagent.ui.overlay

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.protocol.AskUserType
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import org.junit.Before
import org.junit.Test

class CapsuleStateHolderTest {

    private lateinit var holder: CapsuleStateHolder

    @Before
    fun setUp() {
        holder = CapsuleStateHolder()
    }

    // ── Initial state ──

    @Test
    fun `initial mode is Hidden`() {
        assertThat(holder.mode.value).isEqualTo(CapsuleMode.Hidden)
    }

    @Test
    fun `initial previousMode is Hidden`() {
        assertThat(holder.previousMode).isEqualTo(CapsuleMode.Hidden)
    }

    // ── Task started ──

    @Test
    fun `onTaskStarted sets Running mode with sanitized input`() {
        holder.onTaskStarted("task1", "打开淘宝搜索")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Running::class.java)
        assertThat((mode as CapsuleMode.Running).thought).isEqualTo("打开淘宝搜索")
    }

    @Test
    fun `onTaskStarted truncates long input`() {
        val longInput = "a".repeat(50)
        holder.onTaskStarted("task1", longInput)
        val thought = (holder.mode.value as CapsuleMode.Running).thought
        assertThat(thought).hasLength(43) // 40 + "..."
        assertThat(thought).endsWith("...")
    }

    // ── Thought update ──

    @Test
    fun `onThoughtUpdate changes to Running with new thought`() {
        holder.onTaskStarted("task1", "input")
        holder.onThoughtUpdate("正在搜索...")
        assertThat((holder.mode.value as CapsuleMode.Running).thought).isEqualTo("正在搜索...")
    }

    // ── Takeover flow ──

    @Test
    fun `onTakeoverRequested transitions from Running to TakeoverPending`() {
        holder.onTaskStarted("task1", "input")
        holder.onThoughtUpdate("当前想法")
        holder.onTakeoverRequested()
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.TakeoverPending::class.java)
        assertThat((mode as CapsuleMode.TakeoverPending).lastThought).isEqualTo("当前想法")
    }

    @Test
    fun `onTakeoverConfirmed transitions from TakeoverPending to Takeover`() {
        holder.onTaskStarted("task1", "input")
        holder.onThoughtUpdate("当前想法")
        holder.onTakeoverRequested()
        holder.onTakeoverConfirmed()
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Takeover::class.java)
        assertThat((mode as CapsuleMode.Takeover).lastThought).isEqualTo("当前想法")
    }

    @Test
    fun `onResumed transitions back to Running`() {
        holder.onTaskStarted("task1", "input")
        holder.onTakeoverRequested()
        holder.onTakeoverConfirmed()
        holder.onResumed()
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Running::class.java)
        assertThat((mode as CapsuleMode.Running).thought).isEqualTo("思考中...")
    }

    @Test
    fun `onResumed resets isAgentMidTurn`() {
        holder.setAgentMidTurn(true)
        assertThat(holder.isAgentMidTurn.value).isTrue()
        holder.onResumed()
        assertThat(holder.isAgentMidTurn.value).isFalse()
    }

    // ── Ask user ──

    @Test
    fun `onAskUser QUESTION sets WaitingForInput`() {
        holder.onAskUser(AskUserType.QUESTION, "选哪个?", "call1")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.WaitingForInput::class.java)
        assertThat((mode as CapsuleMode.WaitingForInput).question).isEqualTo("选哪个?")
        assertThat(mode.callId).isEqualTo("call1")
    }

    @Test
    fun `onAskUser ACTION sets WaitingForAction`() {
        holder.onAskUser(AskUserType.ACTION, "请打开设置", "call2")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.WaitingForAction::class.java)
        assertThat((mode as CapsuleMode.WaitingForAction).instruction).isEqualTo("请打开设置")
        assertThat(mode.callId).isEqualTo("call2")
    }

    @Test
    fun `onUserResponseSent transitions to Running`() {
        holder.onAskUser(AskUserType.QUESTION, "选哪个?", "call1")
        holder.onUserResponseSent("call1")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Running::class.java)
        assertThat((mode as CapsuleMode.Running).thought).isEqualTo("处理答复中...")
    }

    // ── Task completion ──

    @Test
    fun `onTaskCompleted GOAL_ACHIEVED sets Done`() {
        holder.onTaskCompleted(CompletionReason.GOAL_ACHIEVED)
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Done::class.java)
        assertThat((mode as CapsuleMode.Done).message).isEqualTo("已完成")
    }

    @Test
    fun `onTaskCompleted ERROR sets Error`() {
        holder.onTaskCompleted(CompletionReason.ERROR)
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Error::class.java)
    }

    @Test
    fun `onDoneAutoHide transitions to Hidden`() {
        holder.onTaskCompleted(CompletionReason.GOAL_ACHIEVED)
        holder.onDoneAutoHide()
        assertThat(holder.mode.value).isEqualTo(CapsuleMode.Hidden)
    }

    // ── Error ──

    @Test
    fun `onError sets Error mode`() {
        holder.onError("网络错误")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Error::class.java)
        assertThat((mode as CapsuleMode.Error).message).isEqualTo("网络错误")
    }

    @Test
    fun `onDismissError transitions to Hidden`() {
        holder.onError("网络错误")
        holder.onDismissError()
        assertThat(holder.mode.value).isEqualTo(CapsuleMode.Hidden)
    }

    // ── Previous mode tracking ──

    @Test
    fun `previousMode tracks the mode before the current one`() {
        holder.onTaskStarted("task1", "input")
        assertThat(holder.previousMode).isEqualTo(CapsuleMode.Hidden)

        holder.onThoughtUpdate("new thought")
        assertThat(holder.previousMode).isInstanceOf(CapsuleMode.Running::class.java)
    }

    @Test
    fun `previousMode updates through full lifecycle`() {
        holder.onTaskStarted("task1", "input")
        val afterStart = holder.mode.value

        holder.onTakeoverRequested()
        assertThat(holder.previousMode).isEqualTo(afterStart)

        val afterTakeoverReq = holder.mode.value
        holder.onTakeoverConfirmed()
        assertThat(holder.previousMode).isEqualTo(afterTakeoverReq)
    }
}
