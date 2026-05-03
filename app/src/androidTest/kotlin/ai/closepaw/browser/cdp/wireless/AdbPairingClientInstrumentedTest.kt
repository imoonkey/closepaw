package ai.closepaw.browser.cdp.wireless

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbPairingClientInstrumentedTest {

    @Test
    fun spake2LibraryReachable() {
        val cls = Class.forName("io.github.muntashirakon.crypto.spake2.Spake2Context")
        assertNotNull(cls)
    }

    @Test
    fun conscryptExporterReachable() {
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Lorg/conscrypt/")
        val cls = Class.forName("org.conscrypt.Conscrypt")
        assertNotNull(cls)
    }
}
