package ai.closepaw.ui.overlay

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.AskUserType
import ai.closepaw.protocol.SessionEndReason
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.ui.overlay.model.CapsuleMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CapsuleStateHolderTest {

    private lateinit var scope: TestScope
    private lateinit var holder: CapsuleStateHolder

    @Before
    fun setUp() {
        scope = TestScope()
        holder = CapsuleStateHolder(scope)
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
        holder.onTaskStarted("task1", "Open Taobao and search")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Running::class.java)
        assertThat((mode as CapsuleMode.Running).thought).isEqualTo("Open Taobao and search")
    }

    @Test
    fun `onTaskStarted truncates long input`() {
        val longInput = "a".repeat(50)
        holder.onTaskStarted("task1", longInput)
        val thought = (holder.mode.value as CapsuleMode.Running).thought
        assertThat(thought).hasLength(43) // 40 + "..."
        assertThat(thought).endsWith("...")
    }

    @Test
    fun `onTaskStarted resets from any state`() {
        holder.onError("some error")
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Error::class.java)
        holder.onTaskStarted("task2", "new task")
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Running::class.java)
    }

    // ── Thought update ──

    @Test
    fun `onThoughtUpdate changes thought in Running mode`() {
        holder.onTaskStarted("task1", "input")
        holder.onThoughtUpdate("Searching...")
        assertThat((holder.mode.value as CapsuleMode.Running).thought).isEqualTo("Searching...")
    }

    @Test
    fun `onThoughtUpdate ignored when not in Running mode`() {
        holder.onTaskStarted("task1", "input")
        holder.onTakeoverRequested()
        val modeBefore = holder.mode.value
        holder.onThoughtUpdate("should be ignored")
        assertThat(holder.mode.value).isEqualTo(modeBefore)
    }

    // ── Takeover flow ──

    @Test
    fun `onTakeoverRequested transitions from Running to TakeoverPending`() {
        holder.onTaskStarted("task1", "input")
        holder.onThoughtUpdate("current thought")
        holder.onTakeoverRequested()
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.TakeoverPending::class.java)
        assertThat((mode as CapsuleMode.TakeoverPending).lastThought).isEqualTo("current thought")
    }

    @Test
    fun `onTakeoverRequested ignored when not Running`() {
        // Hidden state
        holder.onTakeoverRequested()
        assertThat(holder.mode.value).isEqualTo(CapsuleMode.Hidden)
    }

    @Test
    fun `onTakeoverConfirmed transitions from TakeoverPending to Takeover`() {
        holder.onTaskStarted("task1", "input")
        holder.onThoughtUpdate("current thought")
        holder.onTakeoverRequested()
        holder.onTakeoverConfirmed()
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Takeover::class.java)
        assertThat((mode as CapsuleMode.Takeover).lastThought).isEqualTo("current thought")
    }

    @Test
    fun `onResumed transitions from Takeover to Running`() {
        holder.onTaskStarted("task1", "input")
        holder.onTakeoverRequested()
        holder.onTakeoverConfirmed()
        holder.onResumed()
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Running::class.java)
        assertThat((mode as CapsuleMode.Running).thought).isEqualTo("Thinking...")
    }

    @Test
    fun `onResumed ignored when not in takeover state`() {
        holder.onTaskStarted("task1", "input")
        val modeBefore = holder.mode.value
        holder.onResumed()
        assertThat(holder.mode.value).isEqualTo(modeBefore)
    }

    @Test
    fun `onResumed resets isAgentMidTurn`() {
        holder.onTaskStarted("task1", "input")
        holder.setAgentMidTurn(true)
        assertThat(holder.isAgentMidTurn.value).isTrue()
        holder.onTakeoverRequested()
        holder.onTakeoverConfirmed()
        holder.onResumed()
        assertThat(holder.isAgentMidTurn.value).isFalse()
    }

    // ── Ask user ──

    @Test
    fun `onAskUser QUESTION sets WaitingForInput`() {
        holder.onAskUser(AskUserType.QUESTION, "Which one?", "call1")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.WaitingForInput::class.java)
        assertThat((mode as CapsuleMode.WaitingForInput).question).isEqualTo("Which one?")
        assertThat(mode.callId).isEqualTo("call1")
    }

    @Test
    fun `onAskUser ACTION sets WaitingForAction`() {
        holder.onAskUser(AskUserType.ACTION, "Open Settings", "call2")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.WaitingForAction::class.java)
        assertThat((mode as CapsuleMode.WaitingForAction).instruction).isEqualTo("Open Settings")
        assertThat(mode.callId).isEqualTo("call2")
    }

    @Test
    fun `onUserResponseSent transitions to Running from WaitingForInput`() {
        holder.onAskUser(AskUserType.QUESTION, "Which one?", "call1")
        holder.onUserResponseSent("call1")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Running::class.java)
        assertThat((mode as CapsuleMode.Running).thought).isEqualTo("Processing response...")
    }

    @Test
    fun `onUserResponseSent ignored when callId mismatches waiting call`() {
        holder.onAskUser(AskUserType.QUESTION, "Which one?", "call1")
        val modeBefore = holder.mode.value
        holder.onUserResponseSent("wrong-call")
        assertThat(holder.mode.value).isEqualTo(modeBefore)
    }

    @Test
    fun `onUserResponseSent ignored when no pending ask`() {
        holder.onTaskStarted("task1", "input")
        val modeBefore = holder.mode.value
        holder.onUserResponseSent("call1")
        assertThat(holder.mode.value).isEqualTo(modeBefore)
    }

    // ── Stop transient feedback ──

    @Test
    fun `onStopRequested marks stop pending in running mode`() {
        holder.onTaskStarted("task1", "input")
        val accepted = holder.onStopRequested()
        assertThat(accepted).isTrue()
        assertThat(holder.isStopPending.value).isTrue()
    }

    @Test
    fun `onStopRequested ignored when stop already pending`() {
        holder.onTaskStarted("task1", "input")
        assertThat(holder.onStopRequested()).isTrue()
        assertThat(holder.onStopRequested()).isFalse()
        assertThat(holder.isStopPending.value).isTrue()
    }

    @Test
    fun `stop pending is cleared on terminal events`() {
        holder.onTaskStarted("task1", "input")
        holder.onStopRequested()
        holder.onTaskCompleted(TaskOutcome.USER_STOPPED)
        assertThat(holder.isStopPending.value).isFalse()
    }

    @Test
    fun `stop pending is cleared on new task start`() {
        holder.onTaskStarted("task1", "input")
        holder.onStopRequested()
        holder.onTaskStarted("task2", "next")
        assertThat(holder.isStopPending.value).isFalse()
    }

    // ── Task completion ──

    @Test
    fun `onTaskCompleted GOAL_ACHIEVED sets Done`() {
        holder.onTaskStarted("task1", "input")
        holder.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED, "All done")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Done::class.java)
        assertThat((mode as CapsuleMode.Done).message).isEqualTo("All done")
    }

    @Test
    fun `onTaskCompleted GOAL_ACHIEVED uses default message when result missing`() {
        holder.onTaskStarted("task1", "input")
        holder.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED, null)
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Done::class.java)
        assertThat((mode as CapsuleMode.Done).message).isEqualTo("Task completed")
    }

    @Test
    fun `onTaskCompleted GOAL_ACHIEVED uses default message when result blank`() {
        holder.onTaskStarted("task1", "input")
        holder.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED, "   ")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Done::class.java)
        assertThat((mode as CapsuleMode.Done).message).isEqualTo("Task completed")
    }

    @Test
    fun `onTaskCompleted ERROR sets Error`() {
        holder.onTaskStarted("task1", "input")
        holder.onTaskCompleted(TaskOutcome.ERROR)
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Error::class.java)
    }

    @Test
    fun `onTaskCompleted ignored when already Hidden`() {
        holder.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED)
        assertThat(holder.mode.value).isEqualTo(CapsuleMode.Hidden)
    }

    @Test
    fun `onSessionEnded USER_STOPPED sets Hidden`() {
        holder.onTaskStarted("task1", "input")
        holder.onSessionEnded(SessionEndReason.USER_STOPPED)
        assertThat(holder.mode.value).isEqualTo(CapsuleMode.Hidden)
    }

    @Test
    fun `onSessionEnded IDLE_TIMEOUT sets Hidden even after completed task`() {
        holder.onTaskStarted("task1", "input")
        holder.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED, "Summary")
        holder.onSessionEnded(SessionEndReason.IDLE_TIMEOUT)
        assertThat(holder.mode.value).isEqualTo(CapsuleMode.Hidden)
    }

    @Test
    fun `auto-hide transitions Done to Hidden after 3 seconds`() {
        holder.onTaskStarted("task1", "input")
        holder.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED)
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Done::class.java)

        scope.advanceTimeBy(3001)
        assertThat(holder.mode.value).isEqualTo(CapsuleMode.Hidden)
    }

    @Test
    fun `auto-hide cancelled by new task`() {
        holder.onTaskStarted("task1", "input")
        holder.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED)
        holder.onTaskStarted("task2", "new task")

        scope.advanceTimeBy(3001)
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Running::class.java)
    }

    // ── Error ──

    @Test
    fun `onError sets Error mode`() {
        holder.onError("Network error")
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Error::class.java)
        assertThat((mode as CapsuleMode.Error).message).isEqualTo("Network error")
    }

    @Test
    fun `onDismissError transitions to Hidden`() {
        holder.onError("Network error")
        holder.onDismissError()
        assertThat(holder.mode.value).isEqualTo(CapsuleMode.Hidden)
    }

    @Test
    fun `onDismissError ignored when not in Error`() {
        holder.onTaskStarted("task1", "input")
        val modeBefore = holder.mode.value
        holder.onDismissError()
        assertThat(holder.mode.value).isEqualTo(modeBefore)
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

    // ── Derived properties ──

    @Test
    fun `hasActiveTask is false when Hidden`() {
        assertThat(holder.hasActiveTask).isFalse()
    }

    @Test
    fun `hasActiveTask is true when Running`() {
        holder.onTaskStarted("task1", "input")
        assertThat(holder.hasActiveTask).isTrue()
    }

    @Test
    fun `hasActiveTask is false when Done`() {
        holder.onTaskStarted("task1", "input")
        holder.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED)
        assertThat(holder.hasActiveTask).isFalse()
    }

    @Test
    fun `hasActiveTask is false when Error`() {
        holder.onError("error")
        assertThat(holder.hasActiveTask).isFalse()
    }
}
