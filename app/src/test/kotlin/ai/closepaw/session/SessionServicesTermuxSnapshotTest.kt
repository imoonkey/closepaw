package ai.closepaw.session

import ai.closepaw.termux.NeedsSetupReason
import ai.closepaw.termux.TermuxBridgeStatus
import ai.closepaw.termux.TermuxCapabilitySnapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import org.junit.Test

class SessionServicesTermuxSnapshotTest {

    @Test
    fun `captureTermuxSnapshot probes bridge before snapshot`() {
        val bridge = FakeTermuxSessionBridge()

        val snapshot = SessionServices.captureTermuxSnapshot(
            bridge = bridge,
            termuxShellEnabled = true
        )

        assertThat(bridge.healthChecks).isEqualTo(1)
        assertThat(snapshot.available).isTrue()
        assertThat(snapshot.enabled).isTrue()
        assertThat(snapshot.status).isEqualTo(TermuxBridgeStatus.Ready)
    }

    @Test
    fun `captureTermuxSnapshot keeps toggle disabled after successful probe`() {
        val bridge = FakeTermuxSessionBridge()

        val snapshot = SessionServices.captureTermuxSnapshot(
            bridge = bridge,
            termuxShellEnabled = false
        )

        assertThat(bridge.healthChecks).isEqualTo(1)
        assertThat(snapshot.available).isFalse()
        assertThat(snapshot.enabled).isFalse()
        assertThat(snapshot.status).isEqualTo(TermuxBridgeStatus.Ready)
    }

    @Test
    fun `captureTermuxSnapshot falls back to current state when probe times out`() {
        val bridge = FakeTermuxSessionBridge(delayMs = 50)

        val snapshot = SessionServices.captureTermuxSnapshot(
            bridge = bridge,
            termuxShellEnabled = true,
            timeoutMs = 1
        )

        assertThat(bridge.healthChecks).isEqualTo(1)
        assertThat(snapshot.available).isFalse()
        assertThat(snapshot.enabled).isTrue()
        assertThat(snapshot.status).isEqualTo(TermuxBridgeStatus.NotInstalled)
    }

    @Test
    fun `captureTermuxSnapshot restarts idle exited bridge before snapshot`() {
        val bridge = RestartingTermuxSessionBridge()

        val snapshot = SessionServices.captureTermuxSnapshot(
            bridge = bridge,
            termuxShellEnabled = true
        )

        assertThat(bridge.healthChecks).isEqualTo(2)
        assertThat(bridge.restarts).isEqualTo(1)
        assertThat(snapshot.available).isTrue()
        assertThat(snapshot.enabled).isTrue()
        assertThat(snapshot.status).isEqualTo(TermuxBridgeStatus.Ready)
    }

    private class FakeTermuxSessionBridge(
        private val delayMs: Long = 0
    ) : TermuxSessionBridge {
        var healthChecks = 0
            private set
        private var status: TermuxBridgeStatus = TermuxBridgeStatus.NotInstalled

        override suspend fun healthCheck(): TermuxBridgeStatus {
            healthChecks += 1
            if (delayMs > 0) delay(delayMs)
            status = TermuxBridgeStatus.Ready
            return status
        }

        override fun snapshot(enabled: Boolean): TermuxCapabilitySnapshot =
            TermuxCapabilitySnapshot(
                available = enabled && status is TermuxBridgeStatus.Ready,
                enabled = enabled,
                status = status
            )
    }

    private class RestartingTermuxSessionBridge : TermuxSessionBridge {
        var healthChecks = 0
            private set
        var restarts = 0
            private set
        private var status: TermuxBridgeStatus =
            TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.HEALTH_TIMEOUT)

        override suspend fun healthCheck(): TermuxBridgeStatus {
            healthChecks += 1
            status =
                if (healthChecks == 1) {
                    TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.HEALTH_TIMEOUT)
                } else {
                    TermuxBridgeStatus.Ready
                }
            return status
        }

        override suspend fun ensureReadyForSession(timeoutMs: Long): TermuxBridgeStatus {
            val initialStatus = healthCheck()
            if (initialStatus is TermuxBridgeStatus.Ready) return initialStatus

            restarts += 1
            return healthCheck()
        }

        override fun snapshot(enabled: Boolean): TermuxCapabilitySnapshot =
            TermuxCapabilitySnapshot(
                available = enabled && status is TermuxBridgeStatus.Ready,
                enabled = enabled,
                status = status
            )
    }
}
