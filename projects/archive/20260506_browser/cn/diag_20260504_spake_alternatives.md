# SPAKE2-25519 alternatives — investigation result

**Date**: 2026-05-04
**Task**: `browser-phase6-spake-replace` — drop `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1` (LGPL via JitPack, not a major library) and replace with a popular-lib equivalent.
**Hard cap**: 30 min for investigation phase.

## Path 1 (BEST): popular lib with high-level SPAKE2-25519 wire-compat with AOSP

**Result: NONE EXIST.**

| Candidate                      | Version | Has SPAKE2? | Wire-compat? | Verdict |
|--------------------------------|---------|-------------|--------------|---------|
| **BouncyCastle bcprov-jdk18on**| 1.78–1.84 | NO. `org.bouncycastle.crypto.agreement` has `jpake/`, `ecjpake/`, `srp/` — but no SPAKE2 of any kind. Confirmed via GitHub Contents API of `bcgit/bc-java/core/src/main/java/org/bouncycastle/crypto/agreement` and via the published Javadoc. | n/a | OUT |
| **Google Tink (`tink-java`)** | 1.x | NO. `com.google.crypto.tink.subtle` lists ChaCha20Poly1305, Ed25519Sign/Verify, Hkdf, AesGcmJce, etc. No SPAKE/PAKE class anywhere. Last verified by listing repo contents. | n/a | OUT |
| **lazysodium-android (com.goterl)** | 5.1.x | NO high-level SPAKE2. The libsodium upstream supports SPAKE2+EE only via an external example; the lazysodium Java JNI surface (`com.goterl.lazysodium.Sodium`) exposes Ristretto255 + X25519 only — **no `crypto_scalarmult_ed25519` / `crypto_core_ed25519_add/sub`** bindings, so even building SPAKE2 over libsodium primitives via lazysodium is not possible without forking it. | n/a | OUT |

Conclusion: path 1 is empty. There is no popular Java/Android library that ships AOSP-wire-compatible SPAKE2-25519 with `adb pair client\0` / `adb pair server\0` names, SHA-512 transcript, and 64-byte key output. Move to path 2.

## Path 2 (FALLBACK): popular lib with the right primitives + write SPAKE2 glue

