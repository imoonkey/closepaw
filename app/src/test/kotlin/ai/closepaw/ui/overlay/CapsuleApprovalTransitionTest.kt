package ai.closepaw.ui.overlay

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.AskUserType
import ai.closepaw.protocol.SessionEndReason
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.ui.overlay.model.CapsuleMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test

/**
 * Gap-filling transitions for [CapsuleStateHolder] not covered by
 * [CapsuleStateHolderTest]: approval flow, takeover-without-pending shortcut, and
 * stop-request guard semantics. Locks the documented contract in
 * doc/main/state_machines/ui_capsule.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapsuleApprovalTransitionTest {

    private lateinit var scope: TestScope
    private lateinit var holder: CapsuleStateHolder

    @Before
    fun setUp() {
        scope = TestScope()
        holder = CapsuleStateHolder(scope)
    }

    // ── Approval flow ──

    @Test
    fun `onApprovalRequired from Running sets WaitingForApproval`() {
        holder.onTaskStarted("task1", "input")
        holder.onApprovalRequired(
            callId = "call-A",
            description = "Tap Allow",
            appLabel = "Chrome",
            packageName = "com.android.chrome",
            reason = "Unknown app — action requires approval",
        )
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.WaitingForApproval::class.java)
        with(mode as CapsuleMode.WaitingForApproval) {
            assertThat(callId).isEqualTo("call-A")
            assertThat(description).isEqualTo("Tap Allow")
            assertThat(appLabel).isEqualTo("Chrome")
            assertThat(packageName).isEqualTo("com.android.chrome")
            assertThat(reason).isEqualTo("Unknown app — action requires approval")
        }
    }

    @Test
    fun `onApprovalRequired stores package scoped approval subject`() {
        holder.onApprovalRequired("c1", "Tap", "App", packageName = "com.example.app", reason = "r")
        val mode = holder.mode.value as CapsuleMode.WaitingForApproval
        assertThat(mode.packageName).isEqualTo("com.example.app")
    }

    @Test
    fun `onApprovalResolved with matching callId returns to Running`() {
        holder.onApprovalRequired("call-A", "d", "App", "pkg", "r")
        val accepted = holder.onApprovalResolved("call-A")
        assertThat(accepted).isTrue()
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Running::class.java)
        assertThat((mode as CapsuleMode.Running).thought).isEqualTo("Processing...")
    }

    @Test
    fun `onApprovalResolved with mismatched callId is dropped`() {
        holder.onApprovalRequired("call-A", "d", "App", "pkg", "r")
        val before = holder.mode.value
        val accepted = holder.onApprovalResolved("call-B")
        assertThat(accepted).isFalse()
        assertThat(holder.mode.value).isEqualTo(before)
    }

    @Test
    fun `onApprovalResolved is dropped when not in WaitingForApproval`() {
        holder.onTaskStarted("task1", "input")
        val accepted = holder.onApprovalResolved("any-call")
        assertThat(accepted).isFalse()
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Running::class.java)
    }

    @Test
    fun `WaitingForApproval is treated as active task`() {
        holder.onApprovalRequired("c1", "d", "App", "pkg", "r")
        assertThat(holder.hasActiveTask).isTrue()
    }

    @Test
    fun `onUserResponseSent transitions to Running from WaitingForAction`() {
        holder.onTaskStarted("task1", "input")
        holder.onAskUser(AskUserType.ACTION, "do this", "call-1")
        val accepted = holder.onUserResponseSent("call-1")
        assertThat(accepted).isTrue()
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Running::class.java)
        assertThat((mode as CapsuleMode.Running).thought).isEqualTo("Processing response...")
    }

    // ── Takeover shortcut ──

    @Test
    fun `onTakeoverConfirmed from Running directly transitions to Takeover`() {
        holder.onTaskStarted("task1", "input")
        holder.onThoughtUpdate("doing thing")
        holder.onTakeoverConfirmed()
        val mode = holder.mode.value
        assertThat(mode).isInstanceOf(CapsuleMode.Takeover::class.java)
        assertThat((mode as CapsuleMode.Takeover).lastThought).isEqualTo("doing thing")
    }

    @Test
    fun `onResumed from TakeoverPending is allowed`() {
        holder.onTaskStarted("task1", "input")
        holder.onTakeoverRequested()
        holder.onResumed()
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Running::class.java)
    }

    // ── Stop guards ──

    @Test
    fun `onStopRequested rejected from Hidden`() {
        assertThat(holder.onStopRequested()).isFalse()
        assertThat(holder.isStopPending.value).isFalse()
    }

    @Test
    fun `onStopRequested rejected from Done`() {
        holder.onTaskStarted("task1", "input")
        holder.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED)
        assertThat(holder.onStopRequested()).isFalse()
    }

    @Test
    fun `onStopRequested rejected from Error`() {
        holder.onError("boom")
        assertThat(holder.onStopRequested()).isFalse()
    }

    @Test
    fun `onStopRequested accepted from WaitingForApproval`() {
        holder.onApprovalRequired("c1", "d", "App", "pkg", "r")
        assertThat(holder.onStopRequested()).isTrue()
        assertThat(holder.isStopPending.value).isTrue()
    }

    @Test
    fun `onStopRequested accepted from WaitingForInput`() {
        holder.onAskUser(AskUserType.QUESTION, "?", "c1")
        assertThat(holder.onStopRequested()).isTrue()
    }

    @Test
    fun `onStopRequested accepted from WaitingForAction`() {
        holder.onAskUser(AskUserType.ACTION, "do", "c1")
        assertThat(holder.onStopRequested()).isTrue()
    }

    @Test
    fun `onStopRequested accepted from Takeover`() {
        holder.onTaskStarted("task1", "input")
        holder.onTakeoverConfirmed()
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Takeover::class.java)
        assertThat(holder.onStopRequested()).isTrue()
        assertThat(holder.isStopPending.value).isTrue()
    }

    @Test
    fun `onStopRequested accepted from TakeoverPending`() {
        holder.onTaskStarted("task1", "input")
        holder.onTakeoverRequested()
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.TakeoverPending::class.java)
        assertThat(holder.onStopRequested()).isTrue()
        assertThat(holder.isStopPending.value).isTrue()
    }

    @Test
    fun `stop pending cleared when error event arrives`() {
        holder.onTaskStarted("task1", "input")
        holder.onStopRequested()
        holder.onError("network fail")
        assertThat(holder.isStopPending.value).isFalse()
    }

    @Test
    fun `stop pending cleared on session ended`() {
        holder.onTaskStarted("task1", "input")
        holder.onStopRequested()
        holder.onSessionEnded(SessionEndReason.INTERRUPTED)
        assertThat(holder.isStopPending.value).isFalse()
    }

    // ── Session end coverage ──

    @Test
    fun `onSessionEnded INTERRUPTED hides capsule`() {
        holder.onTaskStarted("task1", "input")
        holder.onSessionEnded(SessionEndReason.INTERRUPTED)
        assertThat(holder.mode.value).isEqualTo(CapsuleMode.Hidden)
    }

    // Removed `MAX_TURNS sets Done` — TaskOutcome.MAX_TURNS deleted (auto-compact branch).

    @Test
    fun `task completed TASK_IMPOSSIBLE sets Done with impossible message`() {
        holder.onTaskStarted("task1", "input")
        holder.onTaskCompleted(TaskOutcome.TASK_IMPOSSIBLE)
        assertThat((holder.mode.value as CapsuleMode.Done).message).isEqualTo("Task impossible")
    }

    @Test
    fun `task completed USER_STOPPED sets Done with stopped message`() {
        holder.onTaskStarted("task1", "input")
        holder.onTaskCompleted(TaskOutcome.USER_STOPPED)
        assertThat((holder.mode.value as CapsuleMode.Done).message).isEqualTo("Stopped")
    }

    @Test
    fun `onTaskCompleted ERROR from Takeover sets Error`() {
        holder.onTaskStarted("task1", "input")
        holder.onTakeoverConfirmed()
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Takeover::class.java)
        holder.onTaskCompleted(TaskOutcome.ERROR)
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Error::class.java)
    }

    @Test
    fun `onTaskCompleted ERROR from WaitingForApproval sets Error`() {
        holder.onApprovalRequired("c1", "d", "App", "pkg", "r")
        holder.onTaskCompleted(TaskOutcome.ERROR)
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Error::class.java)
    }

    @Test
    fun `onTaskCompleted ERROR from TakeoverPending sets Error`() {
        holder.onTaskStarted("task1", "input")
        holder.onTakeoverRequested()
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.TakeoverPending::class.java)
        holder.onTaskCompleted(TaskOutcome.ERROR)
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Error::class.java)
    }

    @Test
    fun `onTaskCompleted ERROR from WaitingForInput sets Error`() {
        holder.onTaskStarted("task1", "input")
        holder.onAskUser(AskUserType.QUESTION, "?", "c1")
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.WaitingForInput::class.java)
        holder.onTaskCompleted(TaskOutcome.ERROR)
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Error::class.java)
    }

    @Test
    fun `onTaskCompleted ERROR from WaitingForAction sets Error`() {
        holder.onTaskStarted("task1", "input")
        holder.onAskUser(AskUserType.ACTION, "do", "c1")
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.WaitingForAction::class.java)
        holder.onTaskCompleted(TaskOutcome.ERROR)
        assertThat(holder.mode.value).isInstanceOf(CapsuleMode.Error::class.java)
    }
}
