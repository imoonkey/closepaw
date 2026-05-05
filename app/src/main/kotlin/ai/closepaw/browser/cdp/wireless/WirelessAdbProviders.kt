package ai.closepaw.browser.cdp.wireless

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt

/**
 * Registers the JCE/JCA providers our wireless-ADB stack depends on, without changing global
 * provider priority. Both have Android-specific gotchas:
 *
 * - **BouncyCastle**: Android ships a STRIPPED stub provider already named "BC" (legacy Spongy
 *   Castle remnant) that omits SHA256WITHRSA among other algorithms. A naive `Security.addProvider`
 *   silently no-ops because a "BC" already exists. We must remove the stub then add the real
 *   bcprov-jdk18on. We append at the END of the provider list (rather than `insertProviderAt(_, 1)`)
 *   so unqualified `getInstance` calls elsewhere in the process keep getting the platform default.
 *   Browser-internal call sites that need BC-specific behaviour pass `"BC"` explicitly via
 *   provider-qualified `getInstance(...)` / `setProvider(...)`.
 * - **Conscrypt**: the platform Conscrypt is hidden API and its `exportKeyingMaterial` is
 *   unreachable on some vendor builds even with HiddenApiBypass. We bundle
 *   `org.conscrypt:conscrypt-android` and append it (priority unchanged) so other code is
 *   unaffected. Browser-internal TLS call sites use `SSLContext.getInstance("TLSv1.3", "Conscrypt")`
 *   to resolve to the bundled Conscrypt explicitly.
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
            // Append at end: leaves global provider order untouched. Browser code that needs the
            // real bcprov-jdk18on uses provider-qualified getInstance / setProvider.
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun installConscrypt() {
        // Conscrypt requires its native lib; skip silently in host-JVM unit tests where
        // conscrypt_jni isn't present (the keystore tests don't need TLS).
        runCatching {
            val existing = Security.getProvider(CONSCRYPT_PROVIDER_NAME)
            if (existing == null) {
                // Append rather than insertProviderAt(_, 1): unqualified SSLContext.getInstance
                // calls elsewhere keep the platform default. We always qualify with "Conscrypt".
                Security.addProvider(Conscrypt.newProvider())
            }
        }
    }

    private const val CONSCRYPT_PROVIDER_NAME = "Conscrypt"
}
