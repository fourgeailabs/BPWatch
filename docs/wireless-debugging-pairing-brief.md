# Android 11+ Wireless Debugging: Pairing + TLS ADB — Implementation Brief

Researched 2026-09-20 for implementing `adb pair` / TLS ADB from scratch in
pure Java/Kotlin on Android (no native adb binary). Facts are drawn from AOSP
source references and third-party implementations verified against real
devices; guesses are flagged as such.

## 0. The two ports (different listeners!)

| | Pairing port | Connect (ADB) port |
|---|---|---|
| mDNS | `_adb-tls-pairing._tcp` (only while pair dialog open) | `_adb-tls-connect._tcp` |
| UI | "Pair device with pairing code" dialog | Wireless debugging screen "IP address and port" |
| TLS | **immediately** on TCP connect | after cleartext `CNXN` → `STLS` upgrade |
| Auth | 6-digit code via SPAKE2 | client X.509 cert (key registered at pairing) |

Both ports are random per enable; both change across reboots.

## 1. Pairing port flow (`adb pair <host>:<pairing_port>`)

### 1a. TLS 1.3 first
- TCP connect → TLS 1.3 handshake **immediately** (no ADB packets first).
- Client **presents a self-signed certificate**. The server does not verify it
  (self-signed, no PKI) — implementations use a throwaway RSA-2048 cert
  (wanctl) or reuse the app's ADB keypair (mapxr: "same RSA keypair used for
  pairing TLS client cert and PeerInfo" — either works).
- Client does **not** verify the server cert either (`InsecureSkipVerify`
  equivalent). TLS here provides **channel binding, not authentication**.
- **Java gotcha:** Android's platform `SSLSocket` does not expose
  `exportKeyingMaterial()` to apps (hidden API). You must use
  **`org.conscrypt:conscrypt-android` (2.5.x)** — the only Android TLS stack
  that exposes it: `Conscrypt.exportKeyingMaterial(socket, label, context,
  length)`. (Source: simcountry commit notes; libadb-android README
  recommends `org.conscrypt:conscrypt-android:2.5.3`.)
- **TLS exporter (RFC 5705/8446): label = `"adb-label\0"` — 10 bytes WITH the
  trailing NUL.** AOSP passes `sizeof(kExportedKeyLabel)` as the label length,
  and `sizeof` counts the terminator. Dropping the NUL yields valid-looking
  keying material that never matches the device's. Context = empty, output =
  **64 bytes**. (Sources: gong-mi AOSP_PAIRING_SUPPORTED.md checked against
  AOSP 36.0.1; wanctl `exportLabel = "adb-label\x00"`, verified vs device.)

### 1b. SPAKE2 password
- `password = ASCII(pairing_code) || exporter_bytes(64)`.
  The 6-digit code alone is NOT the password — the exporter binds the exchange
  to this TLS session so a relayed pairing can't be stolen. (wanctl, verified.)

### 1c. SPAKE2 parameters (BoringSSL variant — NOT RFC 9382)
- **BoringSSL `SPAKE2_CTX` over Curve25519/Edwards25519** (AOSP
  `pairing_auth/pairing_auth.cpp` → BoringSSL `crypto/curve25519/spake25519.c`).
  Standard SPAKE2 libraries (python-spake2, RFC 9382) will NOT interoperate.
- Roles: client = **Alice**, identity `"adb pair client\0"`; server = **Bob**,
  identity `"adb pair server\0"` — **with trailing NUL byte** (part of the
  compatibility surface; spake2-java README).
- `w = SHA-512(password)` used as the scalar (BoringSSL keeps raw scalar bit
  255 after the cofactor shift — spake2-java documents this quirk) **and**
  `SHA-512(password)` whole goes into the transcript hash.
- Transcript: role-dependent **Alice-then-Bob** ordering; **every field
  prefixed with a little-endian 8-byte length**. Fixed M/N generator points
  from BoringSSL's spake25519.c. (wanctl `internal/adb/spake2.go`.)
- Messages are **32-byte** masked public values.
- **Order: client sends its SPAKE2_MSG first, then reads the server's.**
  (wanctl `pair.go`, device-verified.)
