# Implementation Agent Handoff

> **Plain-English summary.** This is the one internal handoff for the next
> implementer. It states what is already proven (the sealed-box enclave and its
> attestation receipt, verified against AWS), what to build next (exercise the now-
> available Amazon sandbox against the finances endpoints, and replace the TLSNotary
> placeholder with a real signed proof), and the rules that must not be broken
> (fail-closed, no local mock, no secrets in the repo). For the authoritative status
> facts, see [docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md); this
> handoff keeps a historical measurements block that matches that matrix exactly.
>
> Acronyms on first use: PoC (Proof of Concept), SP-API (Selling Partner API, Amazon's
> seller data API), Nitro Enclave (an isolated, tamper-proof virtual machine with no
> storage and no normal network), EIF (Enclave Image File, the single built artifact
> that boots inside the enclave), PCR (Platform Configuration Register, a hash that
> fingerprints the EIF), COSE (CBOR Object Signing and Encryption), CBOR (Concise
> Binary Object Representation), vsock (virtual socket, the only channel between the
> enclave and its host), LWA (Login with Amazon), TLSNotary (a protocol that proves a
> specific HTTPS response came from a specific server), NSM (Nitro Security Module).

## Mission

Implement the controlled Olea-Dowsure Verifiable Data Oracle PoC (Proof of Concept)
described in this folder. The target is a narrow vertical slice for one or two
approved Amazon SP-API (Selling Partner API) endpoints, not unrestricted production
hardening.

## Current Verified State (2026-09-24)

The authoritative, living status lives in
[docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md). The block below is the
historical capture from this date; its enclave fingerprints match that matrix exactly.

- Olea stack: `olea-oracle-preprod`, `UPDATE_COMPLETE`.
- Olea API: `https://c8tw99zmla.execute-api.ap-southeast-1.amazonaws.com/preprod`.
- Dowsure stack: `dowsure-oracle-preprod`, `UPDATE_COMPLETE`.
- Amazon mock: `amazon-sp-api-mock`, `CREATE_COMPLETE`.
- Nitro stack: `olea-dowsure-nitro-preprod`, `UPDATE_COMPLETE`.
- Nitro host: `i-0b2b6aa26fb920103`, private `c5.xlarge`, SSM Online, IMDSv2 required, no SSH ingress.
- Running enclave: `olea-orders-java`, CID `16`, `2` vCPUs, `2048 MiB`, phase4 EIF state `RUNNING`.
- Verified scope: one bounded `GET_ORDERS` flow.
- Local Phase 1 negative tests cover malformed CBOR, PCR mismatch, public-key mismatch, user-data mismatch, replay, expiry, envelope tampering, enclave-signature tampering, corrupted TLS proof, and TLS response-hash mismatch.
- The enclave runtime is now Java with Java AF_VSOCK; NSM access is isolated behind `AttestationProvider` and `JnaAttestationProvider`.
- The test harness is [poc-signed-evidence-test.js](scripts/poc-signed-evidence-test.js); the redacted evidence recorder is [phase1-evidence-report.js](scripts/phase1-evidence-report.js).
- Live non-debug evidence verification passed for request `293548c9-9975-4cc0-9039-b19a5ce6b421` and evidence `e826e531-cbc6-41ed-93d5-1da3ae68fe09`.
- Verification passed against the AWS Nitro Root-G1 certificate for COSE signature, certificate chain, PCR0/PCR1/PCR2, attested public key, and canonicalized `user_data` binding.
- The phase4 EIF SHA-256 `246e2143aeecb6c2e4f5e551536b2dfc75e8313fd521dd4da91da8f5d907da94` is registered `ACTIVE` in the preprod Olea release registry.

### Live Java EIF Measurements

