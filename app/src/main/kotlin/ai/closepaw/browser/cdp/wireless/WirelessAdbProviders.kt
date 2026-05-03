package ai.closepaw.browser.cdp.wireless

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt

/**
 * Installs the JCE/JCA providers our wireless-ADB stack depends on. Both have Android-specific
 * gotchas:
 *
 * - **BouncyCastle**: Android ships a STRIPPED stub provider already named "BC" (legacy Spongy
 *   Castle remnant) that omits SHA256WITHRSA among other algorithms. A naive `Security.addProvider`
 *   silently no-ops because a "BC" already exists. We must remove the stub and insert the real
 *   bcprov-jdk18on at priority 1.
 * - **Conscrypt**: the platform Conscrypt is hidden API and its `exportKeyingMaterial` is
 *   unreachable on some vendor builds even with HiddenApiBypass. We bundle
 *   `org.conscrypt:conscrypt-android` and install it at priority 1 so SSLContext.getInstance
 *   returns Conscrypt's SSLContext (whose SSLSockets the bundled `Conscrypt.exportKeyingMaterial`
 *   accepts).
 *
 * Both installations are idempotent and synchronized.
 */
internal object WirelessAdbProviders {

    @Volatile private var installed = false
    private val lock = Any()

    fun ensure() {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            installBouncyCastle()
            installConscrypt()
            installed = true
        }
    }

    private fun installBouncyCastle() {
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing == null || existing.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private fun installConscrypt() {
        val existing = Security.getProvider(CONSCRYPT_PROVIDER_NAME)
        if (existing == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }

    private const val CONSCRYPT_PROVIDER_NAME = "Conscrypt"
}
