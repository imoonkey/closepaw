package ai.closepaw.browser.cdp.wireless

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbPairingClientInstrumentedTest {

    @Test
    fun spake25519Reachable() {
        val cls = Class.forName("ai.closepaw.browser.cdp.wireless.Spake25519")
        assertNotNull(cls)
        // Sanity check: the Ed25519 group element class from net.i2p.crypto:eddsa must also resolve
        // (Spake25519 references it).
        val ge = Class.forName("net.i2p.crypto.eddsa.math.GroupElement")
        assertNotNull(ge)
    }

    @Test
    fun conscryptExporterReachable() {
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Lorg/conscrypt/")
        val cls = Class.forName("org.conscrypt.Conscrypt")
        assertNotNull(cls)
    }
}
