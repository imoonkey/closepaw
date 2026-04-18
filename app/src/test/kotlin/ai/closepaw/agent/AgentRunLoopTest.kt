package ai.closepaw.agent

import com.google.common.truth.Truth.assertThat
import ai.closepaw.history.HistoryManager
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.LLMToolCall
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ResponsesResult
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.SessionLlmConfig
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.AppInfo
import ai.closepaw.platform.DisplayInfo
import ai.closepaw.platform.UIAction
import ai.closepaw.session.AgentSessionState
import ai.closepaw.session.SessionServices
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.tool.impl.CompleteTaskTool
import ai.closepaw.trace.NoopTraceRecorder
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Characterization tests for the Agent.run() control-loop FSM.
 *
 * Spec: doc/main/state_machines/agent_run_loop.md
 *
 * Each test exercises one transition (or guard rejection) by driving the
 * inner [AgentTurnRunner] indirectly through a programmable [LLMClient].
 *
 * The Agent loop turns LLM-stream behavior into [TurnOutcome]s as follows:
 * - empty stream (no text, no tool call)         -> Continue
 * - text-only stream                             -> Complete(success=true)
 * - complete_task tool call (status=success)     -> Complete(success=true)
 * - complete_task tool call (status=failure)     -> Complete(success=false)
 * - SocketTimeoutException                       -> Error(recoverable=true)
 * - UnknownHostException                         -> Error(recoverable=false)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunLoopTest {

    // ---------------- Outcome: Continue + guard: MaxTurnsReached ----------------

    @Test
    fun `Continue outcome loops until MaxTurnsReached`() = runTest {
        val llm = ProgrammableLLMClient(
            // Three "Continue" responses: 1st turn runs, 2nd turn runs, 3rd attempt is gated.
            listOf(LLMBehavior.Continue, LLMBehavior.Continue)
        )
        val agent = newAgent(llm, maxTurns = 2)

        val reason = agent.run()

        assertThat(reason).isEqualTo(AgentStopReason.MaxTurnsReached)
        assertThat(llm.callCount).isEqualTo(2)
    }

    // ---------------- Outcome: Complete(success=true) ----------------

    @Test
    fun `Complete success via text-only response stops with GoalAchieved`() = runTest {
        val llm = ProgrammableLLMClient(listOf(LLMBehavior.TextOnly("done")))
        val agent = newAgent(llm, maxTurns = 5)

        val reason = agent.run()

        assertThat(reason).isInstanceOf(AgentStopReason.GoalAchieved::class.java)
        assertThat((reason as AgentStopReason.GoalAchieved).message).isEqualTo("done")
    }

    @Test
    fun `Complete success via complete_task tool stops with GoalAchieved`() = runTest {
        val llm = ProgrammableLLMClient(
            listOf(LLMBehavior.CompleteTaskCall(success = true, answer = "ok"))
        )
        val agent = newAgent(llm, maxTurns = 5, registerCompleteTask = true)

        val reason = agent.run()

        assertThat(reason).isInstanceOf(AgentStopReason.GoalAchieved::class.java)
        assertThat((reason as AgentStopReason.GoalAchieved).message).isEqualTo("ok")
    }

    // ---------------- Outcome: Complete(success=false) ----------------

    @Test
    fun `Complete failure via complete_task stops with TaskImpossible`() = runTest {
        val llm = ProgrammableLLMClient(
            listOf(LLMBehavior.CompleteTaskCall(success = false, answer = "blocked"))
        )
        val agent = newAgent(llm, maxTurns = 5, registerCompleteTask = true)

        val reason = agent.run()

        assertThat(reason).isInstanceOf(AgentStopReason.TaskImpossible::class.java)
        assertThat((reason as AgentStopReason.TaskImpossible).message).isEqualTo("blocked")
    }

    // ---------------- Outcome: Error(non-recoverable) ----------------

    @Test
    fun `non recoverable error stops immediately with Error`() = runTest {
        val llm = ProgrammableLLMClient(
            listOf(LLMBehavior.Throw(UnknownHostException("dns")))
        )
        val agent = newAgent(llm, maxTurns = 5)

        val reason = agent.run()

        assertThat(reason).isInstanceOf(AgentStopReason.Error::class.java)
        assertThat(llm.callCount).isEqualTo(1) // no retry attempted
    }

    // ---------------- Outcome: Error(recoverable) — retry & exhaustion ----------------

    @Test
    fun `recoverable error retries within budget and can recover via Continue`() = runTest {
        val llm = ProgrammableLLMClient(
            listOf(
                LLMBehavior.Throw(SocketTimeoutException("t1")), // turn 1: error -> retry
                LLMBehavior.Continue,                            // turn 2: continue -> reset retry
                LLMBehavior.TextOnly("done")                     // turn 3: complete
            )
        )
        val agent = newAgent(llm, maxTurns = 5)

        val reason = agent.run()

        assertThat(reason).isInstanceOf(AgentStopReason.GoalAchieved::class.java)
        assertThat(llm.callCount).isEqualTo(3)
    }

    @Test
    fun `recoverable error exhausts retry budget and stops with Error`() = runTest {
        val llm = ProgrammableLLMClient(
            listOf(
                LLMBehavior.Throw(SocketTimeoutException("t1")),
                LLMBehavior.Throw(SocketTimeoutException("t2")) // back-to-back -> retryCount=1, can't retry
            )
        )
        val agent = newAgent(llm, maxTurns = 5)

        val reason = agent.run()

        assertThat(reason).isInstanceOf(AgentStopReason.Error::class.java)
        assertThat(llm.callCount).isEqualTo(2)
    }

    @Test
    fun `Continue resets retry counter so a later error gets a fresh retry`() = runTest {
        val llm = ProgrammableLLMClient(
            listOf(
                LLMBehavior.Throw(SocketTimeoutException("t1")), // retry++
                LLMBehavior.Continue,                            // retry := 0
                LLMBehavior.Throw(SocketTimeoutException("t2")), // retry++ (because reset)
                LLMBehavior.TextOnly("done")                     // succeeds
            )
        )
        val agent = newAgent(llm, maxTurns = 6)

        val reason = agent.run()

        assertThat(reason).isInstanceOf(AgentStopReason.GoalAchieved::class.java)
        assertThat(llm.callCount).isEqualTo(4)
    }

    @Test
    fun `recoverable error on the last allowed turn cannot retry`() = runTest {
        // hasRemainingTurns guard: turnCount(=1) < maxTurns(=1) is false.
        val llm = ProgrammableLLMClient(
            listOf(LLMBehavior.Throw(SocketTimeoutException("only attempt")))
        )
        val agent = newAgent(llm, maxTurns = 1)

        val reason = agent.run()

        assertThat(reason).isInstanceOf(AgentStopReason.Error::class.java)
        assertThat(llm.callCount).isEqualTo(1)
    }

    // ---------------- Guard: MaxTurnsReached after a successful Continue ----------------

    @Test
    fun `MaxTurnsReached fires before next turn even after Continue`() = runTest {
        // maxTurns=1: first turn runs (Continue), then top-of-loop guard rejects.
        val llm = ProgrammableLLMClient(listOf(LLMBehavior.Continue))
        val agent = newAgent(llm, maxTurns = 1)

        val reason = agent.run()

        assertThat(reason).isEqualTo(AgentStopReason.MaxTurnsReached)
        assertThat(llm.callCount).isEqualTo(1)
    }

    // ---------------- Transition: Running -> UserRequested via stop() ----------------

    @Test
    fun `stop request before run causes UserRequested without invoking LLM`() = runTest {
        val llm = ProgrammableLLMClient(listOf(LLMBehavior.TextOnly("never")))
        val agent = newAgent(llm, maxTurns = 5)
        agent.stop()

        val reason = agent.run()

        assertThat(reason).isEqualTo(AgentStopReason.UserRequested)
        assertThat(llm.callCount).isEqualTo(0)
    }

    @Test
    fun `cancellation signal completed before run causes UserRequested`() = runTest {
        val llm = ProgrammableLLMClient(listOf(LLMBehavior.TextOnly("never")))
        val cancellation = CompletableDeferred<AgentStopReason>().apply {
            complete(AgentStopReason.UserRequested)
        }
        val agent = newAgent(llm, maxTurns = 5, cancellationSignal = cancellation)

        val reason = agent.run()

        assertThat(reason).isEqualTo(AgentStopReason.UserRequested)
        assertThat(llm.callCount).isEqualTo(0)
    }

    // ---------------- Transition: Running -> Paused -> Running ----------------

    @Test
    fun `pause then resume then stop completes the pause Deferred`() = runTest(
        UnconfinedTestDispatcher()
    ) {
        // GatedLLMClient parks each turn on `awaitTurn()` so we can interleave
        // pause/resume/stop deterministically between turns.
        val llm = GatedLLMClient()
        val agent = newAgent(llm, maxTurns = 100)

        val runJob = async { agent.run() }
        // Allow turn 1 to complete and re-enter the loop.
        llm.awaitTurnCalled()
        llm.completeTurn(LLMBehavior.Continue)

        val pauseConfirmed = agent.pause()
        // Turn 2 enters the LLM gate; loop returns to top and observes pause.
        llm.awaitTurnCalled()
        // Release turn 2's behavior so the runner finishes; the next iteration
        // is where the pause check fires.
        llm.completeTurn(LLMBehavior.Continue)
        // Hard sync: await() returns only when the loop has reached the pause
        // block and called pauseConfirmed.complete(Unit). At that point the
        // loop is provably parked on `pauseState.first { !it }` — no scheduler
        // sensitivity, no yield() races.
        pauseConfirmed.await()

        agent.resume()
        // After resume, loop runs another turn — let it queue and then stop.
        llm.awaitTurnCalled()
        agent.stop()
        llm.completeTurn(LLMBehavior.Continue)

        val reason = runJob.await()
        assertThat(reason).isEqualTo(AgentStopReason.UserRequested)
    }

    @Test
    fun `stop while paused unblocks the loop and exits with UserRequested`() = runTest(
        UnconfinedTestDispatcher()
    ) {
        // Guards the post-pause `shouldContinue()` re-check (Agent.kt:78-81):
        // a stop arriving during pause must terminate the run.
        val llm = GatedLLMClient()
        val agent = newAgent(llm, maxTurns = 100)

        val runJob = async { agent.run() }
        llm.awaitTurnCalled()
        val pauseConfirmed = agent.pause()
        llm.completeTurn(LLMBehavior.Continue)
        // Hard sync: loop is provably parked in the pause block once this resolves.
        pauseConfirmed.await()

        // stop() flips pauseState=false, which both wakes `first { !it }`
        // AND makes the next shouldContinue() return false.
        agent.stop()

        val reason = runJob.await()
        assertThat(reason).isEqualTo(AgentStopReason.UserRequested)
    }

    // ---------------- Outcome: Cancelled (in-turn) ----------------

    @Test
    fun `in turn Cancelled outcome maps to UserRequested`() = runTest(
        UnconfinedTestDispatcher()
    ) {
        // To exercise AgentTurnRunner.executeTurn's `isTurnCancelled()` check
        // (which fires AFTER capturePreTurnSnapshot returns), we let the screen
        // capture itself complete the cancellation signal. The turn enters with
        // shouldContinue()=true at the loop top, captures the screen, then sees
        // cancellation and returns TurnOutcome.Cancelled — the very transition
        // documented at agent_run_loop.md "Running (turn) -> UserRequested".
        val cancellation = CompletableDeferred<AgentStopReason>()
        val platform = CancellingCapturePlatform(cancellation)
        // The LLM must never be invoked: planning is reached only if the
        // cancellation gate fails to short-circuit the turn.
        val llm = ProgrammableLLMClient(emptyList())
        val agent = newAgent(
            llm,
            maxTurns = 5,
            cancellationSignal = cancellation,
            platform = platform
        )

        val reason = agent.run()

        assertThat(reason).isEqualTo(AgentStopReason.UserRequested)
        assertThat(platform.captureCount).isEqualTo(1)
        assertThat(llm.callCount).isEqualTo(0) // confirms planning was skipped
    }

    // -------------------- Helpers --------------------

    private fun newAgent(
        llm: LLMClient,
        maxTurns: Int,
        registerCompleteTask: Boolean = false,
        cancellationSignal: CompletableDeferred<AgentStopReason> = CompletableDeferred(),
        platform: AndroidPlatform = FakeAndroidPlatform()
    ): Agent {
        val toolRegistry = ToolRegistry().apply {
            if (registerCompleteTask) register(CompleteTaskTool())
        }
        val policyEngine = PolicyEngine(appClassifier = AppClassifier(emptyMap()))
        val sessionConfig = SessionConfig(
            maxTurns = maxTurns,
            actionDelayMs = 0,
            llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI)
        )
        val testCatalog = ModelCatalog.fromJson(
            """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"}}"""
        )
        val services = SessionServices(
            toolRegistry = toolRegistry,
            toolRouter = ToolRouter(toolRegistry, policyEngine),
            historyManager = HistoryManager(),
            sessionState = AgentSessionState(),
            policyEngine = policyEngine,
            appClassifier = AppClassifier(emptyMap()),
            platform = platform,
            config = sessionConfig,
            llmClient = llm,
            modelCatalog = testCatalog,
            llmClientFactory = LLMClientFactory.forTest(testCatalog, llm),
            traceRecorder = NoopTraceRecorder,
            recordingService = io.mockk.mockk(relaxed = true)
        )
        return Agent(
            config = AgentExecutionConfig(
                goal = "goal",
                sessionId = SessionId.generate(),
                maxTurns = maxTurns,
                uiSettleDelayMs = 0,
                systemPrompt = "test prompt"
            ),
            services = services,
            eventEmitter = {},
            cancellationSignal = cancellationSignal
        )
    }
}

