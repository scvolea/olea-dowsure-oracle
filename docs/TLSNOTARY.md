# TLSNotary MPC-TLS source-authenticity layer

This document describes the real MPC-TLS flow Olea/Dowsure uses to prove that a
piece of evidence came from a specific upstream TLS server (the Amazon SP-API
sandbox), who hosts what, why, and how to reproduce both the no-credential gate
and the orchestrator's credentialed sandbox run.

It is the design/doc for the components implemented across FEAT-001..004:

- `tls-notary/prover-sidecar/` — thin headless Rust glue around the **upstream**
  `tlsn-prover` (prover **path B**: an external prebuilt service, pinned to the
  same release as the notary image, driven over the `tlsProof` seam).
- `tls-notary/notary-server/` — the standalone, Olea-hosted upstream
  `notary-server` run/deploy shape, decoupled from Nitro.
- `tls-notary/bundle/normalize-bundle.js` — normalizes the sidecar bundle into
  the canonical `tlsProof` contract.
- `sam/olea/functions/verification/tlsnotary-verifier.js` — the Olea JS verifier.
- `nitro-enclave/.../EnclaveService.java` — binds the proof hash + nonce into the
  Nitro attestation `user_data`.
- `scripts/tlsnotary-e2e-demo.js` — the fixture/mock end-to-end demo.

---

## 1. Architecture of the real MPC-TLS flow (prover path B)

```
Olea Challenge API (API GW + Lambda)
        | issues nonce + requestId + policyVersion + expiry
        v
Dowsure coordinator  --shells out-->  tlsn-prover sidecar (Rust, external service)
                                             |  MPC-TLS handshake + GET
                                             |  (two-party computation with the notary)
                   +-------------------------+--------------------------+
                   v                                                    v
        SP-API sandbox host                                   Olea-hosted notary-server
        sandbox.sellingpartnerapi-na.amazon.com               (standalone, :7047)
                   |                                                    |
                   +--------- notarized session transcript -------------+
                                             |
                                             v
                              proof bundle (stdout JSON)
                                             |
                   normalize-bundle.js  -->  canonical tlsProof
                                             |
          +----------------------------------+-----------------------------+
          v                                                                v
  Nitro enclave (Java)                                         Olea verifier (JS)
  binds tlsProofHash + nonce                                   notary key pin, domain,
  into attestation user_data                                   responseHash, freshness,
                                                               nonce single-use
```

The **prover** and the **notary** are two independent parties that jointly run
MPC-TLS. Neither alone holds the TLS session keys. The prover drives the request
to SP-API; the notary co-signs a commitment to the ciphertext transcript without
ever seeing the plaintext.

The sidecar is treated like a database/service dependency: the coordinator
shells out, passes the target + headers + Olea nonce, and reads one JSON bundle
from stdout. A non-zero exit or `ok=false` means **no proof** — the coordinator
must not fall back to an unnotarized fetch.

### Decoupled from Nitro (survives Nitro removal)

The notary and the sidecar are standalone services. The Nitro enclave consumes
the resulting `tlsProof` only through the `tlsProof` field seam. If Nitro is
later removed, the notary + prover sidecar + the Olea verifier still work
unchanged — the source-authenticity guarantee does not depend on the enclave.

---

## 2. The notary-learns-nothing property

TLSNotary's MPC-TLS splits the TLS client between the prover and the notary so
that:

- the **notary never sees the plaintext** request or response (it only
  participates in the MPC and signs a commitment to the encrypted transcript),
  so the LWA access token in the `x-amz-access-token` request header is **never**
  revealed to the notary;
- the **prover cannot forge** a transcript, because the notary co-authenticated
  the session: a transcript the notary did not co-sign will not verify against
  the notary's verifying key.

The prover later builds a **presentation** that selectively reveals only the
parts of the transcript it chooses (here: the response body + the bound request
line), and **withholds the token value**. The reveal selection excludes the
token; `normalize-bundle.js` strips the `x-amz-access-token` header line as
defence-in-depth and fails closed if a `name:` fragment survives.

---

## 3. PRODUCTION TRUST RULE — the notary must be in the VERIFIER's trust domain

> **The notary MUST be hosted by the party that CONSUMES the proof (the
> verifier = Olea), NOT by the party that PRODUCES the request (the prover =
> Dowsure).**

### Collusion rationale

The whole guarantee rests on the notary being an **independent** second party.
If the prover also controlled the notary (`prover == notary`), the operator
could run both halves of the MPC with chosen inputs and **mint a transcript for
a response that never came from SP-API** — co-signed by "the notary" because the
notary is the same party. The signature would still verify against the pinned
key, so the verifier would accept fabricated evidence. That voids the entire
source-authenticity claim.

Therefore:

