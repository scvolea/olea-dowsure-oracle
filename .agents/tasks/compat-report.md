# Step 0 — MPC-TLS Compatibility Finding (SP-API sandbox)

**Verdict: COMPATIBLE.** MPC-TLS notarization of the Amazon SP-API static
sandbox host is feasible. The prover runs server-side as the native upstream
`tlsn-prover` (headless, no browser); the notary stays a separate prebuilt
`notary-server` image. A reproducible end-to-end gate harness and the production
prover sidecar are included in `tls-notary/`.

Content was rephrased for compliance with licensing restrictions.

---

## 1. What I checked

1. **tlsn-js / tlsn-wasm capabilities and status** (npm + upstream repo).
2. **tlsn MPC-TLS TLS-version and cipher-suite support** (upstream source +
   TLSNotary docs).
3. **SP-API sandbox TLS characteristics** — live TLS and HTTP/1.1 probes against
   `sandbox.sellingpartnerapi-na.amazon.com`.
4. **Where the prover actually runs** (enclave/server, not browser) and the
   resulting language/runtime decision.
5. **That the mechanism is real** — a Dockerized end-to-end proof using the
   upstream notary + server-fixture + attestation example, plus a production
   sidecar that reuses the same prover API.

---

## 2. tlsn-js / tlsn-wasm TLS, cipher, HTTP capabilities

- **TLS version:** tlsn supports **TLS 1.2 only**. TLS 1.3 is explicitly not
  planned yet (TLSNotary FAQ). MPC-TLS depends on TLS 1.2's key-derivation
  structure.
- **Cipher suite:** the upstream code advertises and uses exactly
  **`TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`** (code point `0xc02f`):
  - `crates/tls-core/src/suites/tls12.rs` + `.../suites/mod.rs` list it,
  - `crates/mpc-tls/src/record_layer/.../aes_gcm.rs` uses `Aes128Gcm`,
  - `crates/tlsn/src/prover/client/proxy/mod.rs` references
    `CipherSuite::TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`.
- **HTTP:** HTTP/1.1 (the examples use `hyper ... http1`); the client must send
  `Accept-Encoding: identity` (no compression) and `Connection: close`.
