# TLS-in-TEE, explained in detail

How the Olea–Dowsure oracle proves that a piece of data came, unaltered, from a real
source API — using nothing but a hardware-attested enclave. This is the mechanism that
replaced the external TLSNotary / MPC-TLS design (now archived in
`archive/TLSNOTARY.md`).

---

## 1. The problem it solves

Olea lends against borrower data that Dowsure supplies (Amazon seller metrics,
financial transactions, KYC/credit checks). Olea needs each data point to be
**verifiably genuine** — provably fetched from the real source and provably unaltered —
**without having to trust Dowsure's honesty or Dowsure's infrastructure**, even though
Dowsure operates the environment that fetches the data.

Two distinct properties are required:

- **Source authenticity (origin):** these exact bytes really came from the named source
  API over a genuine TLS connection — not fabricated, not swapped.
- **Execution integrity:** approved, unmodified code processed those exact bytes — not a
  tampered build that could lie about what it saw.

TLS-in-TEE delivers **both** from a single trust anchor: an AWS Nitro Enclave that
**terminates the TLS connection itself** and attests to its own code.

---

## 2. What a TEE / Nitro Enclave is (the trust primitive)

A **TEE** (Trusted Execution Environment) is an isolated compute environment whose
integrity is guaranteed by hardware. **AWS Nitro Enclaves** are the specific TEE used
here. Key properties the design relies on:

- **No network, no persistent storage, no interactive access.** The enclave has no NIC
  and no shell. Its *only* I/O channel is a `vsock` (virtio socket) link to its parent
  EC2 instance. The host cannot read the enclave's memory.
- **Measured boot → PCRs.** When the enclave image (the **EIF** — Enclave Image File) is
  built and launched, the hardware computes cryptographic measurements into **Platform
  Configuration Registers**:
  - **PCR0** — hash of the enclave image contents (the application + its dependencies).
  - **PCR1** — hash of the Linux kernel and boot ramdisk.
  - **PCR2** — hash of the application layer.
  Any change to the code changes PCR0 (and/or PCR2). The PCRs are the enclave's
  cryptographic *identity*.
- **Attestation documents.** On request, the Nitro hypervisor emits an **attestation
  document**: a CBOR structure, **signed by the AWS Nitro Attestation PKI**, containing
  the PCRs, a caller-supplied `user_data` blob, and a caller-supplied `public_key`. Only
  the hypervisor can produce a valid one. Anyone holding the AWS Nitro root certificate
  can verify it is genuine and read the measurements.

The upshot: an attestation document is an unforgeable statement of the form *"this exact
code (PCR0/1/2) is running, and it vouches for this `user_data` and this `public_key`."*

---

## 3. TLS termination inside the enclave (the origin anchor)

This is the defining move of TLS-in-TEE, and the one that eliminated the notary.

The enclave does **not** receive source bytes from the host. Instead it **opens its own
TLS connection** to the source API and decrypts the response **inside** the enclave:

```
Enclave  →  SourceTlsClient wraps a vsock socket in an SSLSocket
         →  TLS handshake runs end-to-end: enclave ⇄ source API
         →  the host relay (vsock-proxy) forwards only CIPHERTEXT
```

Mechanically (from `SourceTlsClient` / `AfVsockSourceTransport` in the enclave):

- The enclave opens an `AF_VSOCK` connection to the parent host and asks a host-side
  `vsock-proxy` to forward TCP to `provider-host:443`.