- Shared-key output → HKDF-SHA256(salt = empty, info =
  `"adb pairing_auth aes-128-gcm key"`) → **16-byte AES key**.
  (gong-mi; wanctl.)
- **Java:** use `flyfish233/spake2-java`
  (https://github.com/flyfish233/spake2-java) — `Spake2Context(Spake2Role.Alice,
  "adb pair client\0", "adb pair server\0")`, `generateMessage()` /
  `processMessage()`; verified against pinned BoringSSL oracle vectors
  (29 JUnit tests). Alternative: `MuntashirAkon/spake2-java` (the original).

### 1d. Pairing packet framing
- 6-byte header: `version:u8 = 1`, `type:u8` (`0 = SPAKE2_MSG`, `1 = PEER_INFO`),
  `payload_length:u32` **big-endian**. Reject version != 1; cap payload at
  16384 bytes. (gong-mi; wanctl `writePairingPacket`/`readPairingPacket`.)

### 1e. PEER_INFO (encrypted with the SPAKE2-derived key)
- Cipher: **AES-128-GCM**. Nonce = 12 zero bytes with the **little-endian
  64-bit sequence counter in the first 8 bytes**, starting at 0, incremented
  per message; **separate counters per direction**; no AAD; nonce is **not**
  transmitted. (gong-mi; AOSP `pairing_auth/aes_128_gcm.cpp`; wanctl.)
- Plaintext payload is a **fixed 8192-byte struct**: `type:u8` (`0 =
  ADB_RSA_PUB_KEY`) || ASCII `"<base64(524-byte key)> <name>@<host>\n"` ||
  zero padding. **It is the TEXT line format, not the binary blob.**
  (halfmonty/mapxr `docs/adb-issues.md` — manually verified byte-for-byte.)
- The 524-byte ADB RSA public key layout (little-endian u32s):
  `len=64`, `n0inv`, `n[64]`, `rr[64]`, `e=65537` — exactly what
  `base64 -d adbkey.pub` yields. (mapxr; AOSP `crypto/rsa_2048_key.cpp`.)
- Client sends its PEER_INFO, then reads and decrypts the server's PEER_INFO
  (type `1 = ADB_DEVICE_GUID`). **A wrong pairing code shows up here**: the
  server's packet won't decrypt (or it hangs up). (wanctl.)

### 1f. What the client receives on success
- **Nothing new.** Pairing registers the client's *existing* public key on the
  device: adbd appends the key line to **`/data/misc/adb/adb_keys`** (same
  file as USB-authorized keys; survives reboot). The client keeps using its
  existing keypair (`~/.android/adbkey` on desktop; app-private storage on
  Android). (wanctl: "The key lands in /data/misc/adb/adb_keys.")

## 2. Connect port flow (`adb connect <host>:<adb_port>`)

Per the CVE-2026-0073 PoC (adbhijacker) and AOSP `protocol.txt` (`A_STLS`):

1. TCP connect, send cleartext ADB **CNXN** (`"host::features=..."`).
2. Server replies **`A_STLS`** (`"STLS"` = `0x534C5453`).
3. Client replies with `A_STLS`.
4. **TLS 1.3 handshake on the same TCP stream.** Client **MUST present a
   certificate**: a self-signed X.509 built from the **same RSA-2048 ADB
   keypair** that was paired (AOSP `crypto/x509_generator.cpp`; libadb-android
   README has a full Java example using RSA-2048 + self-signed cert via
   sun-security-android or BouncyCastle).
5. adbd verifies the client: extracts the public key from the client cert and
   compares it against `/data/misc/adb/adb_keys` — `adbd_tls_verify_cert()`
   in `daemon/auth.cpp` (this is the CVE-2026-0073 site; fixed May 2026
   bulletin to require `EVP_PKEY_cmp(...) == 1`). **Use an RSA key in the
   client cert** — presenting EC/Ed25519 keys is exactly the old bypass shape.
6. Inside the TLS tunnel, classic ADB runs **unchanged** (CNXN/OPEN/OKAY/WRTE/
   CLSE), with two wrinkles:
   - The **server sends its CNXN** after the TLS handshake; the client does
     **NOT** re-send CNXN (it already sent one in cleartext; re-sending kicks
     the connection — adbhijacker: "No host CNXN sent (would trigger
     handle_new_connection kick)").
   - **No AUTH step** — authentication happened via the TLS client cert.
   - Then `OPEN("shell:")` etc. work as normal.
- Client does not verify the server cert (self-signed; no hostname to check).
  All third-party implementations accept any server cert here. (Exact AOSP
  client-side pinning behaviour not verified from source in this research —
  treat trust-any as the working assumption, consistent with all prior art.)
- **Gotcha (simcountry, real device):** standalone Conscrypt's TLS 1.3
  client-auth once sent an *empty* Certificate message, tripping the server's
  `TLSV1_ALERT_CERTIFICATE_REQUIRED`; the platform-default `SSLContext`
  worked for the connect step. If the handshake fails on client-auth, retry
  the connect-step TLS on the platform socket while keeping Conscrypt for the
  pairing step (which needs the exporter API).

## 3. Samsung Galaxy Watch "SLES" (0x534C5345) — UNRESOLVED

When the app sent a plaintext ADB CNXN to the watch's wireless-debugging
connect port, the first 4 bytes back were `53 4C 45 53` ("SLES",
LE u32 `0x534C5345`) instead of an ADB packet. No source found identifying
this magic; web/code search turned up nothing ADB-related.

- It is **not** a TLS alert (those start `15 03 01`), and **not** AOSP's
  `A_STLS` (`"STLS"` = `0x534C5453`) — though it is suspiciously 2 bytes off
  from "STLS" (`54 4C` vs `4C 45` in bytes 2–3). Do **not** assume it means
  STLS; a bit-flip on TCP is not plausible.
- Hypotheses, in order: (a) Samsung vendor variant of the STLS upgrade
  command on their adbd fork; (b) only the first 4 bytes of a longer response
  were read and the rest would identify it; (c) the port belongs to a
  different Samsung service.
- **Recommended diagnostic:** read and log the first 64+ bytes (hex + ASCII)
  the port sends instead of just the u32; then send a TLS 1.3 ClientHello and
  observe the response. That will distinguish "vendor ADB upgrade command"
  from "TLS-speaking port" from "wrong service" immediately.

## 4. Prior art (from-scratch implementations)

- **MuntashirAkon/libadb-android** (Java; full pairing + TLS via Conscrypt;
  `AdbConnectionManager.getInstance().pair(host, port, pairingCode)`; dual
  GPL-3.0/Apache-2.0) — https://github.com/MuntashirAkon/libadb-android
- **flyfish233/spake2-java** (Java SPAKE2 core, BoringSSL-oracle verified) —
  https://github.com/flyfish233/spake2-java
- **daily-ac/wanctl** `internal/adb/pair.go` + `spake2.go` (Go, verified vs
  real device; the clearest protocol narrative) —
  https://github.com/daily-ac/wanctl/commit/487d6ac909a6f691fbb09c2668280bf528519e21
- **hyperramzey/root-my-galaxy** (Kotlin: `Spake2.kt`,
  `AdbPairingClient.kt` mirroring `pairing_connection.cpp`,
  `AdbKeyManager.kt` RSA-2048 + self-signed cert) —
  https://github.com/hyperramzey/root-my-galaxy/commit/397f09858ca42c68e1bc30e0280b1d19750a2cb3
- **gong-mi/adb-fastboot-rs** `AOSP_PAIRING_SUPPORTED.md` (protocol boundary
  doc checked against AOSP 36.0.1) —
  https://github.com/gong-mi/adb-fastboot-rs/blob/HEAD/AOSP_PAIRING_SUPPORTED.md
- **poapoauu/droidmux** (Rust; pairing + STLS upgrades) —
  https://github.com/poapoauu/droidmux
- **halfmonty/mapxr** `docs/adb-issues.md` (PeerInfo/RSAPublicKey formats,
  CNXN null-terminator gotcha, all byte-verified) —
  https://github.com/halfmonty/mapxr/blob/HEAD/docs/adb-issues.md
- **ahmedsakrr/adbhijacker** (CVE-2026-0073 PoC; documents the connect-port
  CNXN→STLS→TLS→OPEN sequence) — https://github.com/ahmedsakrr/adbhijacker
- **mobile-dev-inc/dadb** (Kotlin; pairing was long-standing gap #25 — check
  current state) — https://github.com/mobile-dev-inc/dadb
- **tytydraco/LADB** — bundles the real `adb` binary and shells out
  (`adb pair`/`adb connect`); architectural reference only, no from-scratch
  protocol code — https://github.com/tytydraco/LADB
- **adamoutler/adb-connect-qr** (Python; QR-code pairing variant,
  payload `WIFI:T:ADB;S:<name>;P:<password>;;`) — via
  https://xdaforums.com/t/connect-adb-with-qr-code.4784978/

## 5. AOSP source map

`platform/packages/modules/adb` (older trees: `platform/system/adb`);
pinned reference rev `1cf2f017d312f73b3dc53bda85ef2610e35a80e9`:

- `pairing_connection/pairing_connection.cpp`,
  `pairing_connection/include/adb/pairing/pairing_connection.h` — pairing state machine
- `proto/pairing.proto` — pairing message definitions
- `pairing_auth/pairing_auth.cpp`, `pairing_auth/aes_128_gcm.cpp` — SPAKE2 + GCM cipher
- `tls/tls_connection.cpp`, `tls/include/adb/tls/tls_connection.h` — TLS setup
- `daemon/auth.cpp` — `adbd_tls_verify_cert()` (CVE-2026-0073)
- `crypto/key.cpp`, `crypto/rsa_2048_key.cpp`, `crypto/x509_generator.cpp` — ADB key + self-signed cert
- `docs/dev/adb_wifi.md`, `adb_mdns.cpp`, `client/adb_wifi.cpp` — wireless debugging, mDNS, client CLI
- `adb.h` (`A_STLS = 0x534C5453`), `transport.cpp`, `sockets.cpp` — transport + STLS upgrade

## 6. Java implementation checklist

1. Keep the existing RSA-2048 adbkey; add self-signed X.509 generation
   (BouncyCastle; see libadb-android README's `X509CertImpl` example).
2. **Pair:** Conscrypt TLS 1.3 socket → pairing port (trust-all server cert,
   present client cert) → `exportKeyingMaterial("adb-label\0", null, 64)` →
   `password = code_ascii || exporter` → spake2-java as Alice (names with
   trailing NUL) → send 32-byte msg framed `[0x01, 0x00, len:u32 BE]` →
   read server SPAKE2_MSG → shared key → HKDF-SHA256(info
   `"adb pairing_auth aes-128-gcm key"`, 16 bytes) → AES-128-GCM, nonce =
   `LE64(seq)` in 12 zero bytes, per-direction seq from 0 → send PEER_INFO =
   `0x00 || "<base64(524B key)> <name>@<host>\n"` zero-padded to 8192 →
   read + decrypt server PEER_INFO (validates the code).
3. **Connect:** TCP → cleartext CNXN → expect `STLS` → reply `STLS` → TLS 1.3
   presenting the client cert (fall back to platform `SSLContext` if
   Conscrypt client-auth misbehaves) → read server CNXN inside TLS →
   `OPEN("shell:")` directly (no re-CNXN, no AUTH).
4. Watch for: the exporter label's trailing NUL; LE (not BE) nonce counter;
   SPAKE2 write-then-read; PEER_INFO is the *text* key line; RSA (not EC)
   client cert on connect.

## Open / unverified items

- The "SLES" magic (Section 3) — needs a byte dump from the watch port.
- Whether AOSP's own adb client pins/TOFUs the server cert on connect
  (all prior art uses trust-any; works in practice).
- Server-side SPAKE2 read/write ordering in AOSP (write-then-read is
  device-verified via wanctl; safe to follow).
- Current contents of `proto/pairing.proto` (current implementations use the
  raw 6-byte framing; the proto file's role is unclear from this research).