- EIF: `/opt/olea-nitro/olea-orders-java-phase4.eif`, `464095310` bytes.
- Docker image: `olea-nitro-orders:java-phase4`, ID `5c7f299e46ffe90d8e2fadb0bb0808a7a171512cc84b6f3eb0d0de2d91f88ab3`.
- PCR0: `5fba63399c819c8658452d9d48f35ecd491f9d65d5d842eb7ada4ae23a63cb666796a643102c9cc9e71c8a4bc60baba9`.
- PCR1: `4b4d5b3661b3efc12920900c80e126e4ce783c522de6c02a2a5bf7af3a2b9327b86776f188e4be1c1c404a129dbda493`.
- PCR2: `6560ff543ea448942b3f50ebadf606223a7aed1120856b503468a127671b22de6cbf195e0beb423b255100349e22c68f`.

## Next Steps

The implemented source path today is still the bounded mock `GET_ORDERS` flow (the
enclave code hardcodes `source='mock-api'`, `endpoint='GET_ORDERS'`). The next slice
re-aims the source work toward Amazon finances data and real source proof.

1. **Re-aim the next endpoint to finances, not order metrics.** The next integration
   target is **Transactions** - `GET /finances/2024-06-19/transactions`
   (`listTransactions`), which returns per-transaction detail with a breakdowns tree -
   together with **Financial Event Groups** - `GET /finances/v0/financialEventGroups`
   (`listFinancialEventGroups`), which returns settlement / fund-transfer groups. This
   supersedes the earlier order-metrics-only direction. `GET /sales/v1/orderMetrics`
   (`getOrderMetrics`) is only the shop-level daily sales aggregate; it is **not** the
   financing data source. See [docs/SOURCE_ENDPOINTS.md](docs/SOURCE_ENDPOINTS.md) for
   the full source map.
2. **Use the available Amazon sandbox - do not build a local Amazon mock.** The Amazon
   SP-API sandbox and its credentials are now available (provided out of band / stored
   as secrets; never write credential values in the repo, logs, this handoff, or any
   evidence report). Exercise the real sandbox endpoints and record only the
   environment, operation, non-sensitive parameters, response status/schema, and
   whether the payload is static. Treat sandbox static responses as test data, not live
   seller activity, and never silently replace the sandbox with a local fixture.
3. **Make source selection policy-driven.** The caller selects a registered source ID;
   configuration controls the environment base URL, allowed operation, parameters, and
   disclosure. Reject arbitrary URLs and enforce host/path allowlists.
4. **Replace the TLSNotary placeholder with real proof.** The current code enforces only
   the proof contract and response-hash binding. Integrate an approved TLSNotary
   prover/notary service: approved notary-key pinning, source and endpoint binding,
   freshness / request binding, and exact response-hash binding.
5. **Route through the coordinator path.** Replace the transitional API Gateway parent
   fixture with the coordinator service on the acceptance path, and run the source
   response and proof through coordinator -> vsock -> enclave -> Olea verifier.
6. **Keep fail-closed behavior.** Auth failures, unsupported operations, invalid proofs,
   policy violations, and attestation failures must all fail closed. If the sandbox
   endpoint or the TLSNotary protocol is incompatible, stop and document the evidence
   and the smallest approved alternative. Do not fall back to unproved direct HTTPS and
   describe it as TLSNotary.
7. **Capture the live end-to-end evidence package** and execute replay, expiry, PCR,
   public-key, envelope, and TLS tamper scenarios, then record the Olea acceptance
   receipt.

### Onboarding test cases (sample suppliers)

Use these two named suppliers as onboarding test-case labels only (no other data about
them is defined, and none may be invented):

- Quanzhou Feile E-commerce Co., Ltd
- Xiamen Yunyu Tianji Network Technology Co., Ltd

### Dependency and ownership

- The joint Trusted Execution Environment (TEE) / Transport Layer Security plus Trusted
  Execution Environment (TLS+TEE) integration/test environment is **blocked on Dowsure**.
  Dowsure will share a schedule only after their internal discussion on or around
  **10 October**, and has not yet assigned a technical counterpart.
