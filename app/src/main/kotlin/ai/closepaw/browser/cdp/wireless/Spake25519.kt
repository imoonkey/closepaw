/*
 * SPAKE2-25519 — pure Java/Kotlin port of BoringSSL `spake25519.c` (ISC) operating
 * over the full Ed25519 group with cofactor 8. Wire-compatible with AOSP's adb
 * pair daemon (see `system/core/adb/pairing_auth/internal/auth.cpp`), which links
 * the same BoringSSL implementation. Replaces the previous JNI dep
 * `MuntashirAkon/spake2-android` (LGPL-3.0) with permissive (CC0/public domain)
 * `net.i2p.crypto:eddsa` providing only the Ed25519 group arithmetic primitives;
 * the SPAKE2 protocol itself is implemented here.
 *
 * Reference upstream:
 *   https://github.com/MuntashirAkon/spake2-c/blob/master/spake2.c
 * Algorithm summary (Alice role; Bob symmetric with M ↔ N):
 *   1. private_tmp = 64 random bytes; reduce mod L → 32-byte LE; left_shift_3 (×8)
 *      → private_key. (×8 puts result into prime-order subgroup → cofactor cleared
 *      when later multiplying with peer's masked point.)
 *   2. P = private_key * Base
 *   3. password_hash = SHA-512(password)              (64 bytes; transcript input)
 *      password_scalar = reduce(password_hash) mod L  (32-byte LE)
 *      apply 3-bit "password scalar hack" → password_scalar becomes a multiple of 8
 *      (BoringSSL fixes a copy-paste error from the original code by adding
 *      conditional multiples of L based on low 3 bits — see comments in the C source).
 *   4. mask = password_scalar * M                     (Alice uses M; Bob uses N)
 *   5. send Pstar = P + mask                          (32-byte encoded point)
 *   6. on receive Qstar from peer:
 *        peers_mask = password_scalar * N             (Alice uses N; Bob uses M)
 *        Q_ext = Qstar - peers_mask
 *        dh_shared = private_key * Q_ext
 *      → 32-byte encoded `dh_shared`.
 *   7. key = SHA-512(LP(my_name) || LP(their_name) || LP(my_msg) || LP(their_msg)
 *                    || LP(dh_shared_encoded) || LP(password_hash))
 *      where LP(x) = uint64_le(len(x)) || x. (Bob swaps name and msg ordering.)
 *      The 64-byte digest is the SPAKE2 output.
 *
 * Constants extracted from BoringSSL `kSpakeMSmallPrecomp` / `kSpakeNSmallPrecomp`:
 * row 0 of each precomp table encodes `1 × M` (or N) per the table doc-comment, with
 * bytes [0..32] = field-element x and bytes [32..64] = field-element y. Re-encoding
 * to the 32-byte RFC-8032 compressed form (LE y, top bit of byte 31 = LSB(x)) gives
 * the values below. Both points happen to have x_lsb = 0.
 */
package ai.closepaw.browser.cdp.wireless

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import net.i2p.crypto.eddsa.math.GroupElement
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable

