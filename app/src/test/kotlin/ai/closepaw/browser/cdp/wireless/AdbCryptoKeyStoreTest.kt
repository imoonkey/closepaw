package ai.closepaw.browser.cdp.wireless

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.security.interfaces.RSAPublicKey
import java.util.Base64
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
    fun `androidPubkeyBase64 equals base64 of AndroidPubkey encode`() {
        val store = AdbCryptoKeyStore(dir)
        val material = store.loadOrCreate()
        val expected = Base64.getEncoder()
            .encodeToString(AndroidPubkey.encode(material.keyPair.public as RSAPublicKey))

        assertThat(store.androidPubkeyBase64()).isEqualTo(expected)
    }

    @Test
    fun `androidPubkeyBase64 is stable across reloads from disk`() {
        // Confirms the pubkey blob serializes deterministically across PKCS8 round-trips —
        // the pair-once optimisation depends on this exact string matching what adbd has in
        // /data/misc/adb/adb_keys after the previous run.
        val first = AdbCryptoKeyStore(dir).androidPubkeyBase64()
        val second = AdbCryptoKeyStore(dir).androidPubkeyBase64()
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `reset regenerates a new pubkey`() {
        val store = AdbCryptoKeyStore(dir)
        store.loadOrCreate()
        val first = store.androidPubkeyBase64()

        store.reset()
        assertThat(store.isPersisted()).isFalse()

        val second = AdbCryptoKeyStore(dir).androidPubkeyBase64()
        assertThat(first).isNotEqualTo(second)
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
