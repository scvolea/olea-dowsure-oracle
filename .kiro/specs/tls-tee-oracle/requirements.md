# Requirements — Convert the oracle to TLS-in-TEE (7 calls, working solution)

## Introduction

Convert the existing Olea-Dowsure oracle from the notary-based MPC-TLS path to
**TLS-in-TEE**: the Nitro enclave itself opens and terminates the TLS connection to
each source, so the response is decrypted only inside the enclave, hashed, and
attested. The host never sees plaintext.

This spec is a **working-solution** scope only — convert what exists and make exactly
**7 source calls** work end to end through the enclave and the Olea verifier. No new
abstractions beyond what these 7 calls need.

### The 7 calls (the entire scope)

| # | Provider | Method | Path | Format |
|---|---|---|---|---|
| 1 | Amazon SP-API | GET | `/sales/v1/orderMetrics` | JSON |
| 2 | Amazon SP-API | GET | `/finances/v0/financialEventGroups` | JSON |
| 3 | Amazon SP-API | GET | `/finances/2024-06-19/transactions` | JSON |
| 4 | AliCloud | GET | `/lundear/telThree` | JSON |
| 5 | Qichacha | GET | `/EnterpriseInfo/Verify` | JSON |
| 6 | Qichacha | GET | `/ShixinCheck/GetList` | JSON |
| 7 | Gutu | POST | `/api/v1/judicial/panorama-checks` | JSON |

Hosts for the PoC are the existing deployed mocks (`evidence/preprod-mocks/`):
Amazon SP mock `097sqg03n1.execute-api.ap-southeast-1.amazonaws.com/mock`, KYC mock
`i8yde0kf2g.execute-api.ap-southeast-1.amazonaws.com/mock`. Production hosts are the
real `sandbox.sellingpartnerapi-na.amazon.com` and the real KYC vendor hosts; the
enclave reaches whatever host the registry entry names.

### Convert, don't rebuild

- **Keep:** the Nitro enclave, `EnclaveService` evidence shape, the attestation path,
  the Olea verifier + attestation verifier, PCR registration, the Object-Lock evidence
  vault, the challenge/nonce model, canonical hashing, the evidence/manifest field set.
- **Change:** the enclave now **makes the HTTPS call itself** (new in-enclave TLS
  client + a 7-entry source registry + credentials supplied under attestation).
  `rawResponseB64`/`rawHash` now come from the enclave's own TLS response, not from a
  sidecar/notary.
- **Remove from the live path:** the external notary + MPC-TLS prover sidecar and the
  `tlsProof` fields. (Left in the repo as reference; not called in this flow.)

### Out of scope

The Dowsure-generated repayment file (Dowsure signs it, no TLS-in-TEE); the KYC rule
engine / risk scoring; the Super PO math; PII-policy design; multi-GB streaming;
provider fleets beyond these 7; production credential/commercial agreements.

---

## Requirements

### Requirement 1 — Enclave makes the TLS call

**User story:** As Olea, I want the enclave to fetch each source itself over TLS it
terminates, so the host can't read or alter the response before attestation.

#### Acceptance criteria

1. WHEN the enclave handles a request for one of the 7 calls THEN it SHALL open a TLS
   1.2/1.3 connection to the registry host and perform the request itself; session keys
   SHALL exist only inside the enclave.
2. The host SHALL relay bytes only (vsock → outbound TCP) and SHALL NOT have plaintext
   request or response.
3. The enclave SHALL validate the server certificate against a CA bundle in the enclave
   image; a failed chain/hostname check SHALL fail closed with no evidence.
4. The response bytes the enclave reads SHALL become `rawResponseB64`, and
   `rawHash = SHA256(rawResponseB64 bytes)` SHALL be computed inside the enclave.

### Requirement 2 — 7-entry source registry

**User story:** As the enclave runtime, I want an explicit table of the 7 calls, so a
request selects a known source rather than trusting arbitrary input.

#### Acceptance criteria

1. The enclave SHALL hold a registry of exactly the 7 entries above, each with: id,
   provider, host, method, path (with required query params), auth type, and expected
   format.
2. WHEN a request names a registry id THEN the enclave SHALL use that entry's host/
   method/path/auth; WHEN a request names an unknown id THEN the enclave SHALL fail
   closed (`SOURCE_SCOPE_INVALID`) and emit no evidence.