The AOSP / BoringSSL `spake25519.c` protocol (per `MuntashirAkon/spake2-c/spake2.c`, the upstream of the lib we're dropping) needs:

1. Ed25519 **group element arithmetic** — scalar multiplication of arbitrary points, scalar mult of base, point addition, point subtraction (cofactor-8 group, *not* the prime-order Ristretto subgroup).
2. SHA-512 — for password hash and transcript.
3. HKDF-SHA256 + AES-128-GCM — for the post-SPAKE2 KDF + record encryption (already in deps via BC).

Candidate libs offering Ed25519 raw point arithmetic (Java public API):

| Candidate | Public point API | License | Maven dependents | Verdict |
|-----------|------------------|---------|------------------|---------|
| **net.i2p.crypto:eddsa:0.3.0** (str4d/ed25519-java) | `GroupElement.add(q)`, `sub(q)`, `negate()`, `scalarMultiply(byte[])`, encode/decode 32-byte RFC-8032 form, `Curve` exposes base point and field. | CC0 1.0 (public domain) | **25,600+** | **PICK** |
| BouncyCastle internal `org.bouncycastle.math.ec.rfc8032.Ed25519` | Only `sign/verify/generatePublicKey` are public; `PointAccum`/`PointAffine`/scalarMult internals are package-private. Reflection hack required. | MIT | huge, but not exposed | OUT (not public API) |
| lazysodium-android | Ristretto255 only — wrong group (cofactor cleared). | MPL-2.0 | many | OUT |

`net.i2p.crypto:eddsa` is on Maven Central, has 25k+ dependents (more popular than the spake2-java JitPack lib by orders of magnitude), uses the most permissive license possible (CC0), and exposes the exact arithmetic we need on its public `GroupElement` API. Last release 0.3.0 (2018) is acceptable: EdDSA / RFC-8032 is a frozen standard, no security CVEs, and the API surface we use (point add/sub/scalarmult) is mathematically stable.

## Decision

**Path 2 with `net.i2p.crypto:eddsa:0.3.0`.**

- Add `net.i2p.crypto:eddsa:0.3.0` to `app/build.gradle.kts`.
- Drop `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1`.
- Drop the JitPack repo from `settings.gradle.kts` (it was only there for spake2-android — verified by grepping repo for other JitPack-coordinate deps; none).
- Write `app/src/main/kotlin/ai/closepaw/browser/cdp/wireless/Spake25519.kt` (~150 lines): SPAKE2-25519 over Ed25519 with the AOSP variant (random scalar × cofactor 8, password-scalar low-3-bit fix, length-prefixed SHA-512 transcript, 64-byte output), wire-compatible with `BoringSSL/spake25519.c`.
- M and N points: extracted from `kSpakeMSmallPrecomp` / `kSpakeNSmallPrecomp` row-0 (which encodes `1 × M` and `1 × N` per the table comment) and re-encoded as 32-byte RFC-8032 compressed points. (See file header for the derivation.)
- Adapt `AdbPairingClient.kt` to call `Spake25519` instead of `Spake2Context`.
- JVM unit test: self-interop (Alice + Bob, same password ⇒ same 16-byte key after HKDF; wrong password ⇒ different keys).
- Wire-compat verification = the existing real-device cold-pair on nubia (the device's `adbd` runs the BoringSSL SPAKE2; if our message exchange completes and we get a usable mTLS session, wire is compatible).

License benefit: CC0 (public domain) replaces LGPL-3.0 (weak copyleft) — strictly more permissive, no longer relies on the LGPL §4(d) dynamic-linking carve-out.

## Verification (2026-05-04)

**Build**: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint` all green at HEAD.
- Spake25519Test: 4/4 tests pass on host JVM:
  - `same password yields same 64-byte key on both sides` — Alice + Bob with matching password derive identical 64-byte SHA-512 transcript.
  - `different passwords yield different keys` — Alice + Bob with non-matching passwords derive different keys.
  - `password scalar hack clears low 3 bits and divides by 8`.
  - `scalarReduce64 returns 32 bytes`.
- AdbPairingClientTest (existing): all green (HKDF, AES-GCM, IV layout, name/label byte counts unchanged).
- APK contents: `libspake2.so` is gone from `lib/{arm64-v8a,…}/`; `Lai/closepaw/browser/cdp/wireless/Spake25519` and `Lnet/i2p/crypto/eddsa/…` classes are bundled; `io/github/muntashirakon/crypto/spake2` is fully absent. JitPack repo removed from `settings.gradle.kts`.

**Real device** (nubia P0110, 100.64.43.95:5555, fresh keystore wiped via `run-as ai.closepaw rm -rf files/adb_self_pair`):

```
WirelessAdbSelfPair: wireless-adb bootstrap: starting
WirelessAdbSelfPair: wireless-adb bootstrap: (a) enableWirelessDebugging ok
WirelessAdbSelfPair: wireless-adb bootstrap: (b) tls port = 41089
WirelessAdbSelfPair: wireless-adb pairing complete; future runs reuse persisted key
WirelessAdbSelfPair: wireless-adb bootstrap: complete
```

→ Cold pair completed end-to-end in ~1.4 s. The persisted keystore was re-created on disk (`cert.x509.der`, `private.pkcs8.der`, `.install-complete` sentinel under `/data/data/ai.closepaw/files/adb_self_pair/`), confirming `adbd` accepted our `PeerInfo` (which is encrypted with the AES-128-GCM key derived via HKDF-SHA256 from the SPAKE2 64-byte transcript). Wire-compat with AOSP `adbd`'s BoringSSL spake25519 is therefore proven by the only test that actually exercises wire-format: a fresh handshake against the real daemon.

The follow-on `Example Domain` HTTP exchange did not complete in this run because the device was on the lockscreen (user lock pattern, cannot be dismissed remotely without the credential). The post-pair `SocketTimeoutException: Read timed out` originates in `AdbWireProtocolClient.readUntilHttpComplete` reading from the local-abstract relay socket while Chrome was not foregrounded — entirely environmental, unrelated to the SPAKE2 swap. The Phase 4 PASS recorded in `diag_20260503_phase4_handoff.md` was on the same device with the screen unlocked + Chrome foregrounded.

## Post-rebase verification (2026-05-04, after merging main)

After rebasing onto main HEAD `6dc2af3a` (which advanced the branch with BC 1.84 bump, storeArtifact quota, ws-idle reset, target-leak per-LiveConnection / switchMutex, and relay token-gate / slowloris deadline), the same gates were re-run:
- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint` — all green.
- Spake25519Test 8/8 (added: pinned KAT vectors, processMessage rejects non-32-byte input, processMessage rejects malformed point bytes, state machine prevents misuse).
- Real-device cold-pair on nubia (cumulative: BC 1.84 + token-gate + per-LiveConnection + Spake25519): same `wireless-adb pairing complete` log + persisted keystore re-created in 1.4 s. Post-pair behavior unchanged from pre-rebase run (lockscreen still blocks Chrome foregrounding).

The crypto core (M/N extraction, password-scalar hack, cofactor algebra) is unchanged from the pre-review commit; rebase touched only `app/build.gradle.kts` (3-way auto-merge: main's BC 1.84 + branch's eddsa add + spake2-android removal).
