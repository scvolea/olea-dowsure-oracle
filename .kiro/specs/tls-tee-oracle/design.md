# Design — Convert the oracle to TLS-in-TEE (7 calls)

## Overview

This converts the live evidence path from **notary-witnessed MPC-TLS** to
**enclave-terminated TLS**. Today the response bytes arrive at the enclave as a
caller-supplied `rawResponseB64` plus a `tlsProof` produced by an external Rust
prover sidecar and an Olea-hosted notary; the enclave checks the proof and binds its
hash into the attestation. After this change, the **enclave itself opens the TLS
connection** to each source, so the response is decrypted only inside the enclave,
and the notary/`tlsProof` leaves the live path entirely. The host becomes a dumb byte
relay (vsock → outbound TCP). Everything else — attestation, the Olea verifier, PCR
registration, the Object-Lock vault, the challenge/nonce model, canonical hashing,
and the evidence/manifest field set — is **kept** and only minimally adjusted to drop
the notary fields and add source selection.

Scope is a working solution for exactly the **7 source calls** in
[requirements.md](./requirements.md). No abstractions beyond what those 7 need.

### Why this is a conversion, not a rebuild

The current chain already proves the hard part (a real Nitro enclave producing a
verified attestation Olea accepts). The only trust-anchor swap is: instead of a
notary witnessing a session the enclave did not control, the **enclave's own
measured code** is the thing that both fetched the bytes and attests to them. The
same-bytes hash (`SHA256(rawResponseB64) == rawPayloadDigest`) stays as the integrity
bridge; what disappears is the separate notary hash to compare it against — the
enclave's TLS termination under attestation *is* the origin anchor.

---

## Current state (verified against the code)

