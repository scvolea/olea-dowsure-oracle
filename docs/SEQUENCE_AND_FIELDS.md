# Olea–Dowsure Oracle — Sequence, Payload Fields & Tamper-Evidence

Authoritative, maintainable source for the end-to-end sequence diagram, the payload
field reference, and the anti-tamper model. Reflects the **rawResponseB64 binding**
(the TLS proof and the Nitro attestation now bind to the exact same HTTP response bytes).

For who-hosts-what and the ELI5 narrative see `PRESENTATION.md`; for the flow prose see `FLOWS.md`.

---

## End-to-end sequence

```mermaid
sequenceDiagram
    autonumber
    participant S as Amazon SP-API
    participant P as Prover sidecar
    participant N as Notary
    participant C as Coordinator
    participant E as Nitro Enclave
    participant O as Olea Verifier

    Note over C,O: 1 Challenge - bind this run to a one-time nonce
    C->>O: POST /v1/challenges requestId source=mock-api endpoint=GET_ORDERS policyVersion=v1.0
    O-->>C: 201 nonce policyVersion challengeSignature expiresAt

    Note over P,N: 2 Notarize the live HTTPS call via MPC-TLS
    P->>S: HTTPS GET /sales/v1/orderMetrics with x-amz-access-token
    S-->>P: 200 OK full HTTP response R = status + headers + JSON body
    P->>N: MPC-TLS session, neither side holds the key alone
    N-->>P: signs presentation over revealed transcript, nonce-bound
    Note right of P: response_hash = SHA256 of R over wire bytes 0..n<br/>revealed_recv_b64 = base64 of R  (NEW)
    P-->>C: proof bundle server_name notary_pub_key_id response_hash nonce presentation_b64 revealed_recv_b64

    Note over C,E: 3 Enclave processes and binds the SAME bytes
    C->>E: vsock request rawPayload rawResponseB64 tlsProof nonce policyVersion eifDigest evidenceId
    Note right of E: rawHash = SHA256 of decode rawResponseB64<br/>GATE rawHash == tlsProof.responseHash else TLS_PROOF_HASH_MISMATCH<br/>transformedHash = SHA256 of canonical transform<br/>ephemeral P-256 key, NSM attestation over user_data
    E-->>C: evidence rawPayload rawResponseB64 rawPayloadDigest transformed+digest tlsProofHash tlsProofResponseHash attestedPublicKeyBase64 manifestDigest enclaveSignature attestationDocument

    Note over C: 4 Dowsure signs the submission envelope
    C->>C: envelope requestId nonce policyVersion evidenceId manifestDigest encryptedEvidenceReference submittedAt<br/>submissionSignature = ECDSA over canonical envelope

    Note over C,O: 5 Submit combined evidence, all seals checked
    C->>O: POST /v1/evidence evidence + rawResponseB64 + tlsProof + submissionEnvelope + submissionSignature + pcr0 pcr1 pcr2
    Note right of O: nonce fresh and single-use; pcr0/1/2 == registered release;<br/>SHA256 of decode rawResponseB64 == rawPayloadDigest == tlsProofResponseHash;<br/>tlsProof notary-pinned + domain + fresh; attestation COSE + chain + PCR + user_data + key;<br/>enclaveSignature over manifest; dowsure signature over envelope
    O-->>C: 202 ACCEPTED evidenceId status manifestDigest acceptedAt -> Object-Lock vault
```

---

## Payload fields

### The binding field

- **`rawResponseB64`** — base64 of the full HTTP response `R` (status line + headers + JSON body),
  the exact `[0,n)` bytes the notary committed. Produced by the sidecar (`revealed_recv_b64`),
  threaded by the coordinator into the enclave request, echoed by the enclave into the evidence,
  and re-hashed by the verifier. Single source of truth for "what Amazon returned."

### Proof bundle (sidecar + notary)

| Field | Meaning | Check |
|---|---|---|
| `server_name` | TLS SNI | must == `SP_API_HOST` (`DOMAIN_MISMATCH`) |
| `notary_pub_key_id` | notary verifying-key id | Olea pins it (`NOTARY_KEY_UNTRUSTED`) |
| `connection_time_unix` | session time | freshness (`TLS_PROOF_STALE`) |
| `response_hash` | `SHA256(R)` | the anchor both seals compare to |
| `nonce` | one-time challenge value | replay prevention |
| `presentation_b64` | signed TLSNotary presentation | origin proof |
| `revealed_recv_b64` | base64(R) (NEW) | lets enclave/verifier recompute `response_hash` |

Normalized `tlsProof` excludes `revealed_recv_b64` so `proofHash` stays intact.

### Attestation binding (`user_data`)

`{requestId, nonce, policyVersion, rawHash, transformedHash, publicKey, tlsProofHash}` —
`rawHash = SHA256(decode(rawResponseB64))`. Signed inside the enclave; immutable after.

### Submitted evidence (`POST /v1/evidence`)

`rawPayload`/`rawPayloadDigest(=rawHash)`, `rawResponseB64`, `transformedPayload`/`digest`,
`tlsProofType`/`tlsProofHash`/`tlsProofResponseHash(=rawPayloadDigest)`, `tlsProof`,
`attestationDocument`, `attestedPublicKeyBase64`, `enclaveSignature`, `manifestDigest`,
`submissionEnvelope`/`submissionSignature`, `eifDigest`, `pcr0`/`pcr1`/`pcr2`.

---

## How tampering is prevented (fail-closed at every step)

1. **One-time nonce** — identical in TLS proof, attestation `user_data`, and submission; single-use (`NONCE_MISMATCH`/`CHALLENGE_REPLAY`).
2. **TLS seal (origin)** — notary (pinned key) co-signs the session; any changed byte breaks `response_hash` (`NOTARY_KEY_UNTRUSTED`/`DOMAIN_MISMATCH`/`TLS_PROOF_STALE`).
3. **Same-bytes bridge** — `SHA256(decode(rawResponseB64)) == rawPayloadDigest == tlsProofResponseHash == tlsProof.responseHash`; forged payload can't match the notarized bytes (`TLS_PROOF_HASH_MISMATCH`).
4. **TEE seal (integrity)** — COSE-signed attestation chains to the genuine AWS Nitro root (fingerprint `64:1A:03:…:BB:5B`); PCR0/1/2 must equal the registered ACTIVE release (`PCR_MISMATCH`/`EIF_REVOKED`).
5. **Attestation binds the facts** — `user_data` mismatch → `ATTESTATION_BINDING_INVALID`.
6. **Key binding** — attested `public_key` == `attestedPublicKeyBase64`; `enclaveSignature` verifies with it.
7. **Dowsure authenticity** — `submissionSignature` verifies against the release's registered `dowsurePublicKeyPem` (`DOWSURE_SIGNATURE_INVALID`).
8. **Manifest integrity** — `manifestDigest` and envelope canonical shape must match (`MANIFEST_HASH_MISMATCH`/`SUBMISSION_ENVELOPE_INVALID`).
9. **Durable record** — accepted evidence written to S3 Object-Lock (COMPLIANCE); immutable.

**Bottom line:** no single party can forge a passing submission. Dowsure proves; the Notary and the Enclave are neutral witnesses; Olea verifies all seals; the nonce makes every receipt one-time.
