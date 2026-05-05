package ai.closepaw.termux

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TermuxBridgeManagerTest {

    @Test
    fun `ensureReadyForSession returns ready without RUN_COMMAND when health is ready`() = runTest {
        val commandRunner = FakeCommandRunner()
        val manager = manager(commandRunner, FakeHealthProbe(HealthProbe.Ready))

        val status = manager.ensureReadyForSession(timeoutMs = 1_000)

        assertThat(status).isEqualTo(TermuxBridgeStatus.Ready)
        assertThat(commandRunner.commands).isEmpty()
    }

    @Test
    fun `ensureReadyForSession restarts deployed idle bridge without install or deploy`() = runTest {
        val commandRunner = FakeCommandRunner(bridgeExists = true)
        val healthProbe = FakeHealthProbe(HealthProbe.Unavailable, commandRunner)
        val manager = manager(commandRunner, healthProbe)

        val status = manager.ensureReadyForSession(timeoutMs = 1_000)

        assertThat(status).isEqualTo(TermuxBridgeStatus.Ready)
        assertThat(commandRunner.commands).containsExactly(Command.BridgeExists, Command.Start).inOrder()
        assertThat(commandRunner.installCalls).isEqualTo(0)
        assertThat(commandRunner.deployCalls).isEqualTo(0)
    }

    @Test
    fun `ensureReadyForSession missing bridge returns passive needs setup without start or deploy`() = runTest {
        val commandRunner = FakeCommandRunner(bridgeExists = false)
        val manager = manager(commandRunner, FakeHealthProbe(HealthProbe.Unavailable, commandRunner))

        val status = manager.ensureReadyForSession(timeoutMs = 1_000)

        assertThat(status).isEqualTo(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.HEALTH_TIMEOUT))
        assertThat(commandRunner.commands).containsExactly(Command.BridgeExists)
        assertThat(commandRunner.startCalls).isEqualTo(0)
        assertThat(commandRunner.deployCalls).isEqualTo(0)
    }

    @Test
    fun `concurrent setup calls share one in flight bootstrap`() = runTest {
        val installGate = CompletableDeferred<Unit>()
        val commandRunner = FakeCommandRunner(installGate = installGate)
        val manager = manager(commandRunner, FakeHealthProbe(HealthProbe.Unavailable, commandRunner))

        val first = async { manager.setup() }
        val second = async { manager.setup() }
        runCurrent()

        assertThat(commandRunner.installCalls).isEqualTo(1)

        installGate.complete(Unit)
        advanceUntilIdle()

        assertThat(first.await()).isEqualTo(TermuxBridgeStatus.Ready)
        assertThat(second.await()).isEqualTo(TermuxBridgeStatus.Ready)
        assertThat(commandRunner.installCalls).isEqualTo(1)
        assertThat(commandRunner.deployCalls).isEqualTo(1)
        assertThat(commandRunner.startCalls).isEqualTo(1)
    }

    @Test
    fun `ensureReadyForSession during in flight setup runs start only block`() = runTest {
        val installGate = CompletableDeferred<Unit>()
        val commandRunner = FakeCommandRunner(bridgeExists = false, installGate = installGate)
        val healthProbe = FakeHealthProbe(
            initial = HealthProbe.Unavailable,
            responses = listOf(HealthProbe.Ready, HealthProbe.Unavailable)
        )
        val manager = manager(commandRunner, healthProbe)

        val setup = async { manager.setup() }
        runCurrent()
        assertThat(commandRunner.installCalls).isEqualTo(1)

        val ensureReady = async { manager.ensureReadyForSession(timeoutMs = 10_000) }
        runCurrent()

        installGate.complete(Unit)
        advanceUntilIdle()

        assertThat(setup.await()).isEqualTo(TermuxBridgeStatus.Ready)
        assertThat(ensureReady.await())
            .isEqualTo(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.HEALTH_TIMEOUT))
        assertThat(commandRunner.commands)
            .containsExactly(Command.Probe, Command.Install, Command.Deploy, Command.Start, Command.BridgeExists)
            .inOrder()
        assertThat(commandRunner.deployCalls).isEqualTo(1)
        assertThat(commandRunner.startCalls).isEqualTo(1)
    }

    @Test
    fun `setup during in flight ensureReadyForSession runs bootstrap block`() = runTest {
        val bridgeExistsGate = CompletableDeferred<Unit>()
        val commandRunner = FakeCommandRunner(bridgeExists = false, bridgeExistsGate = bridgeExistsGate)
        val healthProbe = FakeHealthProbe(
            initial = HealthProbe.Unavailable,
            responses = listOf(HealthProbe.Unavailable, HealthProbe.Ready)
        )
        val manager = manager(commandRunner, healthProbe)

        val ensureReady = async { manager.ensureReadyForSession(timeoutMs = 10_000) }
        runCurrent()
        assertThat(commandRunner.commands).containsExactly(Command.BridgeExists)

        val setup = async { manager.setup() }
        runCurrent()

        bridgeExistsGate.complete(Unit)
        advanceUntilIdle()

        assertThat(ensureReady.await())
            .isEqualTo(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.HEALTH_TIMEOUT))
        assertThat(setup.await()).isEqualTo(TermuxBridgeStatus.Ready)
        assertThat(commandRunner.commands)
            .containsExactly(Command.BridgeExists, Command.Probe, Command.Install, Command.Deploy, Command.Start)
            .inOrder()
        assertThat(commandRunner.deployCalls).isEqualTo(1)
        assertThat(commandRunner.startCalls).isEqualTo(1)
    }

    @Test
    fun `setup during bridge outdated ensureReadyForSession still bootstraps`() = runTest {
        val firstHealthGate = CompletableDeferred<Unit>()
        val commandRunner = FakeCommandRunner()
        val healthProbe = FakeHealthProbe(
            initial = HealthProbe.Unavailable,
            responses = listOf(HealthProbe.BridgeOutdated, HealthProbe.Ready),
            firstFetchGate = firstHealthGate
        )
        val manager = manager(commandRunner, healthProbe)

        val ensureReady = async { manager.ensureReadyForSession(timeoutMs = 10_000) }
        runCurrent()

        val setup = async { manager.setup() }
        runCurrent()

        firstHealthGate.complete(Unit)
        advanceUntilIdle()

        assertThat(ensureReady.await())
            .isEqualTo(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.BRIDGE_OUTDATED))
        assertThat(setup.await()).isEqualTo(TermuxBridgeStatus.Ready)
        assertThat(commandRunner.commands)
            .containsExactly(Command.Probe, Command.Install, Command.Deploy, Command.Start)
            .inOrder()
        assertThat(commandRunner.deployCalls).isEqualTo(1)
    }

    @Test
    fun `cancelled setup awaiter does not cancel in flight bootstrap`() = runTest {
        val installGate = CompletableDeferred<Unit>()
        val commandRunner = FakeCommandRunner(installGate = installGate)
        val manager = manager(commandRunner, FakeHealthProbe(HealthProbe.Unavailable, commandRunner))

        val cancelledAwaiter = launch { manager.setup() }
        runCurrent()
        assertThat(commandRunner.installCalls).isEqualTo(1)

        cancelledAwaiter.cancel()
        runCurrent()

        val secondAwaiter = async { manager.setup() }
        runCurrent()
        assertThat(commandRunner.installCalls).isEqualTo(1)

        installGate.complete(Unit)
        advanceUntilIdle()

        assertThat(secondAwaiter.await()).isEqualTo(TermuxBridgeStatus.Ready)
        assertThat(commandRunner.installCalls).isEqualTo(1)
        assertThat(commandRunner.deployCalls).isEqualTo(1)
        assertThat(commandRunner.startCalls).isEqualTo(1)
    }

    private fun TestScope.manager(
        commandRunner: FakeCommandRunner,
        healthProbe: TermuxHealthProbe
    ): TermuxBridgeManager =
        TermuxBridgeManager(
            commandRunner = commandRunner,
            healthProbe = healthProbe,
            termuxInstalled = { true },
            bridgePayloadBase64 = { "cHJpbnQoJ29rJykK" },
            managerScope = backgroundScope
        )

    private class FakeCommandRunner(
        private val bridgeExists: Boolean = true,
        private val installGate: CompletableDeferred<Unit>? = null,
        private val bridgeExistsGate: CompletableDeferred<Unit>? = null
    ) : TermuxCommandRunner {
        val commands = mutableListOf<Command>()
        var installCalls = 0
            private set
        var deployCalls = 0
            private set
        var startCalls = 0
            private set
        var bridgeReady = false
            private set

        override suspend fun runShell(
            command: String,
            stdinBase64: String?,
            timeoutMs: Long
        ): RunCommandResult =
            when {
                "CLOSEPAW_PROBE=ok" in command -> {
                    commands += Command.Probe
                    RunCommandResult("CLOSEPAW_PROBE=ok\n", "", 0)
                }
                "apt install" in command -> {
                    commands += Command.Install
                    installCalls += 1
                    installGate?.await()
                    RunCommandResult(
                        "/data/data/com.termux/files/usr/bin/python3\n" +
                            "/data/data/com.termux/files/usr/bin/git\n" +
                            "/data/data/com.termux/files/usr/bin/rg\n",
                        "",
                        0
                    )
                }
                "CLOSEPAW_DEPLOY=ok" in command -> {
                    commands += Command.Deploy
                    deployCalls += 1
                    RunCommandResult("CLOSEPAW_DEPLOY=ok\n", "", 0)
                }
                "test -f ~/.closepaw/bridge.py" in command -> {
                    commands += Command.BridgeExists
                    bridgeExistsGate?.await()
                    if (bridgeExists) {
                        RunCommandResult("CLOSEPAW_BRIDGE=present\n", "", 0)
                    } else {
                        RunCommandResult("", "", 1)
                    }
                }
                "nohup python3 ~/.closepaw/bridge.py" in command -> {
                    commands += Command.Start
                    startCalls += 1
                    bridgeReady = true
                    RunCommandResult("CLOSEPAW_START=ok\n", "", 0)
                }
                else -> error("Unexpected RUN_COMMAND: $command")
            }
    }

    private class FakeHealthProbe(
        private val initial: HealthProbe,
        private val commandRunner: FakeCommandRunner? = null,
        responses: List<HealthProbe> = emptyList(),
        private val firstFetchGate: CompletableDeferred<Unit>? = null
    ) : TermuxHealthProbe {
        private val responses = ArrayDeque(responses)
        private var fetchCalls = 0

        override suspend fun fetch(): HealthProbe {
            if (fetchCalls == 0) firstFetchGate?.await()
            fetchCalls += 1
            if (responses.isNotEmpty()) return responses.removeFirst()
            return if (commandRunner?.bridgeReady == true) HealthProbe.Ready else initial
        }
    }

    private enum class Command {
        Probe,
        Install,
        Deploy,
        BridgeExists,
        Start
    }
}
