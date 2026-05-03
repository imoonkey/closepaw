package ai.closepaw.browser.cdp.wireless

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test

class AdbCryptoKeyStoreTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("adb-keystore-test").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `loadOrCreate persists files and isPersisted reflects state`() {
        val store = AdbCryptoKeyStore(dir)
        assertThat(store.isPersisted()).isFalse()

        val first = store.loadOrCreate()
        assertThat(first.keyPair).isNotNull()
        assertThat(first.certificate).isNotNull()
        assertThat(store.isPersisted()).isTrue()
        assertThat(File(dir, AdbCryptoKeyStore.SENTINEL).isFile).isTrue()
        assertThat(File(dir, AdbCryptoKeyStore.PRIVATE_KEY).isFile).isTrue()
        assertThat(File(dir, AdbCryptoKeyStore.CERT).isFile).isTrue()
    }

    @Test
    fun `second load returns same fingerprint`() {
        val store = AdbCryptoKeyStore(dir)
        val fp1 = store.loadOrCreate().let { store.fingerprint() }
        val fp2 = AdbCryptoKeyStore(dir).loadOrCreate().let { AdbCryptoKeyStore(dir).fingerprint() }
        assertThat(fp1).isEqualTo(fp2)
    }

    @Test
    fun `reset regenerates a new fingerprint`() {
        val store = AdbCryptoKeyStore(dir)
        store.loadOrCreate()
        val fp1 = store.fingerprint()

        store.reset()
        assertThat(store.isPersisted()).isFalse()

        val fp2 = AdbCryptoKeyStore(dir).fingerprint()
        assertThat(fp1).isNotEqualTo(fp2)
    }

    @Test
    fun `cert has expected subject and validity over one year`() {
        val store = AdbCryptoKeyStore(dir)
        val cert = store.loadOrCreate().certificate

        assertThat(cert.subjectX500Principal.name).contains("CN=Adb")
        assertThat(cert.subjectX500Principal.name).contains("O=Android")
        assertThat(cert.subjectX500Principal.name).contains("C=US")
        assertThat(cert.issuerX500Principal).isEqualTo(cert.subjectX500Principal)

        val validityMs = cert.notAfter.time - cert.notBefore.time
        assertThat(validityMs).isGreaterThan(TimeUnit.DAYS.toMillis(365))
    }
}