3. For the POST call (#7) the enclave SHALL send the request body supplied in the
   request; for the GET calls it SHALL send the registry path/query.
4. The current hardcoded `source='mock-api'`/`endpoint='GET_ORDERS'` check SHALL be
   replaced by this registry dispatch.

### Requirement 3 — Credentials supplied under attestation

**User story:** As security, I want each source's credential usable only inside the
approved enclave, so the host can't exfiltrate it.

#### Acceptance criteria

1. Per-call auth SHALL be applied inside the enclave: Amazon calls send
   `x-amz-access-token` (LWA); KYC calls send their API key/header as the registry
   entry specifies.
2. Credentials SHALL be delivered so they are only usable inside an enclave whose PCRs
   match an approved release (e.g. KMS decrypt gated on the attestation), and SHALL NOT
   be readable on the host or committed as plaintext.
3. WHEN a credential is missing or rejected by the provider THEN the enclave SHALL fail
   closed and emit no evidence.
4. Credentials, tokens, raw payloads, and response bodies SHALL NOT be logged.

### Requirement 4 — Evidence contract unchanged

**User story:** As the maintainer, I want the evidence/attestation shape to stay the
same so the verifier and vault don't need rework beyond dropping the notary fields.

#### Acceptance criteria

1. The enclave SHALL still produce: `rawPayload`, `rawResponseB64`, `rawPayloadDigest`
   (=`rawHash`), `transformedPayload` + `transformedPayloadDigest`, `attestedPublicKeyBase64`,
   `enclaveSignature`, `manifestDigest`, `attestationDocument`, `eifDigest`, `pcr0/1/2`.
2. The attestation `user_data` binding SHALL remain
   `{requestId, nonce, policyVersion, rawHash, transformedHash, publicKey}` plus the
   source id; key order unchanged.
3. The `tlsProof`/`tlsProofHash`/`tlsProofResponseHash` fields and the notary pin SHALL
   be removed from the required set (no external notary in this path); the verifier
   SHALL no longer require them.
4. The transform step MAY stay as-is per source for this working solution (pass-through
   or the existing stub) — a richer transform is the separate finances-transformation
   spec and is NOT required here.

### Requirement 5 — Olea verification (minus the notary)

**User story:** As the Olea verifier, I want to accept evidence by checking the
attestation and the same-bytes hash, so acceptance stays verification-based.

#### Acceptance criteria

1. The verifier SHALL validate the attestation chain to the AWS Nitro root, assert
   `pcr0/1/2 == ACTIVE registered release`, verify the evidence signature against the
   attested key, and verify the `user_data` binding.
2. The verifier SHALL recompute `SHA256(decode(rawResponseB64)) == rawPayloadDigest`
   (the same-bytes check; the enclave's own TLS termination is the origin anchor — there
   is no notary hash to compare).
3. The verifier SHALL enforce the one-time nonce (fresh, unexpired, single-use).
4. The verifier SHALL NOT require any `tlsProof`/notary field for this path.
5. On success the verifier SHALL store the record in the Object-Lock vault and return a
   202 receipt.

### Requirement 6 — Challenge / nonce / replay (unchanged)

**User story:** As Olea, I want each evidence record tied to a one-time challenge.

#### Acceptance criteria

1. Olea SHALL issue a signed, single-use, TTL-bounded nonce; the enclave SHALL bind it
   into `user_data`.
2. The verifier SHALL require `challenge.nonce == body.nonce == user_data.nonce` for the
   same `requestId`; a reused challenge SHALL fail closed.

### Requirement 7 — EIF rebuild + re-register

**User story:** As the maintainer, I want the converted enclave rebuilt and its PCRs
re-registered, so the new image is the approved one.

#### Acceptance criteria

1. WHEN the enclave code changes (new TLS client + registry) THEN a new EIF SHALL be
   built and its PCR0/1/2 re-registered ACTIVE before producing accepted evidence.
2. The old registered PCRs SHALL NOT be claimed as valid for the converted enclave.

### Requirement 8 — All 7 calls proven end to end

**User story:** As the team, I want each of the 7 calls to produce an accepted evidence
receipt, so the conversion is demonstrably working.

#### Acceptance criteria

1. FOR EACH of the 7 registry calls THEN the flow (challenge → enclave fetches over its
   own TLS → attestation → `POST /v1/evidence`) SHALL return 202 ACCEPTED against the
   deployed mock hosts.
2. The captured evidence for each SHALL be retained under `evidence/` (one record per
   call) as proof.
3. WHEN the enclave is pointed at a wrong host or a tampered response is injected on the
   host relay THEN acceptance SHALL fail closed (demonstrating the host cannot alter the
   data).

### Requirement 9 — Open items to confirm in design

1. The outbound network path from the enclave (vsock → host proxy → provider), and that
   provider certs validate against the enclave CA bundle for all 4 hosts.
2. How credentials reach the enclave under attestation (KMS-gated decrypt vs supplied
   at launch) for the PoC vs production.
3. Whether the transform stays pass-through for all 7 in this working solution (yes,
   unless a call's consumer needs the finances transform — defer to that spec).