- **Owner:** Anudeep Sai Nunna is Olea's named owner for the TLS+TEE test environment
  setup; he has a validated Olea-internal enclave (this repo's PoC).

Do not describe the PoC as fully accepted until the live NSM (Nitro Security Module)
document, configured Nitro root, and end-to-end receipt are captured.

## Read First

1. `README.md`
2. `olea-dowsure-executive-proposal.md`
3. `olea-dowsure-technical-design.md`
4. `dowsure-implementation-handoff.md`

The two files under `archive/` are historical summaries and are not authoritative.

## Architecture Decision

- Dowsure is the operational custodian.
- Olea owns source policy, challenge issuance, PCR/EIF trust registration, verification, acceptance, and immutable retention.
- For the two-week PoC, use the Dowsure-hosted Nitro Enclave model unless Olea already has ready enclave infrastructure.
- Dowsure calls the Olea Challenge API with request context.
- Olea returns a scoped challenge containing request ID, single-use nonce, policy version, endpoint scope, disclosure fields, and expiry.
- Dowsure dispatches the challenge and ephemeral LWA token to the enclave over vsock.
- The parent proxy forwards encrypted traffic and does not terminate or rewrite source TLS.
- The enclave acquires, hashes, transforms, attests, signs, and encrypts evidence to Olea.
- Dowsure signs a canonical submission envelope and submits the opaque evidence bundle to Olea.
- Olea verifies and returns acceptance or rejection with a reason code.

## Required Implementation Slices

### 1. Dowsure coordinator

Implement:

- Challenge request to Olea.
- Challenge validation: request ID, endpoint, operation, nonce, expiry, policy scope.
- Ephemeral LWA token exchange and memory-only handling.
- vsock dispatch to the enclave.
- Canonical submission envelope creation.
- Dowsure submission signature.
- Evidence submission and reason-code handling.

The coordinator must never log or persist tokens, raw payloads, PII, private keys, or full presigned URLs.

### 2. Parent proxy

Implement:

- Encrypted forwarding to approved SP-API/KYC/S3/Olea destinations.
- No TLS MITM.
- Host and port allowlists.
- Explicit rejection and metrics for denied destinations.
- Correlation IDs without sensitive payload logging.

### 3. Nitro Enclave application

Implement:

- Approved endpoint and operation enforcement.
- Synchronous Orders/Finances path for bounded requests.
- Reports API path: create, poll, getReportDocument, presigned S3 download.
- In-memory GZIP decompression.
- Raw-source hash before transformation.
- Approved deterministic transformation.
- Transformed-output hash.
- Ephemeral P-256 key generation.
- Nitro attestation binding public key, request ID, nonce, and policy version.
- Enclave signature over canonical manifest.
- Encryption of evidence to Olea before returning it to the parent.
- Private-key and sensitive-buffer cleanup.

### 4. Source proof

Support the approved proof mechanism per endpoint:

- Provider-native proof where genuinely available.
- TLSNotary/MPC-TLS only after real endpoint compatibility testing.
- Metadata-only TLSNotary proof must not be represented as proof of large report bytes.
- Unsupported source-proof paths fail closed or require an explicit residual-risk exception.

### 5. Evidence contract

The evidence manifest must bind:

- Request/evidence ID.
- Source, endpoint, operation, parameters, account/marketplace context.
- Nonce, policy version, issue time, and expiry.
- Source-proof type, reference, and hash.
- Raw-source hash.
- Transformation version and manifest.
- Transformed-output hash.
- EIF release, PCRs, attestation document, and attested public key.
- Enclave signature.
- Submission envelope digest and Dowsure signature.

The Dowsure submission envelope must cover:

- Manifest digest.
- Encrypted-evidence digest.
- Request ID.
- Nonce.
- Policy version.
- Submission timestamp.

### 6. Scheduled transaction profile

If implementing the financial transaction flow:

- Capture request-level raw-response hash.
- Capture pagination and completeness metadata.
- Filter qualified transactions only after trusted raw capture.
- Calculate per-transaction `receipt_hash` from versioned canonical transaction JSON.
- Produce `integrity_metadata.json` alongside financing Excel.
- Keep `transactionId` and `postedDate` as reconciliation fields, not source proof.
- Preserve filter version, canonicalization version, source-proof reference, and included/excluded transaction metadata where policy permits.

## Explicit Interfaces to Define Before Coding

Do not invent or randomly call undefined services. Define schemas and implementations for:

- `OleaClient.requestChallenge(...)`
- `OleaClient.submitEvidence(...)`
- `EnclaveClient.acquire(...)`
- `SourceClient.fetchApproved(...)`
- `SourceProofProvider.proveResponse(...)`
- `SourceProofProvider.proveReportMetadata(...)`
- `TransformationEngine.apply(...)`
- `EvidenceEncryptor.encryptForOlea(...)`
- `SubmissionSigner.sign(...)`
- `Canonicalizer.canonicalize(...)`
- `Nonce/Challenge response reason codes`

Use Java for service examples and implementation where the target Dowsure service is Java. Keep cloud/vendor APIs behind typed adapters.

## EIF Governance

- Every EIF code change has a named owner and PR reviewer.
- CI runs tests, dependency scans, SBOM generation, reproducible build, EIF creation, and PCR measurement.
- Dowsure submits the signed release manifest and EIF digest to Olea.
- Dowsure deploys only after Olea registers the exact digest/PCR set as active.
- Revoked releases are terminal for new evidence.
- Emergency changes require rollback evidence and retrospective review.

## Acceptance Tests

The implementation is PoC-ready only when it demonstrates:

- Dowsure requests an Olea challenge for every policy-covered request.
- Missing, expired, mismatched, or out-of-scope challenges fail closed.
- The selected endpoints work through the approved path.
- No source side path bypasses the enclave.
- Tokens and sensitive payloads are absent from logs, disk, and EIF.
- Reports API and presigned S3 processing work inside enclave memory.
- Raw and transformed hashes reproduce independently.
- Source proof binds to the raw-source hash.
- Nitro attestation and PCRs match Olea’s registered release.
- Submission-envelope signatures validate.
- Invalid, stale, replayed, tampered, and revoked-release evidence is rejected.
- Monitoring, rollback, and support ownership are demonstrated.

## Definition of Done

(Folded in from the former `sam/docs/ARCHITECTURE_SUMMARY.md`, which is now a pointer
to this handoff.)

- A real Nitro Enclave, a real AWS attestation document, AWS-root validation, COSE
  (CBOR Object Signing and Encryption) validation, PCR validation, vsock, and challenge
  binding work end to end.
- TLSNotary validates and binds to the raw-source hash (today only a placeholder is in
  place; this item is not complete until a real signed proof is verified).
- The evidence manifest and the Dowsure submission signature validate.
- Olea accepts valid evidence, rejects every negative case, and retains accepted
  evidence in the immutable (S3 Object Lock) evidence vault.
- Amazon SP-API, LWA (Login with Amazon), seller accounts, reports, financial reports,
  presigned downloads, multi-region, production high availability, governance
  automation, SBOM (Software Bill of Materials) automation, and production onboarding
  remain explicitly deferred to the next phase.

## Out of Scope for This Agent

- Full production multi-region hardening.
- Broad endpoint/provider expansion.
- Funder CLI productization.
- Business-policy decisions owned by Olea Risk, Legal, or leadership.
- Changing the architecture without an explicit decision record.

## Final Deliverable

Return:

- Implemented code and tests.
- API/schema definitions.
- EIF build and PCR evidence.
- Local/PoC deployment instructions.
- Negative-test results.
- Open risks and blockers.
- A short implementation report mapped to the acceptance criteria above.