class Spake25519(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    enum class Role { ALICE, BOB }

    private var state: State = State.INIT
    private lateinit var passwordHash: ByteArray   // 64 bytes — SHA-512(password); transcript input
    private lateinit var privateKeyDiv8: ByteArray // 32 bytes LE; (private_key / 8) mod L; = (random_64 reduced mod L)
    private lateinit var passwordScalarDiv8: ByteArray // 32 bytes LE; (password_scalar_hacked / 8) mod L
    private lateinit var myMsg: ByteArray          // 32 bytes encoded Pstar/Qstar (transcript input)

    private enum class State { INIT, MSG_GENERATED, KEY_GENERATED }

    /**
     * @return 32-byte encoded SPAKE2 message to send to the peer.
     */
    fun generateMessage(password: ByteArray): ByteArray {
        check(state == State.INIT) { "generateMessage called twice or after processMessage" }

        // 1. private_key = (rand mod L) * 8  →  store privateKeyDiv8 = (rand mod L)  (< L < 2^253)
        val privateTmp = ByteArray(64).also { random.nextBytes(it) }
        privateKeyDiv8 = scalarReduce64(privateTmp)

        // 2. P = private_key * Base = (privateKeyDiv8 * 8) * Base = privateKeyDiv8 * (8·Base) since
        //    Base is in the prime-order subgroup.
        val pPoint = scalarMul(privateKeyDiv8, base8Point)

        // 3. password_hash + password_scalar (32-byte LE, < L) + 3-bit hack to make it ≡ 0 (mod 8).
        passwordHash = sha512(password)
        val pwReduced = scalarReduce64(passwordHash.copyOf())
        val pwHackedDiv8 = passwordScalarHackDiv8(pwReduced)
        passwordScalarDiv8 = pwHackedDiv8

        // 4. mask = password_scalar * (M or N) = pwHackedDiv8 * (8·M or 8·N). 8·M and 8·N are in the
        //    prime-order subgroup so the scalar can be reduced mod L freely (pwHackedDiv8 < L already).
        val maskBase8 = if (role == Role.ALICE) m8Point else n8Point
        val mask = scalarMul(pwHackedDiv8, maskBase8)

        // 5. Pstar = P + mask
        val pstar = pPoint.add(mask.toCached()).toP3()
        myMsg = pstar.toByteArray()
        state = State.MSG_GENERATED
        return myMsg.copyOf()
    }

    /**
     * @return 64-byte SPAKE2 transcript hash (SHA-512). Caller derives session keys via HKDF.
     */
    fun processMessage(theirMsg: ByteArray): ByteArray {
        check(state == State.MSG_GENERATED) { "processMessage requires generateMessage first" }
        require(theirMsg.size == 32) { "SPAKE2 message must be 32 bytes" }

        val qstar = curve.createPoint(theirMsg, false)

        // peers_mask = password_scalar * (N for Alice, M for Bob) = pwHackedDiv8 * (8·N or 8·M)
        val peerMaskBase8 = if (role == Role.ALICE) n8Point else m8Point
        val peersMask = scalarMul(passwordScalarDiv8, peerMaskBase8)

        // Q_ext = Qstar - peers_mask
        val qExt = qstar.sub(peersMask.toCached()).toP3()

        // dh_shared = private_key * Q_ext = privateKeyDiv8 * (8 * Q_ext); 8·Q_ext ∈ prime-order subgroup.
        val qExt8 = qExt.mul8()
        val dhShared = scalarMul(privateKeyDiv8, qExt8)
        val dhSharedEncoded = dhShared.toByteArray()

        val sha = MessageDigest.getInstance("SHA-512")
        if (role == Role.ALICE) {
            sha.updateLP(myName); sha.updateLP(theirName)
            sha.updateLP(myMsg);  sha.updateLP(theirMsg)
        } else {
            sha.updateLP(theirName); sha.updateLP(myName)
            sha.updateLP(theirMsg);  sha.updateLP(myMsg)
        }
        sha.updateLP(dhSharedEncoded)
        sha.updateLP(passwordHash)

        state = State.KEY_GENERATED
        return sha.digest()
    }

    fun destroy() {
        if (::passwordHash.isInitialized) passwordHash.fill(0)
        if (::privateKeyDiv8.isInitialized) privateKeyDiv8.fill(0)
        if (::passwordScalarDiv8.isInitialized) passwordScalarDiv8.fill(0)
    }

    private fun GroupElement.mul8(): GroupElement {
        // 3 × point doubling = ×8. Cheaper than scalarMultiply with scalar 8.
        return dbl().toP3().dbl().toP3().dbl().toP3()
    }

    /**
     * Custom double-and-add scalar multiplication on a P3 group element. We can't use eddsa's
     * `GroupElement.scalarMultiply` because it requires a precomputed lookup table, only set up
     * for the named base point B and points constructed via `createPoint(_, true)`. Our M and N
     * points and the per-handshake mask/peer points have no precomp tables, so we implement
     * the standard LSB-first double-and-add directly using `dbl()` / `add(cached)` / `toP3()`.
     *
     * Variable-time per scalar bit (the `if` branch) — acceptable for a single-pair handshake on
     * a personal device. Scalar must be ≤ 32 bytes little-endian.
     */
    private fun scalarMul(scalarLE: ByteArray, point: GroupElement): GroupElement {
        require(scalarLE.size == 32)
        var result = curve.getZero(GroupElement.Representation.P3)
        var addend = point  // P3
        for (i in 0 until 32) {
            val b = scalarLE[i].toInt() and 0xff
            for (j in 0 until 8) {
                if ((b ushr j) and 1 == 1) {
                    result = result.add(addend.toCached()).toP3()
                }
                addend = addend.dbl().toP3()
            }
        }
        return result
    }

    companion object {
        private val curveSpec = EdDSANamedCurveTable.ED_25519_CURVE_SPEC
        private val curve = curveSpec.curve
        private val scalarOps = curveSpec.scalarOps
        private val baseB: GroupElement = curveSpec.b

        // Ed25519 prime-order subgroup order L = 2^252 + 27742317777372353535851937790883648493
        private val L: BigInteger = BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")

        // Extracted from BoringSSL kSpakeMSmallPrecomp / kSpakeNSmallPrecomp row 0
        // (which encodes 1·M / 1·N per the table format). Both points have LSB(x)=0
        // so the standard RFC-8032 compressed form is just the y-coordinate bytes.
        private val M_ENCODED = hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
        private val N_ENCODED = hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")

        // 8·M, 8·N, 8·Base — all in prime-order subgroup (cofactor cleared).
        private val m8Point: GroupElement = mul8Static(curve.createPoint(M_ENCODED, false))
        private val n8Point: GroupElement = mul8Static(curve.createPoint(N_ENCODED, false))
        private val base8Point: GroupElement = mul8Static(baseB)

        private fun mul8Static(p: GroupElement): GroupElement =
            p.dbl().toP3().dbl().toP3().dbl().toP3()

        /** Reduce a 64-byte little-endian integer mod L → 32-byte little-endian, < L. */
        internal fun scalarReduce64(x: ByteArray): ByteArray {
            require(x.size == 64)
            return scalarOps.reduce(x)
        }

        /**
         * BoringSSL's "password scalar hack": the original spec divided by cofactor in the password
         * scalar but a copy-paste error left the multiplier-by-8 omitted. Compatibility fix: add
         * conditional multiples of L (L, 2L, 4L) based on low 3 bits so the result becomes ≡ 0 (mod 8)
         * while staying mathematically equivalent (mod L). Then divide by 8 to keep the scalar < L.
         */
        internal fun passwordScalarHackDiv8(scalarLE32: ByteArray): ByteArray {
            require(scalarLE32.size == 32)
            var s = BigInteger(1, scalarLE32.reversedArray())  // BigInteger expects big-endian
            // s < L. Result will be in [s, s + 7L] ⊂ [0, 8L).
            if (s.testBit(0)) s = s.add(L)
            if (s.testBit(1)) s = s.add(L.shiftLeft(1))
            if (s.testBit(2)) s = s.add(L.shiftLeft(2))
            check(s.and(BigInteger.valueOf(7)).signum() == 0) { "password-hack failed to clear low 3 bits" }
            // Divide by 8 (low 3 bits are zero). Result < L.
            val divided = s.shiftRight(3)
            return encodeScalar32LE(divided)
        }

        internal fun encodeScalar32LE(value: BigInteger): ByteArray {
            val be = value.toByteArray()  // big-endian, may have leading sign byte
            val le = ByteArray(32)
            // Skip leading 0x00 sign byte if present, then copy least-significant bytes first.
            val srcStart = if (be.isNotEmpty() && be[0] == 0.toByte() && be.size > 32) 1 else 0
            val srcLen = (be.size - srcStart).coerceAtMost(32)
            for (i in 0 until srcLen) {
                le[i] = be[be.size - 1 - i]  // LE = reverse of BE
            }
            return le
        }

        private fun MessageDigest.updateLP(data: ByteArray) {
            val lenLE = ByteArray(8)
            var n = data.size.toLong()
            for (i in 0 until 8) { lenLE[i] = (n and 0xff).toByte(); n = n ushr 8 }
            update(lenLE)
            update(data)
        }

        private fun sha512(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512").digest(data)

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }
    }
}
