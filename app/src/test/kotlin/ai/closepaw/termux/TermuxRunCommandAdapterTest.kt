package ai.closepaw.termux

import android.app.ForegroundServiceStartNotAllowedException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TermuxRunCommandAdapterTest {

    @Test
    fun `auto wake retry lets setup reach ready after first FGS rejection`() = runTest {
        val commandRunner =
            AutoWakeCommandRunner(
                firstProbeFailure = ForegroundServiceStartNotAllowedException(FGS_REJECTED_MESSAGE),
                wakeSucceeds = true
            )
        val manager = manager(commandRunner)

        val status = manager.setup()

        assertThat(status).isEqualTo(TermuxBridgeStatus.Ready)
        assertThat(manager.state.value).isEqualTo(TermuxBridgeStatus.Ready)
        assertThat(commandRunner.commands)
            .containsExactly(Command.Probe, Command.Install, Command.Deploy, Command.Start)
            .inOrder()
        assertThat(commandRunner.probeStartAttempts).isEqualTo(2)
        assertThat(commandRunner.wakeCalls).isEqualTo(1)
    }

    @Test
    fun `wake failure falls through to termux not running without retry loop`() = runTest {
        val commandRunner =
            AutoWakeCommandRunner(
                firstProbeFailure = ForegroundServiceStartNotAllowedException(FGS_REJECTED_MESSAGE),
                wakeSucceeds = false
            )
        val manager = manager(commandRunner)

        val status = manager.setup()

        assertThat(status)
            .isEqualTo(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_NOT_RUNNING))
        assertThat(manager.state.value).isEqualTo(status)
        assertThat(commandRunner.commands).containsExactly(Command.Probe)
        assertThat(commandRunner.probeStartAttempts).isEqualTo(1)
        assertThat(commandRunner.wakeCalls).isEqualTo(1)
    }

    @Test
    fun `unrelated SecurityException is not auto wake retried`() = runTest {
        var startAttempts = 0
        var wakeCalls = 0

        val error =
            startRunCommandWithAutoWake(
                startRunCommand = {
                    startAttempts += 1
                    throw SecurityException("Permission Denial: missing RUN_COMMAND permission")
                },
                wakeTermux = {
                    wakeCalls += 1
                    true
                },
                delayAfterWake = {}
            )

        assertThat(error).isEqualTo(RunCommandError.PermissionMissing)
        assertThat(startAttempts).isEqualTo(1)
        assertThat(wakeCalls).isEqualTo(0)
    }

    private fun TestScope.manager(commandRunner: AutoWakeCommandRunner): TermuxBridgeManager =
        TermuxBridgeManager(
            commandRunner = commandRunner,
            healthProbe = HealthAfterBridgeStart(commandRunner),
            termuxInstallProbe = TermuxInstallProbe { TermuxInstallState.Available },
            bridgePayloadBase64 = { "cHJpbnQoJ29rJykK" },
            managerScope = backgroundScope
        )

    private class AutoWakeCommandRunner(
        private val firstProbeFailure: Throwable,
        private val wakeSucceeds: Boolean
    ) : TermuxCommandRunner {
        val commands = mutableListOf<Command>()
        var probeStartAttempts = 0
            private set
        var wakeCalls = 0
            private set
        var bridgeReady = false
            private set

        override suspend fun runShell(
            command: String,
            stdinBase64: String?,
            timeoutMs: Long
        ): RunCommandResult {
            val type = command.toCommand()
            commands += type

            startRunCommandWithAutoWake(
                startRunCommand = { startForegroundService(type) },
                wakeTermux = {
                    wakeCalls += 1
                    wakeSucceeds
                },
                delayAfterWake = {}
            )?.let { throw it }

            return type.result().also {
                if (type == Command.Start) bridgeReady = true
            }
        }

        private fun startForegroundService(type: Command): Boolean {
            if (type != Command.Probe) return true
            probeStartAttempts += 1
            if (probeStartAttempts == 1) throw firstProbeFailure
            return true
        }

        private fun String.toCommand(): Command =
            when {
                "CLOSEPAW_PROBE=ok" in this -> Command.Probe
                "apt install" in this -> Command.Install
                "CLOSEPAW_DEPLOY=ok" in this -> Command.Deploy
                "nohup python3 ~/.closepaw/bridge.py" in this -> Command.Start
                else -> error("Unexpected RUN_COMMAND: $this")
            }

        private fun Command.result(): RunCommandResult =
            when (this) {
                Command.Probe -> RunCommandResult("CLOSEPAW_PROBE=ok\n", "", 0)
                Command.Install ->
                    RunCommandResult(
                        "/data/data/com.termux/files/usr/bin/python3\n" +
                            "/data/data/com.termux/files/usr/bin/git\n" +
                            "/data/data/com.termux/files/usr/bin/rg\n",
                        "",
                        0
                    )
                Command.Deploy -> RunCommandResult("CLOSEPAW_DEPLOY=ok\n", "", 0)
                Command.Start -> RunCommandResult("CLOSEPAW_START=ok\n", "", 0)
            }
    }

    private class HealthAfterBridgeStart(
        private val commandRunner: AutoWakeCommandRunner
    ) : TermuxHealthProbe {
        override suspend fun fetch(): HealthProbe =
            if (commandRunner.bridgeReady) HealthProbe.Ready else HealthProbe.Unavailable
    }

    private enum class Command { Probe, Install, Deploy, Start }

    private companion object {
        const val FGS_REJECTED_MESSAGE = "It is forbidden to start a 3rd process by service"
    }
}
