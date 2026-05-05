/*
 * SPAKE2-25519 — pure Kotlin port of BoringSSL `spake25519.c`, wire-compatible with AOSP's
 * adb pair daemon (which links the same BoringSSL implementation). Replaces the previous
 * JNI dep `MuntashirAkon/spake2-android` (LGPL-3.0). Group arithmetic via `net.i2p.crypto:eddsa`
 * (CC0); the SPAKE2 protocol itself is implemented here. See header on the upstream C source
 * and `projects/active/browser/cn/diag_20260504_spake_alternatives.md` for context.
 *
 * Upstream reference: https://github.com/MuntashirAkon/spake2-c/blob/master/spake2.c
 * Algorithm (Alice; Bob symmetric with M ↔ N): generate sends Pstar = x·Base + w·M where
 * x = (rand mod L)·8 and w = SHA-512(password) mod L with the BoringSSL "password scalar
 * hack" (add δL, δ∈{0..7}, to clear low 3 bits). Process receives Qstar, computes
 * dh = x·(Qstar − w·N) (cofactor cleared because x is a multiple of 8), then returns the
 * 64-byte SHA-512 transcript SHA512(LP(my_name)||LP(their_name)||LP(my_msg)||LP(their_msg)
 * ||LP(dh)||LP(password_hash)) where LP(x)=uint64_le(len(x))||x (Bob swaps name/msg order).
 *
 * SPDX-License-Identifier: ISC
 *
 * Copyright (c) 2020, Google Inc.
 * Copyright (c) 2021, Muntashir Al-Islam
 *
 * Permission to use, copy, modify, and/or distribute this software for any purpose with or
 * without fee is hereby granted, provided that the above copyright notice and this permission
 * notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO
 * THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT
 * SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR
 * ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION
 * OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH
 * THE USE OR PERFORMANCE OF THIS SOFTWARE.
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
    private lateinit var passwordHash: ByteArray   // SHA-512(password); 64 bytes; transcript input
    private lateinit var privateKeyDiv8: ByteArray // (private_key / 8) mod L; LE 32B
    private lateinit var passwordScalarDiv8: ByteArray // (password_scalar_hacked / 8); LE 32B
    private lateinit var myMsg: ByteArray          // encoded Pstar; 32 bytes; transcript input

    private enum class State { INIT, MSG_GENERATED, KEY_GENERATED }

    /** @return 32-byte encoded SPAKE2 message to send to the peer. */
    fun generateMessage(password: ByteArray): ByteArray {
        check(state == State.INIT) { "generateMessage called twice or after processMessage" }
        privateKeyDiv8 = scalarReduce64(ByteArray(64).also { random.nextBytes(it) })
        val pPoint = scalarMul(privateKeyDiv8, base8Point)  // = privateKeyDiv8·(8·Base) = (8·privateKeyDiv8)·Base
        passwordHash = sha512(password)
        passwordScalarDiv8 = passwordScalarHackDiv8(scalarReduce64(passwordHash.copyOf()))
        val mask = scalarMul(passwordScalarDiv8, if (role == Role.ALICE) m8Point else n8Point)
        val pstar = pPoint.add(mask.toCached()).toP3()
        myMsg = pstar.toByteArray()
        state = State.MSG_GENERATED
        return myMsg.copyOf()
    }

    /** @return 64-byte SPAKE2 transcript hash (SHA-512). Caller derives session keys via HKDF. */
    fun processMessage(theirMsg: ByteArray): ByteArray {
        check(state == State.MSG_GENERATED) { "processMessage requires generateMessage first" }
        require(theirMsg.size == 32) { "SPAKE2 message must be 32 bytes" }
        val qstar = curve.createPoint(theirMsg, false)
        val peersMask = scalarMul(passwordScalarDiv8, if (role == Role.ALICE) n8Point else m8Point)
        val qExt = qstar.sub(peersMask.toCached()).toP3()
        // private_key·Q_ext = privateKeyDiv8·(8·Q_ext); 8·Q_ext clears Q_ext's cofactor.
        val dhSharedEncoded = scalarMul(privateKeyDiv8, mul8(qExt)).toByteArray()
        val sha = MessageDigest.getInstance("SHA-512")
        if (role == Role.ALICE) {
            sha.updateLP(myName); sha.updateLP(theirName); sha.updateLP(myMsg); sha.updateLP(theirMsg)
        } else {
            sha.updateLP(theirName); sha.updateLP(myName); sha.updateLP(theirMsg); sha.updateLP(myMsg)
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

    companion object {
        private val curveSpec = EdDSANamedCurveTable.ED_25519_CURVE_SPEC
        private val curve = curveSpec.curve
        private val scalarOps = curveSpec.scalarOps
        private val baseB: GroupElement = curveSpec.b

        // Ed25519 prime-order subgroup order L = 2^252 + 27742317777372353535851937790883648493
        private val L: BigInteger = BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")

        // Extracted from BoringSSL kSpakeMSmallPrecomp / kSpakeNSmallPrecomp row 0 (which encodes
        // 1·M / 1·N per the table doc-comment): bytes [0..32]=field-element x, [32..64]=field-
        // element y. RFC-8032 compressed = LE y with top bit of byte 31 = LSB(x). Both have x_lsb=0.
        private val M_ENCODED = hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
        private val N_ENCODED = hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")

        // 8·M, 8·N, 8·Base in the prime-order subgroup. Multiplying by `*Div8` instead of the
        // raw scalar lets us stay strictly < L and avoids edge cases in the scalar's top bit.
        private val m8Point: GroupElement = mul8(curve.createPoint(M_ENCODED, false))
        private val n8Point: GroupElement = mul8(curve.createPoint(N_ENCODED, false))
        private val base8Point: GroupElement = mul8(baseB)

        /** 3 doublings = ×8. Cheaper than scalarMultiply with scalar 8. */
        private fun mul8(p: GroupElement): GroupElement = p.dbl().toP3().dbl().toP3().dbl().toP3()

        /**
         * LSB-first double-and-add scalar mult. eddsa's `GroupElement.scalarMultiply` requires a
         * precomputed lookup table that is only set up for `createPoint(_, true)` points; per-
         * handshake mask/peer points have none, so we add directly via dbl()/add(cached)/toP3().
         * Variable-time per scalar bit — acceptable for a single-pair handshake on a personal device.
         */
        private fun scalarMul(scalarLE: ByteArray, point: GroupElement): GroupElement {
            require(scalarLE.size == 32)
            var result = curve.getZero(GroupElement.Representation.P3)
            var addend = point
            for (i in 0 until 32) {
                val b = scalarLE[i].toInt() and 0xff
                for (j in 0 until 8) {
                    if ((b ushr j) and 1 == 1) result = result.add(addend.toCached()).toP3()
                    addend = addend.dbl().toP3()
                }
            }
            return result
        }

        /** Reduce a 64-byte LE integer mod L → 32-byte LE, < L. */
        internal fun scalarReduce64(x: ByteArray): ByteArray {
            require(x.size == 64); return scalarOps.reduce(x)
        }

        /**
         * BoringSSL's "password scalar hack": original cofactor-clear pass omitted the ×8 multiply
         * after `x25519_sc_reduce` (copy-paste error). Compatibility fix: add δL (δ ∈ {0..7})
         * picked from the low 3 bits so the result becomes a multiple of 8 while staying ≡ s (mod L).
         * We then divide by 8 to keep the scalar < L (avoids eddsa's < 2^255 scalarmult ceiling).
         */
        internal fun passwordScalarHackDiv8(scalarLE32: ByteArray): ByteArray {
            require(scalarLE32.size == 32)
            var s = BigInteger(1, scalarLE32.reversedArray())  // BigInteger wants big-endian
            if (s.testBit(0)) s = s.add(L)
            if (s.testBit(1)) s = s.add(L.shiftLeft(1))
            if (s.testBit(2)) s = s.add(L.shiftLeft(2))
            check(s.and(BigInteger.valueOf(7)).signum() == 0) { "password-hack failed to clear low 3 bits" }
            return encodeScalar32LE(s.shiftRight(3))
        }

        internal fun encodeScalar32LE(value: BigInteger): ByteArray {
            val be = value.toByteArray()  // big-endian; may have leading 0x00 sign byte
            val srcStart = if (be.isNotEmpty() && be[0] == 0.toByte() && be.size > 32) 1 else 0
            val srcLen = (be.size - srcStart).coerceAtMost(32)
            return ByteArray(32).also { for (i in 0 until srcLen) it[i] = be[be.size - 1 - i] }
        }

        private fun MessageDigest.updateLP(data: ByteArray) {
            val lenLE = ByteArray(8)
            var n = data.size.toLong()
            for (i in 0 until 8) { lenLE[i] = (n and 0xff).toByte(); n = n ushr 8 }
            update(lenLE); update(data)
        }

        private fun sha512(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512").digest(data)

        private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
            ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
        }
    }
}