- The enclave wraps that raw socket in an `SSLSocket` and runs the **full TLS handshake
  itself**, with `setEndpointIdentificationAlgorithm("HTTPS")` so the **server
  certificate and hostname are verified inside the enclave** against a CA bundle **baked
  into the EIF** (not the host's trust store).
- It sends the HTTP/1.1 request (`Connection: close`, `Accept-Encoding: identity` — no
  compression, bounded read) and reads the **complete response bytes** `R` (status line
  + headers + body).

Because TLS terminates inside the enclave:

- The host's relay sees **only encrypted records** — it cannot read or alter the
  plaintext.
- A man-in-the-middle on the host can't impersonate the source: the cert/hostname check
  runs inside the enclave against the baked-in CA bundle, so a swapped endpoint fails
  the handshake (`TLS_HANDSHAKE_FAILED`).
- The exact bytes the enclave hashes are the exact bytes the source sent. **That is the
  origin proof** — no external witness needed.

> This is precisely what the notary used to provide (a co-signed transcript proving the
> bytes came from the server). TLS-in-TEE provides it *intrinsically*, because the
> attested code is the thing that spoke TLS to the server.

---

## 4. Binding data to the attestation (the integrity anchor)

Once the enclave has the response bytes `R`, it seals them (from `EnclaveService`):

1. **Hash the bytes.**
   - `rawHash = SHA-256(R)` — the exact wire bytes (status + headers + body).
   - `transformed = R`'s JSON body (today a **pass-through**, no business transform).
   - `transformedHash = SHA-256(canonicalize(transformed))` — canonical (sorted-key)
     JSON, tagged `RFC8785-PoC`.
2. **Generate an ephemeral key.** A fresh EC P-256 (`secp256r1`) key pair is created
   **inside the enclave for this one request** and discarded afterward.
3. **Build the attestation `user_data` binding** and request the attestation document:
   ```
   user_data = canonical({
     requestId, nonce, policyVersion, sourceId,
     rawHash, transformedHash, publicKey
   })
   ```
   The hypervisor embeds these bytes **inside the AWS-signed attestation document**,
   alongside the PCRs and the ephemeral public key. Nobody can change them afterward.
4. **Build + sign a manifest.** A manifest of the evidence metadata is canonicalized and
   signed with the ephemeral private key:
   `enclaveSignature = ECDSA-SHA256(canonicalize(manifest))`.

Now the data is welded to the hardware identity: the AWS-signed attestation says *"code
with PCR0/1/2 = X produced request `requestId`, bound to `nonce`, and the data hashes to
`rawHash`/`transformedHash`, signed by key `publicKey`."* Change any byte of the data and
`rawHash` no longer matches what the attestation sealed — and the ephemeral key needed to
re-sign a forged manifest never leaves the enclave.

---

## 5. The anti-replay nonce (freshness)

Before the enclave runs, the Coordinator asks the Olea verifier for a **challenge**
(`POST /v1/challenges`). Olea returns a fresh random `nonce` (32 bytes), a
`policyVersion`, an `endpointScope`, and an expiry — all stored server-side and
single-use.

The `nonce` is carried into the enclave, bound into the attestation `user_data`, and must
re-appear unchanged in the submitted evidence. At verification Olea checks the challenge
is unused and unexpired and atomically marks it used. This stops anyone from replaying a
previously accepted evidence package as if it were new.

---

## 6. Submission and verification (fail-closed)

The Coordinator wraps the enclave's evidence in a submission envelope, signs the envelope
with **Dowsure's** key (authorization/non-repudiation), and POSTs everything to
`POST /v1/evidence`. The Olea verifier (`sam/olea/functions/verification/`) then runs an
ordered gauntlet; **any** failure returns `422 REJECTED` with a specific reason:

1. **Challenge state** — exists, unused, unexpired, `nonce` matches, `policyVersion`
   matches, `sourceId` in scope.
2. **Release / PCR check** — `eifDigest` is a **registered, ACTIVE** release and the
   submitted `pcr0/1/2` match the registered values. *(This is where "approved code
   only" is enforced.)*
3. **Hash re-derivation** — re-hash `rawResponseB64` → must equal `rawPayloadDigest`;
   re-canonicalize + re-hash `transformedPayload` → must equal `transformedPayloadDigest`;
   rebuild the manifest → must equal `manifestDigest`.
4. **Signatures** — Dowsure's envelope signature (key from the release registry) and the
   enclave signature (key from the attestation) both verify.
5. **Attestation** — the Nitro document is genuine (AWS PKI chain), its PCRs match the
   release, its embedded `public_key` matches the signing key, and its `user_data` binds
   the same `{requestId, nonce, policyVersion, sourceId, rawHash, transformedHash,
   publicKey}`.
6. **Commit** — atomically mark the challenge used, store evidence in the Object-Lock
   vault (S3, KMS-encrypted), write a receipt, return **`202 ACCEPTED`**.

**Fail-closed** means: if the attestation is unreachable, the PCRs don't match, a hash is
off, a signature fails, or the nonce is stale — the data is **rejected**, never silently
accepted.

---

## 7. Why Dowsure (the host operator) cannot cheat

Dowsure runs the host and the Coordinator, so it sees the plaintext and holds the evidence
before it reaches Olea. It still cannot produce *accepted-but-tampered* data:

| Attempt | Why it fails |
|---|---|
| Alter the data only | Re-hash ≠ `rawPayloadDigest` → `RAW_PAYLOAD_HASH_MISMATCH` |
| Alter data **and** its digest | New digest ≠ the one sealed in the **AWS-signed** attestation → `user_data` mismatch |
| Alter data + digest + re-sign the manifest | The signing key is **ephemeral, inside the enclave** — Dowsure never has it → `ENCLAVE_SIGNATURE_INVALID` |
| Regenerate the whole attestation | Only the Nitro hypervisor emits a valid doc, and it carries **different PCRs** (or a key Dowsure controls) → `PCR_MISMATCH` |
| Swap the source endpoint | Cert/hostname verification runs inside the enclave against the baked-in CA → `TLS_HANDSHAKE_FAILED` |
| Replay old evidence | Nonce is single-use + expiring → `CHALLENGE_REPLAY` / `SUBMISSION_REPLAY` |

**Honest caveat — availability, not integrity:** Dowsure can refuse to submit, drop a
request, or decline to run a call (it controls the host). It cannot make Olea accept
false data. Completeness ("forward *all* relevant traffic, no side path") is a policy
concern — enforced by requiring every source call go through the enclave — not something
the cryptography alone guarantees.

---

## 8. How this differs from the archived TLSNotary design

| Aspect | TLSNotary / MPC-TLS (archived) | TLS-in-TEE (current) |
|---|---|---|
| Who proves origin | External, Olea-hosted **notary** co-signs the TLS session via MPC; a Rust prover sidecar emits a `tlsProof` | The **enclave terminates TLS itself**; the attested code *is* the origin anchor |
| Trust anchor | The notary's published public key | AWS Nitro attestation PKI + registered PCRs |
| External dependency on the request path | Yes — the always-on notary server (the client call blocks on it) | **None** — no notary, no sidecar |
| Extra evidence fields | `tlsProof`, `tlsProofHash`, `tlsProofResponseHash`, `tlsProofType` | Dropped; replaced by in-enclave TLS termination under attestation |
| What binds the two seals | `SHA256(rawResponseB64) == tlsProof.responseHash` welds notary bytes to attestation bytes | Not needed — one boundary fetched *and* attested the same bytes |
| Code status | Kept as reference only (`archive/TLSNOTARY.md`, `tls-notary/`, `tlsnotary-verifier.js`), off the live path | Live; proved with 7×202 ACCEPTED |

The net simplification: **two trust anchors collapsed into one.** Instead of "a notary
witnessed a session the enclave didn't control, and we bind that to the enclave's
attestation," it's simply "the attested enclave fetched the bytes and vouches for them."

---

## 9. See also

- Step-by-step flow with real payloads: `docs/flow-steps/` (`step0-index.md` → `step9`)
- Live component + sequence diagrams: `docs/flow-steps/step0-index.md`
- Production (Dowsure-hosted) topology + ownership: `docs/flow-steps/diagram-production-topology.md`
- Current facts (EIF digest, PCRs, status): `docs/PROJECT_STATUS_MATRIX.md`
- Verifier contract: `sam/olea/functions/verification/`
- Spec: `.kiro/specs/tls-tee-oracle/{requirements,design,tasks}.md`
- Archived notary design: `archive/TLSNOTARY.md`