| Component | File | Today | Change |
|---|---|---|---|
| Enclave entry | `nitro-enclave/.../EnclaveMain.java` | vsock server, CID 16 / port 5005; one handler → `EnclaveService.acquire` | unchanged transport; handler input shape changes |
| Enclave logic | `nitro-enclave/.../EnclaveService.java` | hardcodes `SOURCE="mock-api"`, `ENDPOINT="GET_ORDERS"`; requires `rawPayload`,`rawResponseB64`,`tlsProof`; validates `tlsProof` (`proofType`,`proofHash`,`responseHash`); `rawHash=SHA256(decode(rawResponseB64))`; gate `rawHash==tlsProof.responseHash`; `user_data` binding includes `tlsProofHash`; manifest includes `tlsProofType`,`tlsProofHash` | enclave **fetches over TLS**; registry dispatch replaces the hardcoded scope; drop `tlsProof` requirement + gate; `user_data` gains `sourceId`, drops `tlsProofHash`; manifest drops `tlsProof*` |
| Coordinator | `coordinator/.../CoordinatorMain.java`, `Coordinator.java`, `VsockEnclaveClient.java` | threads `--raw-payload-file`,`--raw-response-b64-file`,`--tls-proof-file` into the enclave request | stops supplying response bytes + proof; supplies **sourceId** (+ POST body for call #7) and the per-call credential handle |
| Source registry (reference) | `coordinator/sources.py` | Python: 7 calls, per-provider base URLs, auth header construction, mock/sandbox mode, Amazon sandbox fixture params | **ported into the enclave (Java)** as the authoritative in-enclave registry |
| Verifier | `sam/olea/functions/verification/index.js` | `required[]` includes `tlsProofType`,`tlsProofHash`,`tlsProofResponseHash`,`tlsProof`; hardcodes `source==='mock-api' && endpoint==='GET_ORDERS'`; calls `verifyTlsNotaryProof` + binds `tlsProofHash` in `verifyNitroAttestation` | drop notary fields from `required[]`; registry-aware source check; remove notary verify + the `tlsProofHash` arg from the `user_data` binding |
| Attestation verify | `.../attestation-verifier.js`, `verification-contract.js`, `tlsnotary-verifier.js` | COSE/chain/PCR/user_data/key checks; notary verify in a separate module | keep attestation path; the notary module is no longer called on the live path |
| EIF build | `nitro-enclave/Dockerfile` | Java 21 jar + `libnsm.so` into `amazonlinux:2023` | add the enclave CA bundle; rebuild ⇒ new PCRs ⇒ re-register |

---

## Architecture (after conversion)

```text
                         Dowsure-hosted Nitro host (EC2)
                     +---------------------------------------+
   Olea Challenge    |  parent process        Nitro Enclave  |
   (nonce)  ───────► |  (coordinator)   vsock  (sealed)       |
                     |     │  sourceId+creds ─────► TLS client │────TLS───► Provider
                     |     │                 ◄───── response   │   (SP-API / KYC host
                     |     │  byte relay (vsock↔TCP, no plaintext)   or deployed mock)
                     |     ▼                                   |
                     |  evidence  ◄── attestation+signature ───┘
                     +-----------│-------------------------------+
                                 ▼
                 Olea Verifier  →  attestation + same-bytes + nonce  →  202 + Object-Lock vault
```

Two data paths cross the enclave boundary:
1. **Control** (vsock request): `requestId, nonce, policyVersion, evidenceId, eifDigest,
   sourceId, requestBody?(for POST), credentialHandle`.
2. **Byte relay** (vsock ↔ outbound TCP): the enclave's TLS record stream to the
   provider. The host forwards ciphertext only.

### Outbound network path (Requirement 1, 9.1)

The enclave has no NIC. Outbound TLS works via a **host-side TCP relay** bound to a
vsock port: the enclave opens a socket to the parent over vsock, the parent proxies
raw bytes to `host:443`. This is the standard `vsock-proxy` pattern (AWS ships
`vsock-proxy`; a minimal Java/host relay also suffices for the PoC). The enclave runs
a normal TLS 1.2/1.3 client (JSSE) **over** that vsock-backed stream and validates the
server certificate against a **CA bundle baked into the EIF** (so the trust anchor is
measured by the PCRs). A failed chain/hostname check fails closed with no evidence.

- For the deployed mocks (`*.execute-api.ap-southeast-1.amazonaws.com`) the chain is
  the normal public Amazon API Gateway cert — validates against the standard CA bundle.
- For sandbox/real hosts (`sandbox.sellingpartnerapi-na.amazon.com`, KYC vendor hosts)
  the same public-CA validation applies.

### In-enclave TLS source client (Requirement 1)

New class `nitro-enclave/.../SourceTlsClient.java`:
- Input: a resolved `SourceEntry` (host, port 443, method, path+query, headers) and an
  optional request body (call #7).
- Opens `SSLSocket` over the vsock-backed transport, sends the HTTP/1.1 request with
  `Connection: close` and `Accept-Encoding: identity` (bounded, no compression — same
  constraints the MPC-TLS path used, see `.agents/tasks/compat-report.md`), reads the
  **full** response bytes `R` (status line + headers + body).
- Returns `R` as a byte array. `rawResponseB64 = base64(R)` and
  `rawHash = SHA256(R)` are computed inside the enclave (replacing the caller-supplied
  value). This is the single source of truth for "what the provider returned."

### 7-entry source registry (Requirement 2)

New class `nitro-enclave/.../SourceRegistry.java`, ported from the selection logic in
`coordinator/sources.py` (host/path/params/auth), holding exactly these entries:

| sourceId | provider | method | path (+required query) | auth | body |
|---|---|---|---|---|---|
| `getOrderMetrics` | amazon | GET | `/sales/v1/orderMetrics?...` | `x-amz-access-token` | — |
| `listFinancialEventGroups` | amazon | GET | `/finances/v0/financialEventGroups?...` | `x-amz-access-token` | — |
| `listTransactions` | amazon | GET | `/finances/2024-06-19/transactions?...` | `x-amz-access-token` | — |
| `alicloudTelThree` | alicloud | GET | `/lundear/telThree?...` | `Authorization: APPCODE ...` | — |
| `qichachaEnterpriseVerify` | qichacha | GET | `/EnterpriseInfo/Verify?...` | `key`+`Timespan`+`Token` | — |
| `qichachaShixinCheck` | qichacha | GET | `/ShixinCheck/GetList?...` | `key`+`Timespan`+`Token` | — |
| `gutuPanoramaChecks` | gutu | POST | `/api/v1/judicial/panorama-checks` | `Authorization: Bearer ...` | JSON |

- Host resolution mirrors `sources.py`: a per-provider base URL comes from registry
  config (the deployed mock host for the PoC; the real provider host for sandbox/prod).
  The enclave reaches **whatever host the registry entry names** (Requirement intro).
- `WHEN sourceId ∉ registry` → `SOURCE_SCOPE_INVALID`, no evidence (replaces the
  current `mock-api`/`GET_ORDERS` hardcode and the `SOURCE_SCOPE_INVALID` throw).
- The Qichacha `Token` is `md5(appKey + Timespan + secretKey)` — port
  `qichacha_token()` verbatim.

### Credentials under attestation (Requirement 3)

Per-call auth is applied **inside** the enclave. Credentials must be usable only
inside an approved-PCR enclave and never readable on the host:

- **PoC (mock hosts):** the mocks need no provider secret (they accept an optional
  `x-api-key`); the registry sends the mock key if present. Zero provider credentials,
  matching `sources.py` mock mode.
- **Sandbox/prod:** credentials are delivered **KMS-gated on attestation** — the
  parent holds only ciphertext; the enclave calls KMS `Decrypt` with its attestation
  document, and the KMS key policy releases plaintext only when
  `kms:RecipientAttestation:PCRn` matches the approved release (the pattern from the
  CTO TEE design, `docs/ENGAGEMENT_CONTEXT.md` §6b). The enclave then builds the auth
  header (`build_auth_headers` logic ported from `sources.py`) and, for Amazon,
  performs the LWA token exchange inside the enclave.
- Missing/rejected credential → fail closed, no evidence. No credential, token, raw
  payload, or response body is ever logged.

### Evidence & attestation contract (Requirement 4)

`EnclaveService.acquire` keeps producing the same evidence map, with these deltas:

- **Removed from the required request set and from the manifest/evidence:** `tlsProof`,
  `tlsProofType`, `tlsProofHash`, `tlsProofResponseHash`. The `tlsProof` validation
  block and the `rawHash == tlsProof.responseHash` gate are deleted.
- **`rawResponseB64`/`rawHash`** now come from `SourceTlsClient`, not the request.
- **`user_data` binding** becomes
  `{requestId, nonce, policyVersion, sourceId, rawHash, transformedHash, publicKey}` —
  `tlsProofHash` removed, `sourceId` added (Requirement 4.2). Key order fixed and
  mirrored in the verifier.
- **`source`/`endpoint`** manifest fields are replaced by `sourceId` (the registry id).
- `transform()` stays **pass-through / existing stub per source** (Requirement 4.4) —
  the richer finances transform is the separate `finances-transformation` spec and is
  explicitly out of scope here.

Everything else in the evidence map (`rawPayload`, digests, `attestedPublicKeyBase64`,
`enclaveSignature`, `manifestDigest`, `attestationDocument`, `eifDigest`) is unchanged.

### Olea verifier changes (Requirement 5)

In `index.js` `submitEvidence`:
- Remove `tlsProofType`, `tlsProofHash`, `tlsProofResponseHash`, `tlsProof` from
  `required[]`.
- Remove the `verifyTlsNotaryProof(...)` call and the `TLS_PROOF_INVALID` check block;
  drop `require('./tlsnotary-verifier')` from the live path (module stays in the repo).
- Replace `if (body.source !== 'mock-api' || body.endpoint !== 'GET_ORDERS')` with a
  **registry check**: `body.sourceId ∈ {the 7 ids}` else `SOURCE_SCOPE_INVALID`. The
  7 ids live in a shared constant (and the challenge endpoint check in `issueChallenge`
  is updated the same way — today it hardcodes `mock-api`/`GET_ORDERS`).
- Keep the same-bytes check `SHA256(decode(rawResponseB64)) === rawPayloadDigest`
  (Requirement 5.2) and the transformed-digest check.
- In `verifyNitroAttestation(...)`, drop `tlsProofHash` from the `user_data` object and
  add `sourceId`, matching the enclave binding exactly.
- Keep attestation chain/PCR/signature/nonce/envelope/vault/202 untouched.

`verification-contract.js buildManifest` must drop the `tlsProof*` fields from the
manifest it rebuilds (it currently mirrors them); keep it in lockstep with the enclave
manifest so `manifestDigest` still matches.

### Challenge / nonce (Requirement 6) — unchanged

`issueChallenge` still issues a signed, single-use, TTL-bounded nonce and
`submitEvidence` still enforces `challenge.nonce == body.nonce == user_data.nonce` and
single-use via the conditional `UpdateCommand`. Only the endpoint/scope field names
change (`sourceId` instead of `endpoint==='GET_ORDERS'`).

### EIF rebuild + re-register (Requirement 7)

The enclave code and the baked-in CA bundle change ⇒ PCR0/1/2 change. `Dockerfile`
adds the CA bundle; rebuild the EIF, read its new PCRs, and `POST /v1/releases` to
register them ACTIVE with the attested public key before any accepted evidence. The
old PCRs are not claimed valid for the converted enclave (Requirement 7.2).

### All 7 proven E2E (Requirement 8)

For each sourceId: `POST /v1/challenges` → coordinator dispatches `{sourceId, nonce,…}`
to the enclave over vsock → enclave fetches over its own TLS from the deployed mock →
attestation → `POST /v1/evidence` → **202**. Capture one evidence record per call under
`evidence/tls-in-tee/<sourceId>.json`. Negative check: point the enclave at a wrong
host, or flip a byte in the host relay, and show acceptance fails closed (the host
cannot alter data without breaking the attestation-bound `rawHash`).

---

## Error codes (fail-closed matrix)

| Condition | Where | Code |
|---|---|---|
| Unknown sourceId | enclave + verifier | `SOURCE_SCOPE_INVALID` |
| Server cert chain/hostname fails | enclave | `TLS_HANDSHAKE_FAILED` (new) |
| Credential missing/rejected | enclave | `SOURCE_AUTH_FAILED` (new) |
| `SHA256(rawResponseB64) != rawPayloadDigest` | verifier | `RAW_PAYLOAD_HASH_MISMATCH` |
| PCR mismatch / revoked | verifier | `PCR_MISMATCH` / `EIF_REVOKED` |
| Nonce reuse/expiry | verifier | `CHALLENGE_REPLAY` / `CHALLENGE_EXPIRED` / `NONCE_MISMATCH` |
| `user_data` mismatch | verifier | `ATTESTATION_BINDING_INVALID` |

New codes (`TLS_HANDSHAKE_FAILED`, `SOURCE_AUTH_FAILED`) are enclave-side and surface
as the enclave handler's `{ok:false,error}` (see `EnclaveMain`), which the coordinator
propagates fail-closed.

---

## Decisions and open items (Requirement 9)

- **Networking (9.1):** host vsock→TCP relay (AWS `vsock-proxy` or a minimal parent
  relay); enclave JSSE TLS client; CA bundle baked into the EIF so it is PCR-measured.
  To confirm during implementation: all 4 hosts validate against that bundle.
- **Credentials (9.2):** KMS-gated decrypt bound to attestation for sandbox/prod; mocks
  need none for the PoC. Confirm the KMS key policy `kms:RecipientAttestation:PCRn`
  condition against the new PCRs.
- **Transform (9.3):** stays pass-through for all 7 in this spec; the richer finances
  transform is deferred to the `finances-transformation` spec (that spec's Requirement
  7 already pledges not to disturb these seals).
- **Reference code reuse:** `coordinator/sources.py` is the authoritative behavior
  reference for host/path/param/auth; it is **ported to Java inside the enclave**
  (the Python coordinator was already removed from the runtime). Keep `sources.py` as a
  reference artifact or delete once ported — decide in tasks.

## Testing strategy

- **Unit (enclave, Java):** `SourceRegistry` dispatch (7 known + 1 unknown→fail);
  `qichacha_token` parity with the Python; `user_data`/manifest field set and order
  (no `tlsProof*`, has `sourceId`); `SourceTlsClient` request shaping with a local
  TLS stub.
- **Unit (verifier, JS):** `required[]` no longer contains notary fields; registry
  source check accepts the 7 and rejects others; `verifyNitroAttestation` user_data
  object matches the enclave; `buildManifest` has no `tlsProof*`.
- **Contract parity:** a shared vector asserts `manifestDigest` computed by enclave ==
  verifier `buildManifest` for a sample evidence body.
- **E2E (Requirement 8):** the 7 live calls against the deployed mocks → 202 each, plus
  the two negative cases (wrong host, tampered relay byte).
