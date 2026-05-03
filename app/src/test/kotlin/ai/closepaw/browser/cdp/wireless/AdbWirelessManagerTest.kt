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
    fun isPubkeyAuthorized_true_when_pubkey_present_in_adb_keys() = runTest {
        val pubkey = "AAAA_OUR_PUBKEY_BASE64_BLOB_AAAA"
        every { binder.readAdbKeys() } returns
            "OTHER_KEY_BASE64 alice@host\n$pubkey ClosePaw@P0110\nYET_ANOTHER bob@host\n"

        assertThat(manager.isPubkeyAuthorized(pubkey)).isTrue()
    }

    @Test
    fun isPubkeyAuthorized_false_when_pubkey_missing() = runTest {
        every { binder.readAdbKeys() } returns "OTHER_KEY_BASE64 alice@host\n"

        assertThat(manager.isPubkeyAuthorized("MY_KEY_BASE64")).isFalse()
    }

    @Test
    fun isPubkeyAuthorized_false_when_adb_keys_unreadable() = runTest {
        every { binder.readAdbKeys() } returns null

        assertThat(manager.isPubkeyAuthorized("MY_KEY_BASE64")).isFalse()
    }

    @Test
    fun isPubkeyAuthorized_false_for_blank_input() = runTest {
        // Empty input must not match an empty file (or worse: a non-empty file via String.contains).
        assertThat(manager.isPubkeyAuthorized("")).isFalse()
        verify(exactly = 0) { binder.readAdbKeys() }
    }

    @Test
    fun pruneAdbKeys_removes_extra_closepaw_entries_keeps_current_and_others() = runTest {
        val current = "AAAA_CURRENT_KEY"
        val before = listOf(
            "OTHER_KEY alice@host",
            "STALE_CLOSEPAW_1 ClosePaw@P0110",
            "STALE_CLOSEPAW_2 ClosePaw@P0110",
            "$current ClosePaw@P0110",
            "STALE_CLOSEPAW_3 ClosePaw@P0110",
            "BOB_KEY bob@host",
        ).joinToString("\n", postfix = "\n")
        every { binder.readAdbKeys() } returns before
        every { binder.writeAdbKeys(any()) } returns true

        val pruned = manager.pruneAdbKeys(current)

        assertThat(pruned).isTrue()
        val expected = listOf(
            "OTHER_KEY alice@host",
            "$current ClosePaw@P0110",
            "BOB_KEY bob@host",
        ).joinToString("\n", postfix = "\n")
        verify { binder.writeAdbKeys(expected) }
    }

    @Test
    fun pruneAdbKeys_noop_when_only_current_closepaw_present() = runTest {
        val current = "AAAA_CURRENT_KEY"
        val content = "OTHER_KEY alice@host\n$current ClosePaw@P0110\n"
        every { binder.readAdbKeys() } returns content

        assertThat(manager.pruneAdbKeys(current)).isFalse()
        verify(exactly = 0) { binder.writeAdbKeys(any()) }
    }

    @Test
    fun pruneAdbKeys_noop_when_no_closepaw_entries() = runTest {
        every { binder.readAdbKeys() } returns "OTHER_KEY alice@host\nBOB_KEY bob@host\n"

        assertThat(manager.pruneAdbKeys("AAAA_CURRENT_KEY")).isFalse()
        verify(exactly = 0) { binder.writeAdbKeys(any()) }
    }

    @Test
    fun pruneAdbKeys_skips_when_current_key_missing_from_file() = runTest {
        // Defensive: never delete the only ClosePaw entry we cannot verify is current.
        every { binder.readAdbKeys() } returns "STALE_CLOSEPAW_1 ClosePaw@P0110\n"

        assertThat(manager.pruneAdbKeys("AAAA_CURRENT_KEY")).isFalse()
        verify(exactly = 0) { binder.writeAdbKeys(any()) }
    }

    @Test
    fun pruneAdbKeys_returns_false_when_adb_keys_unreadable() = runTest {
        every { binder.readAdbKeys() } returns null

        assertThat(manager.pruneAdbKeys("AAAA_CURRENT_KEY")).isFalse()
        verify(exactly = 0) { binder.writeAdbKeys(any()) }
    }

    @Test
    fun pruneAdbKeys_returns_false_when_writeback_fails() = runTest {
        val current = "AAAA_CURRENT_KEY"
        every { binder.readAdbKeys() } returns
            "STALE_CLOSEPAW ClosePaw@P0110\n$current ClosePaw@P0110\n"
        every { binder.writeAdbKeys(any()) } returns false

        assertThat(manager.pruneAdbKeys(current)).isFalse()
    }

    @Test
    fun pruneAdbKeys_drops_blank_lines_and_strips_cr() = runTest {
        val current = "AAAA_CURRENT_KEY"
        val before = "OTHER alice\r\n\nSTALE ClosePaw@P0110\r\n$current ClosePaw@P0110\n\n"
        every { binder.readAdbKeys() } returns before
        every { binder.writeAdbKeys(any()) } returns true

        assertThat(manager.pruneAdbKeys(current)).isTrue()
        verify { binder.writeAdbKeys("OTHER alice\n$current ClosePaw@P0110\n") }
    }

    @Test
    fun pruneAdbKeys_rejects_blank_retain_pubkey() = runTest {
        try {
            manager.pruneAdbKeys("")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("retainPubkeyBase64")
        }
    }
}
