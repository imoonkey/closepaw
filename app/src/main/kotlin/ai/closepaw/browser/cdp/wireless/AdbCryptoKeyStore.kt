package ai.closepaw.browser.cdp.wireless

import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.bc.BcX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Persistent RSA-2048 keypair + self-signed X.509 cert used by the wireless ADB
 * self-pair flow. Mirrors AOSP `crypto/x509_generator.cpp` semantics so adbd
 * accepts the same material for both the pairing handshake and the mTLS adb session.
 */
class AdbCryptoKeyStore(private val baseDir: File) {

    data class Material(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
    )

    @Synchronized
    fun loadOrCreate(): Material {
        if (isPersisted()) {
            runCatching { return readMaterial() }
        }
        val material = generate()
        persist(material)
        return material
    }

    fun fingerprint(): String {
        val pub = loadOrCreate().keyPair.public.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(pub)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun isPersisted(): Boolean =
        File(baseDir, SENTINEL).isFile &&
            File(baseDir, PRIVATE_KEY).isFile &&
            File(baseDir, CERT).isFile

    @Synchronized
    fun reset() {
        if (baseDir.exists() && !baseDir.deleteRecursively()) {
            throw IOException("Failed to delete key store: ${baseDir.absolutePath}")
        }
    }

    private fun generate(): Material {
        ensureBouncyCastle()
        val keyPair = generateKeyPair()
        val cert = selfSignCert(keyPair)
        return Material(keyPair, cert)
    }

    private fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom())
        return gen.generateKeyPair()
    }

    private fun selfSignCert(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000L)
        val notAfter = Date(now + 10L * 365L * 24L * 60L * 60L * 1000L)
        val serial = BigInteger(160, SecureRandom()).abs().let {
            if (it.signum() == 0) BigInteger.ONE else it
        }
        val name = X500Principal("CN=Adb, O=Android, C=US")

        val builder = JcaX509v3CertificateBuilder(
            name,
            serial,
            notBefore,
            notAfter,
            name,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
        )
        val spki = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(
            PublicKeyFactory.createKey(keyPair.public.encoded)
        )
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            BcX509ExtensionUtils().createSubjectKeyIdentifier(spki),
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder)
    }

    private fun persist(material: Material) {
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw IOException("Failed to create key store dir: ${baseDir.absolutePath}")
        }
        val tmp = File(baseDir, TMP_DIR)
        if (tmp.exists() && !tmp.deleteRecursively()) {
            throw IOException("Failed to clear tmp dir: ${tmp.absolutePath}")
        }
        if (!tmp.mkdirs()) throw IOException("Failed to create tmp dir: ${tmp.absolutePath}")

        File(tmp, PRIVATE_KEY).writeBytes(material.keyPair.private.encoded)
        File(tmp, CERT).writeBytes(material.certificate.encoded)

        atomicMove(File(tmp, PRIVATE_KEY), File(baseDir, PRIVATE_KEY))
        atomicMove(File(tmp, CERT), File(baseDir, CERT))
        File(baseDir, SENTINEL).writeText("v=1\n", Charsets.UTF_8)
        if (tmp.exists() && !tmp.deleteRecursively()) {
            throw IOException("Failed to clean tmp dir: ${tmp.absolutePath}")
        }
    }

    private fun readMaterial(): Material {
        ensureBouncyCastle()
        val privateKeyBytes = File(baseDir, PRIVATE_KEY).readBytes()
        val certBytes = File(baseDir, CERT).readBytes()
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certBytes.inputStream()) as X509Certificate
        return Material(KeyPair(cert.publicKey, privateKey), cert)
    }

    private fun atomicMove(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {
        const val SENTINEL = ".install-complete"
        const val PRIVATE_KEY = "private.pkcs8.der"
        const val CERT = "cert.x509.der"
        private const val TMP_DIR = ".tmp"

        @Synchronized
        private fun ensureBouncyCastle() {
            WirelessAdbProviders.ensure()
        }
    }
}