- **Dowsure (the prover)** runs the sidecar — it legitimately holds the LWA
  token and drives the request. That is fine; the prover is *expected* to be the
  interested party.
- **Olea (the verifier)** hosts the notary, on infrastructure Olea controls, in
  Olea's account/trust domain, with a keypair Olea generates and guards. The
  verifier pins that notary's public key and rejects any other signer.

This split is what makes a Dowsure-produced proof trustworthy to Olea: Olea's own
notary co-authenticated the session, and Dowsure could not have forged it.

### Where the pinned notary PUBLIC key lives

- **Source of truth (hex key id):** `tls-notary/notary-server/notary-key-id.txt`
- **Optional PEM (SPKI):** `tls-notary/notary-server/notary.pub`
- **Verifier runtime pin:** the Olea verifier reads the pinned id from
  `expected.notaryPubKeyId` or the `OLEA_NOTARY_PUBKEY` environment variable and
  compares it to the bundle's `tlsProof.notaryPubKeyId`. Any mismatch rejects
  with `NOTARY_KEY_UNTRUSTED`; no pin at all fails closed with
  `NOTARY_KEY_UNPINNED`.

The notary **private** signing key is generated and held only on the notary host
(Olea), is gitignored, and is mounted at runtime (in production, from Secrets
Manager / KMS). Only PUBLIC key material is ever committed.

---

## 4. Hosting: API Gateway + Lambda is WRONG for the notary, RIGHT for the verifier

| Component | Correct host | Why |
|---|---|---|
| **notary-server** | **ECS Fargate** (private subnets + internal NLB TCP `:7047`, signing key in Secrets Manager/KMS) | Needs a **long-lived bidirectional MPC socket**, **persistent per-session state**, and a **stable signing keypair**. API Gateway + Lambda is request/response, short-lived, and stateless — it cannot hold the MPC socket or session, so it is **unsuitable** for the notary. |
| **Olea verifier** (`/v1/evidence`) | **API Gateway + Lambda** | Stateless request/response: validate a submitted bundle, hit DynamoDB, return a receipt. A perfect fit. |
| **Olea Challenge / nonce API** (`/v1/challenges`) | **API Gateway + Lambda** | Stateless: issue a signed nonce, store it, return it. A perfect fit. |
| **prover sidecar** | Server-side alongside the coordinator (container) | Drives MPC-TLS; not a public endpoint. (`tlsn-js` is browser-only and deprecated — wrong tool here.) |

See `tls-notary/notary-server/DEPLOY-ECS-FARGATE.md` for the Fargate target
detail. This is design/doc, not a live deploy.

### Network and DNS

- **Target host (Amazon SP-API): real public DNS, never customized.** The prover
  connects to the genuine `sandbox.sellingpartnerapi-na.amazon.com` (prod:
  `sellingpartnerapi-na.amazon.com`) via normal public resolution. The notarized
  server name is part of the proof, so the target MUST resolve to the real Amazon
  edge — rewriting or spoofing its DNS would void the proof's meaning. No custom
  DNS here.
- **Notary endpoint: a stable DNS name under `oleainternal.com`** (hosted zone in
  the Olea **tooling** account), e.g. `notary.oleainternal.com`, pointing at the
  notary's internal NLB (TCP `:7047`) on ECS Fargate. The prover and verifier
  reach the notary by this name, but **trust is anchored on the notary's pinned
  public key, not the DNS name** — the name is only addressing. The TLS listener
  on the notary can carry an `oleainternal.com` cert for transport, independent of
  the MPC signing key.
- **Enclave egress (if the prover ever runs inside Nitro):** the enclave has no
  direct network; outbound traffic goes over vsock to the parent host, which does
  DNS resolution and forwards to the real target. In the current design the prover
  sidecar and the notary are standalone (not in the enclave), so this does not
  apply to the demo.

### Test framing: Dowsure proving against Olea's infra

The demo and preprod exercise are run **as if Dowsure (the prover) is testing
against Olea-hosted infrastructure** — i.e. Dowsure drives the MPC-TLS session
while the **notary runs on Olea's side** (`notary.oleainternal.com`, Olea's key).
This mirrors the production trust split in section 3 exactly: the prover is
Dowsure, the independent notary is Olea, so a Dowsure-produced proof is
trustworthy to Olea because Olea's own notary co-authenticated the session.

---

## 5. Nonce binding — Olea challenge nonce vs TLSNotary handshake randomness

These are **two different nonces** and must not be confused.