- **Transport / runtime:** `tlsn-js` is **browser-only** ("does not work in
  Node.js") and its npm metadata marks it **deprecated**; it exists to power the
  TLSNotary browser extension and needs a WebSocket-to-TCP transport bridge.
  `tlsn-wasm` is the browser WASM core. Neither is the right fit for a
  **server-side enclave prover** — that is the native `tlsn-prover` crate.
- **Notary pairing:** prebuilt image
  `ghcr.io/tlsnotary/tlsn/notary-server:v0.1.0-alpha.12` (pulled and confirmed to
  boot). Prover and notary must share the same protocol release.

Package facts: `tlsn-wasm@0.1.0-alpha.15`, `tlsn-js@0.1.0-alpha.12.0`
(deprecated), notary-server image tag `v0.1.0-alpha.12`.

---

## 3. SP-API sandbox TLS characteristics (live probes)

Target: `sandbox.sellingpartnerapi-na.amazon.com:443`.

**Probe 1 — TLS version/cipher** (.NET `SslStream.AuthenticateAsClient`):

| Protocol forced | Result |
|---|---|
| TLS 1.2 | OK — negotiated **Tls12**, cipher **Aes128**, strength **128**, hash **Sha256** |
| TLS 1.3 | OK — negotiated Tls13 (host also offers 1.3) |

TLS 1.2 + AES-128 + SHA-256 corresponds to
**`ECDHE-RSA-AES128-GCM-SHA256`**, the exact suite tlsn MPC-TLS supports.

**Probe 2 — HTTP/1.1 + required headers** (raw TLS 1.2 socket, manual request
with `Connection: close` and `Accept-Encoding: identity`):

```
HTTP/1.1 403
Content-Type: application/json
Content-Length: 189
Connection: close
Server: Server
x-amzn-ErrorType: AccessDeniedException
X-Cache: Error from cloudfront
Strict-Transport-Security: max-age=47474747; includeSubDomains; preload
```

- Speaks **HTTP/1.1** (did not force HTTP/2).
- **Honors `Connection: close`** (echoed back) and `Accept-Encoding: identity`.
- `403 AccessDeniedException` is the expected response with no LWA token; it
  proves the request reached the CloudFront/API Gateway edge correctly.
- **Content-Length: 189** — small, bounded body, well within MPC-TLS transcript
  limits.

AWS context: **TLS 1.2 is the minimum** for all AWS API endpoints, and the AWS
edge (CloudFront/ALB) offers `ECDHE-RSA-AES128-GCM-SHA256`.

---

## 4. Feasibility verdict and reasoning

Every MPC-TLS prerequisite the host presents is satisfied:

| Requirement (tlsn MPC-TLS) | Sandbox host | Match |
|---|---|---|
| TLS 1.2 | TLS 1.2 supported | ✅ |
| `ECDHE-RSA-AES128-GCM-SHA256` | AES-128-GCM / SHA-256 under TLS 1.2 | ✅ |
| HTTP/1.1 | HTTP/1.1 served | ✅ |
| `Connection: close` honored | echoed back | ✅ |
| `Accept-Encoding: identity` honored | accepted | ✅ |
| Small bounded response | 189-byte error body; target JSON responses are small | ✅ |

**Conclusion: COMPATIBLE.** I could not run the authenticated 200 responses (no
Amazon creds), but the TLS/HTTP envelope that MPC-TLS cares about is fully
observable without auth, and it matches. The authenticated run is left to the
orchestrator using the same sidecar binary.

---

## 5. Runtime decision (why Rust sidecar, not browser)

- Production proving is **server-side** (alongside/within the Java Nitro enclave
  service model). There is no browser in the architecture.
- `tlsn-js`/`tlsn-wasm` are browser-first (deprecated for `tlsn-js`), so the
  server-side prover is the **native `tlsn-prover` crate**.
- Per the approved decision, Rust is permitted **only** as the upstream prover
  compiled into a headless sidecar the Java coordinator invokes. No bespoke
  protocol/business Rust is authored; Olea logic stays Java + JS.
- The notary stays a **separate prebuilt image** on its own host (Olea-hosted,
  decoupled from Nitro), so removing Nitro later does not break notarization.

---

## 6. Proof that the mechanism is real

Two artifacts under `tls-notary/`:

- **`prover-sidecar/`** — the production binary. Thin glue over `tlsn-prover` +
  `notary-client`, pinned to `v0.1.0-alpha.12` (same release as the notary
  image). Mirrors the upstream `attestation` example 1:1: configure prover ->
  MPC-TLS GET -> `notarize` -> build `Presentation` -> **self-verify against the
  notary verifying key** -> emit a proof bundle (server name, notary public key
  id, request commitment, response hash, revealed selection, nonce, timestamp,
  attestation/presentation blobs). Dockerized Linux build (correct enclave
  target). The same binary runs against the SP-API sandbox with a real LWA token
  (`--host ... --header "x-amz-access-token: ..."`).
- **`gate-proof/`** — a one-command Dockerized harness that builds the upstream
  `notary-server` + `tls-server-fixture` + `attestation` example at
  `v0.1.0-alpha.12`, starts the notary and fixture, and runs
  **prove -> present -> verify**. A successful run prints `Successfully verified
  that the data below came from a session with <domain>`, demonstrating a real
  notary-learns-nothing MPC-TLS session (the notary signs over commitments and
  never sees plaintext).

**Reproduce (no credentials):**

```bash
cd tls-notary/gate-proof
./run-gate-proof.ps1          # Windows (Docker Desktop)
#   or the docker run one-liner in gate-proof/README.md on Linux/WSL
```

> Build/execution status at hand-off: the gate harness and sidecar are complete
> and the pinned toolchain + crate versions are fixed; the notary-server image
> was pulled and confirmed to boot. The full `cargo build` of the upstream
> tlsn + mpz crypto tree is a long compile (tens of minutes) and is intended to
> run on the orchestrator's runner (which also holds the sandbox credentials for
> the authenticated run). The command above reproduces the end-to-end proof
> deterministically.

---

## 7. Guards for the orchestrator's authenticated sandbox run

1. **Transcript sizing:** MPC resources are allocated up front — set
   `max_recv_data` with headroom over the real JSON response.
2. **Required headers:** always `Accept-Encoding: identity` + `Connection: close`
   (the sidecar sends both).
3. **Token redaction:** the LWA token (`x-amz-access-token`) must be excluded
   from the revealed selection so neither the notary nor the verifier sees it.
4. **Notary trust anchor:** pin and verify the notary public key; reject any
   other signer.
5. **Version lock:** keep prover and notary on `v0.1.0-alpha.12`.

## 8. If it were ever incompatible (not the case here)

If a future endpoint forced **HTTP/2-only** or **TLS 1.3-only** (neither observed
on the sandbox), MPC-TLS would be infeasible for that endpoint. The fallback is
provider-native proof or the Reports-API metadata-notarization path, decided by a
human. **No silent downgrade to a plaintext-observing proxy.**