/** Per-call scripted behaviors for [ProgrammableLLMClient]. */
private sealed class LLMBehavior {
    /** Empty stream — yields TurnResult(isComplete=false) -> TurnOutcome.Continue. */
    data object Continue : LLMBehavior()

    /** Text-only stream — yields TurnResult(isComplete=true) -> Complete(success=true). */
    data class TextOnly(val text: String) : LLMBehavior()

    /** Streams a complete_task tool call so it can be executed by the registry. */
    data class CompleteTaskCall(val success: Boolean, val answer: String) : LLMBehavior()

    /** Throws on streaming — classified by [TurnErrorClassifier]. */
    data class Throw(val error: Throwable) : LLMBehavior()
}

/**
 * Returns a scripted behavior per call. Throws if the script runs out, which
 * forces tests to be explicit about expected call counts.
 */
private class ProgrammableLLMClient(
    private val script: List<LLMBehavior>
) : LLMClient() {
    var callCount: Int = 0
        private set

    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult {
        // Agent uses streaming exclusively in this path; non-streaming is a
        // sanity guard so unexpected callers are easy to spot.
        error("non-streaming chatWithTools should not be invoked by Agent.run()")
    }

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): Flow<LLMStreamEvent> = flow {
        val index = callCount
        callCount += 1
        val behavior = script.getOrNull(index)
            ?: error("ProgrammableLLMClient ran out of scripted behaviors at call $index")
        emit(LLMStreamEvent.Created("resp-$index"))
        when (behavior) {
            LLMBehavior.Continue -> {
                emit(LLMStreamEvent.Completed)
            }
            is LLMBehavior.TextOnly -> {
                emit(LLMStreamEvent.TextDelta(behavior.text))
                emit(LLMStreamEvent.Completed)
            }
            is LLMBehavior.CompleteTaskCall -> {
                val status = if (behavior.success) "success" else "failure"
                val args = """{"status":"$status","answer":"${behavior.answer}"}"""
                emit(
                    LLMStreamEvent.ToolCallDone(
                        LLMToolCall(
                            callId = "ct-$index",
                            name = "complete_task",
                            arguments = args
                        )
                    )
                )
                emit(LLMStreamEvent.Completed)
            }
            is LLMBehavior.Throw -> throw behavior.error
        }
    }
}