| | **Olea challenge nonce** | **TLSNotary handshake randomness** |
|---|---|---|
| Who issues it | The Olea Challenge API (`issueChallenge`), 32 random bytes, base64url, signed, stored single-use with a TTL | The TLS 1.2 handshake itself (ClientHello/ServerHello randoms), internal to the MPC-TLS session |
| Purpose | **Freshness + anti-replay + request binding** at the Olea application layer: ties *this* proof to *this* Olea request so a proof cannot be replayed for a different request | Cryptographic TLS session uniqueness / key derivation **inside** the protocol |
| Where it appears | Echoed by the sidecar into the bundle (`nonce`), normalized to `tlsProof.nonce`, bound into the enclave `user_data`, and checked by the verifier against the issued challenge | Never surfaced to Olea; it is a TLS/MPC internal and is **not** the anti-replay control |

**The Olea challenge nonce is the anti-replay control, NOT TLSNotary's handshake
randomness.** The verifier ties three values together for one request:
`challenge.nonce === body.nonce === tlsProof.nonce`. The nonce is also bound into
the Nitro attestation `user_data` alongside `tlsProofHash` (FEAT-003), so the
attestation cryptographically commits to *which* proof answered *which*
challenge. Single-use consumption (the conditional DynamoDB update in
`index.js`) prevents a second submit (`CHALLENGE_REPLAY` / `SUBMISSION_REPLAY`).

### user_data binding fields (FEAT-003)

The enclave binds these seven fields into attestation `user_data` (canonical,
sorted-key JSON — order does not matter, names must match the verifier):
`requestId, nonce, policyVersion, rawHash, transformedHash, publicKey,
tlsProofHash`. `tlsProofHash = tlsProof.proofHash`.

---

## 6. One-command reproduce

### 6a. No-credential agent gate (this workflow — JS fixture/mock)

Runs with **no credentials and no Rust compile**. From the worktree root:

```powershell
node --test scripts/tlsnotary-e2e-demo.test.js   # 11 node:test cases, all green
node scripts/tlsnotary-e2e-demo.js               # prints ACCEPT + 8 REJECT reasonCodes, exit 0
```

Expected: one `ACCEPT` for a valid bundle and a distinct `REJECT <reasonCode>`
for each of: tampered response (`TLS_PROOF_HASH_MISMATCH`), tampered proof
(`TLS_PROOF_INVALID`), wrong nonce (`NONCE_MISMATCH`), missing nonce
(`NONCE_MISMATCH`), expired nonce (`CHALLENGE_EXPIRED`), replayed nonce
(`CHALLENGE_REPLAY`), wrong notary key (`NOTARY_KEY_UNTRUSTED`), wrong domain
(`DOMAIN_MISMATCH`). Redacted evidence lands under `evidence/tlsnotary/`.

### 6b. No-credential MPC gate (orchestrator — Rust, no SP-API creds)

Proves the real MPC-TLS pipeline (prove -> present -> verify) against the
upstream `tls-server-fixture`, no external credentials, inside a pinned
`rust:1.90` container. First run compiles the full tlsn + mpz tree (tens of
minutes). From `tls-notary/gate-proof/`:

```powershell
./run-gate-proof.ps1
```

### 6c. Credentialed SP-API sandbox run (orchestrator only — real sidecar)

Runs the SAME upstream prover binaries against the SP-API sandbox with a real
LWA token. **Orchestrator only; not executed in this workflow.** The token is
passed as a header at call time and is **never** committed or logged; the reveal
selection and `normalize-bundle.js` keep the `x-amz-access-token` value out of
the bundle and the evidence.

```bash
# 1. Build the sidecar image (Linux target, host stays clean):
docker build -t tlsn-prover-sidecar:alpha.12 tls-notary/prover-sidecar

# 2. Run against the SP-API sandbox (token redacted from the reveal selection):
tlsn-prover-sidecar \
  --host sandbox.sellingpartnerapi-na.amazon.com --port 443 \
  --path '/sales/v1/orderMetrics?marketplaceIds=ATVPDKIKX0DER&interval=2022-08-01T00:00:00-07:00--2022-08-02T00:00:00-07:00&granularity=Total' \
  --header "x-amz-access-token: $LWA_ACCESS_TOKEN" \
  --nonce "$OLEA_CHALLENGE_NONCE" \
  --notary-host notary.olea.internal --notary-port 7047 --notary-tls true
```

The bundle on stdout is then normalized (`normalize-bundle.js`) and submitted to
the Olea verifier exactly as the demo does — the only difference is the proof is
from a real notarized SP-API session instead of the checked-in fixture.

### Version pins

| Component | Pin |
|---|---|
| Upstream tlsn crates | git `tag = v0.1.0-alpha.12` |
| notary-server image | `ghcr.io/tlsnotary/tlsn/notary-server:v0.1.0-alpha.12` |
| Rust toolchain | `1.90` (floor 1.87 for mpz `extract_if`) |

Prover and notary MUST share the same protocol release.
