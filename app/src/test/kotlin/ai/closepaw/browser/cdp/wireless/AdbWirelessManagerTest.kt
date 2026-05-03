package ai.closepaw.browser.cdp.wireless

import ai.closepaw.browser.cdp.shizuku.IChromeDevtoolsUserService
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Coverage for [AdbWirelessManager]: each public method is exercised against a mocked binder.
 * Hand-rolled `binderProvider` mirrors the convention in [ShizukuChromeDevtoolsBridgeTest] —
 * lambda returns the same mock every time, no DI framework needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdbWirelessManagerTest {

    private val binder = mockk<IChromeDevtoolsUserService>(relaxed = true)
    private val manager = AdbWirelessManager(
        binderProvider = { binder },
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun currentBssid_returns_value_from_binder() = runTest {
        every { binder.currentBssid } returns "aa:bb:cc:11:22:33"
        assertThat(manager.currentBssid()).isEqualTo("aa:bb:cc:11:22:33")
    }

    @Test
    fun currentBssid_returns_null_when_binder_returns_null() = runTest {
        every { binder.currentBssid } returns null
        assertThat(manager.currentBssid()).isNull()
    }

    @Test
    fun enableWirelessDebugging_happy_path_returns_success() = runTest {
        every { binder.currentBssid } returns "aa:bb:cc:11:22:33"
        every { binder.enableWirelessDebugging("aa:bb:cc:11:22:33") } returns true

        val result = manager.enableWirelessDebugging()

        assertThat(result.isSuccess).isTrue()
        verify { binder.enableWirelessDebugging("aa:bb:cc:11:22:33") }
    }

    @Test
    fun enableWirelessDebugging_fails_when_no_bssid() = runTest {
        every { binder.currentBssid } returns null

        val result = manager.enableWirelessDebugging()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("No Wi-Fi BSSID")
    }

    @Test
    fun enableWirelessDebugging_fails_when_binder_returns_false() = runTest {
        every { binder.currentBssid } returns "aa:bb:cc:11:22:33"
        every { binder.enableWirelessDebugging(any()) } returns false

        val result = manager.enableWirelessDebugging()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("returned false")
    }

    @Test
    fun getAdbWirelessPort_returns_binder_value() = runTest {
        every { binder.adbWirelessPort } returns 37123
        assertThat(manager.getAdbWirelessPort()).isEqualTo(37123)
    }

    @Test
    fun getAdbWirelessPort_returns_minus_one_when_disabled() = runTest {
        every { binder.adbWirelessPort } returns -1
        assertThat(manager.getAdbWirelessPort()).isEqualTo(-1)
    }

    @Test
    fun openPairPort_returns_port_from_binder() = runTest {
        every { binder.enablePairingByQrCode("ClosePaw", "secret_psk") } returns 41234

        val port = manager.openPairPort("ClosePaw", "secret_psk".toByteArray())

        assertThat(port).isEqualTo(41234)
        verify { binder.enablePairingByQrCode("ClosePaw", "secret_psk") }
    }

    @Test
    fun openPairPort_throws_when_binder_returns_negative() = runTest {
        every { binder.enablePairingByQrCode(any(), any()) } returns -1

        try {
            manager.openPairPort("ClosePaw", "secret_psk".toByteArray())
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertThat(e.message).contains("did not appear")
        }
    }

    @Test
    fun openPairPort_rejects_empty_psk() = runTest {
        try {
            manager.openPairPort("ClosePaw", ByteArray(0))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("psk")
        }
    }

    @Test
    fun closePairPort_invokes_binder() = runTest {
        manager.closePairPort()
        verify { binder.disablePairing() }
    }

    @Test
    fun isPubkeyAuthorized_returns_binder_value() = runTest {
        every { binder.isPubkeyInAdbKeys("fp123") } returns true
        assertThat(manager.isPubkeyAuthorized("fp123")).isTrue()

        every { binder.isPubkeyInAdbKeys("missing") } returns false
        assertThat(manager.isPubkeyAuthorized("missing")).isFalse()
    }
}