/**
 * Like [ProgrammableLLMClient] but each call parks until the test calls
 * [completeTurn]. Lets tests interleave pause/resume/stop deterministically.
 */
private class GatedLLMClient : LLMClient() {
    private val turnEntered = Channel<Unit>(capacity = Channel.UNLIMITED)
    private val turnRelease = Channel<LLMBehavior>(capacity = Channel.UNLIMITED)

    suspend fun awaitTurnCalled() {
        turnEntered.receive()
    }

    fun completeTurn(behavior: LLMBehavior) {
        check(turnRelease.trySend(behavior).isSuccess)
    }

    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult = error("non-streaming chatWithTools should not be invoked")

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): Flow<LLMStreamEvent> = flow {
        turnEntered.send(Unit)
        val behavior = turnRelease.receive()
        emit(LLMStreamEvent.Created("gated"))
        when (behavior) {
            LLMBehavior.Continue -> emit(LLMStreamEvent.Completed)
            is LLMBehavior.TextOnly -> {
                emit(LLMStreamEvent.TextDelta(behavior.text))
                emit(LLMStreamEvent.Completed)
            }
            is LLMBehavior.CompleteTaskCall -> {
                val status = if (behavior.success) "success" else "failure"
                emit(
                    LLMStreamEvent.ToolCallDone(
                        LLMToolCall(
                            callId = "ct",
                            name = "complete_task",
                            arguments = """{"status":"$status","answer":"${behavior.answer}"}"""
                        )
                    )
                )
                emit(LLMStreamEvent.Completed)
            }
            is LLMBehavior.Throw -> throw behavior.error
        }
    }
}

/**
 * Platform whose [captureScreen] completes the agent's cancellation signal,
 * exercising the `isTurnCancelled()` check inside `AgentTurnRunner.executeTurn`.
 */
private class CancellingCapturePlatform(
    private val signal: CompletableDeferred<AgentStopReason>
) : AndroidPlatform {
    var captureCount: Int = 0
        private set

    override suspend fun captureScreen(): ScreenSnapshot {
        captureCount += 1
        signal.complete(AgentStopReason.UserRequested)
        return ScreenSnapshot(timestamp = 0L, elements = emptyList())
    }

    override suspend fun performAction(action: UIAction): ActionResult = ActionResult.Success()
    override fun hasRequiredPermissions(): Boolean = true
    override fun getCurrentPackageName(): String? = "com.example.fake"
    override fun getDisplayInfo(): DisplayInfo =
        DisplayInfo(widthPixels = 1080, heightPixels = 1920, density = 2f)
    override suspend fun getInstalledApps(): List<AppInfo> = emptyList()
    override suspend fun launchApp(packageName: String): ActionResult = ActionResult.Success()
}
