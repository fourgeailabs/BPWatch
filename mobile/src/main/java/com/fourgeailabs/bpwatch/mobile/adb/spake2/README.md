# SPAKE2 for ADB pairing

This directory holds our SPAKE2 implementation for the ADB wireless-debugging
pairing handshake.

## History

We first vendored `MuntashirAkon/spake2-java` (a Java port of BoringSSL's
`spake25519.c`, LGPL-3.0). Its Ed25519 scalar multiplication turned out to be
broken — `1*G != G` — and the upstream repo fails its own key-agreement test.
Rather than debugging someone else's field arithmetic, we implemented the
small subset of Ed25519 that SPAKE2 needs from scratch, directly from
RFC 8032.

## What lives here

- `Ed25519Spake.kt` — minimal Edwards25519 arithmetic on BigInteger:
  point decode/encode, addition, scalar multiplication, scalar reduction,
  and BoringSSL's fixed SPAKE2 points M and N (derived from
  SHA-256("edwards25519 point generation seed (M|N)")), plus BoringSSL's
  password-scalar construction (SHA-512, reduce mod L, clear low 3 bits).
- `Spake2.kt` — the SPAKE2 protocol: message generation, peer-message
  processing, and the BoringSSL key-derivation transcript
  (length-prefixed names, messages, DH secret, password hash, SHA-512).

The protocol parameters (names `"adb pair client\0"` / `"adb pair server\0"`,
password = ASCII(code) || 64-byte TLS exporter, HKDF info string, AES-GCM
framing) match AOSP's `adb` pairing client and BoringSSL's `spake25519.c`,
which is what the watch's adbd runs.

## Licence

`Ed25519Spake.kt` and `Spake2.kt` are original code written for BPWatch.
No third-party SPAKE2 code remains in the tree.
