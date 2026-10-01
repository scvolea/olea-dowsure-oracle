ARCHIVED - historical, superseded by the active docs; do not use as current truth.

# Olea Data Integrity and Provenance Design

## 1. Purpose
This document defines Olea’s role in validating data provenance, authenticity, and transformation integrity for third-party source data acquired through Dowsure for Amazon SP-API and KYC provider workflows. The design is intended for a controlled proof of concept and follows a fail-closed trust model.

## 2. Scope
Olea is responsible for:
- validating source authenticity and freshness
- validating the integrity of the acquisition and transformation environment
- verifying downstream processing was not altered outside approved controls
- retaining immutable evidence for auditability and funder review
- deciding whether a package is acceptable for underwriting workflows

Olea is not the operator of the upstream data source. Olea does not own the long-lived credentials used to access third-party provider APIs. Olea does not accept a Dowsure assertion as sufficient proof by itself.

## 3. Trust Model
Olea’s trust model is layered and independent:

1. Source proof
   - provider-native signed data or authenticated session evidence
   - fallback: TLSNotary MPC-TLS proof where supported
   - reject unsigned or unverifiable responses

2. Execution proof
   - AWS Nitro Enclave attestation for the acquisition and transformation boundary
   - code identity measured and pinned by the enclave attestation registry
   - KMS access only granted to attested enclave identities

3. Transformation proof
   - canonical raw payload hash captured before transformation
   - approved transformation manifest with immutable versioning
   - replayed output hash compared with the produced artifact

4. Evidence retention
   - raw payload, proof metadata, attestation record, hash chain, and transformation logs stored in an immutable evidence vault
   - each package receives a verification receipt

5. Decision gate
   - if any required proof is missing, stale, or mismatched, the package is rejected

## 4. Positioning in the Data Flow
Olea is the independent verifier and policy authority. Dowsure remains the operational custodian of the acquisition path. The role split is:

- Dowsure: obtains data, exposes it through the enclave-controlled flow, and provides operational receipts
- Olea: verifies proof, attestation, lineage, and acceptance criteria

This separation ensures Olea does not depend on Dowsure’s operational convenience or on its claim that a “source was reached.”

## 5. Required Proof Types by Source
### 5.1 Amazon SP-API
Amazon SP-API does not provide application-layer digital signatures over ordinary JSON responses or report files. AWS SigV4 is also not the SP-API authentication model; requests use Login with Amazon (LWA) access tokens in `x-amz-access-token`. Therefore, Amazon source authenticity must be established by TLSNotary MPC-TLS or another explicitly approved source-proof mechanism, not by a presumed Amazon payload signature.

Required checks:
- source identity and endpoint match expected provider
- exact request parameters, account, marketplace, pagination, and response schema are bound to the proof
- session freshness and nonce validity
- response integrity over the exact retrieved payload
- report creation, polling, `getReportDocument`, presigned S3 download, decompression, and parsing remain under enclave control
- the parent proxy permits the regional SP-API endpoint and the presigned S3 host without terminating the trusted TLS session
- non-PII endpoints and fields are preferred; RDT access is not required for underwriting unless explicitly approved

SP-API workflows must support both synchronous REST queries for recent metrics and asynchronous Reports API flows for historical or large settlement data. TLSNotary should be limited to small metadata responses where large report-document proofs would exceed practical memory, bandwidth, or presigned-URL lifetime constraints.

### 5.2 KYC provider data
For KYC or identity verification sources, Olea requires:
- provider-native signed payloads when available
- TLSNotary or equivalent proof for sources without native signature support
- verification that the returned data matches the policy registry for the requested entity and time window

## 6. Enclave Responsibilities
The AWS Nitro Enclave acts as the approval boundary for acquisition and transformation.

Within the enclave, Olea requires:
- data retrieval only through approved code paths
- attested startup state before credential activation
- hash capture of canonical raw payload before transformation
- deterministic transformation logic with versioned configuration
- generation of a transformation receipt and output hash

The enclave must never expose raw credentials to upstream processing outside the attested boundary.

The enclave generates an ephemeral signing key, binds its public key and an Olea-issued challenge to the Nitro attestation document, and signs the canonical evidence manifest. The parent host may provide an ephemeral LWA access token over vsock, but must not persist it in the EIF or expose it to ordinary application processing.

## 7. Verification Workflow
Olea’s verification flow is:

1. Receive evidence package from Dowsure
2. Validate source proof against expected provider and endpoint policy
3. Validate Nitro attestation for the enclave and code identity
4. Validate freshness and nonce/timestamp rules
5. Recompute the canonical raw payload hash from the stored raw payload
6. Validate the transformation manifest and replay output hash
7. Confirm the evidence bundle is complete and immutable
8. Accept or reject the package

## 8. Acceptance Rules
The package is accepted only when all of the following are true:
- source proof verifies successfully
- enclave attestation verifies successfully
- raw payload hash matches the canonical record
- transformation manifest is approved and versioned
- output hash matches the replayed result
- evidence bundle is complete and retained in the vault
- freshness is within policy limits

If any rule fails, the package is rejected and logged as a failed provenance event.

## 9. Immutable Evidence Vault
Olea will maintain an immutable evidence vault with:
- raw payload
- canonicalized source block
- source proof artifacts
- enclave attestation record
- transformation manifest and version
- computed hashes
- final verification receipt
- dependent underwriting package identifiers

Logs in the vault must be append-only and must not be mutable after acceptance.

## 10. Operational Policy Requirements
Olea must define per-endpoint proof policy, including:
- proof mechanism required
- freshness window
- allowed disclosure scope
- expected response schema or version
- accepted attestation registry and code versions
- exception handling for missing or weak proof

For SP-API, the registry must additionally define whether the endpoint uses the synchronous or asynchronous path, the allowed SP-API and S3 domains, report types, rate-limit and retry behavior, PII/RDT prohibition, TLSNotary compatibility, disclosure fields, and the rule that unsupported or unsigned endpoints are rejected by default.

This policy registry becomes a required control before a source is used in production.

## 11. PoC Success Criteria
The PoC is successful only if Olea can prove all of the following for one or two representative SP-API endpoints:
- the source response was authentic and tied to the correct endpoint
- the enclave boundary was attested and approved
- the raw payload was hashed before transformation
- the output of the transformation was reproducible and hashed
- the evidence bundle was retained immutably
- Olea could independently verify the full chain without relying on Dowsure’s assertion
- presigned S3 report retrieval, decompression, and parsing occurred inside the enclave boundary
- expired or reused challenges were rejected
- an air-gapped verifier could validate the attestation, PCR registry, evidence signature, source hash, and replayed output hash
- latency, proof size, rate-limit behavior, and operational failure modes were documented

## 12. Olea Verifier Implementation

The Olea verification hub should separate challenge issuance, policy resolution, evidence verification, and immutable retention:

```text
Olea account

  +----------------+     +------------------+     +----------------------+
  | Challenge API  |---->| Policy Registry  |---->| Verification Service |
  | atomic nonce   |     | endpoints/PCRs   |     | fail-closed decision |
  +----------------+     +------------------+     +----------+-----------+
                                             |
                                             v
                                      +----------------------+
                                      | S3 Object Lock Vault |
                                      | verification receipt |
                                      +----------------------+
                                             |
                                             v
                                       Underwriting / Funder
```

The Challenge API issues a single-use nonce, request ID, approved operation, disclosure policy, policy version, and expiration. The Policy Registry controls source domains, endpoint parameters, proof requirements, approved EIF/PCR releases, transformations, freshness, privacy, and exception state. The Verification Service consumes the nonce atomically, validates the complete evidence chain, writes the immutable storage receipt, and only then releases the verification receipt to underwriting.

### Olea verification sample

```ts
async function issueChallenge(input: {
   source: "AMAZON_SP_API" | "KYC_PROVIDER";
   operation: string;
   disclosureFields: string[];
}) {
   const challenge = {
      requestId: crypto.randomUUID(),
      nonce: randomBytes(32).toString("hex"),
      source: input.source,
      operation: input.operation,
      disclosureFields: input.disclosureFields,
      policyVersion: await policyRegistry.activeVersion(input.source, input.operation),
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString()
   };

   await nonceStore.putIfAbsent({
      key: challenge.requestId,
      nonce: challenge.nonce,
      expiresAt: challenge.expiresAt,
      status: "ISSUED"
   });
   return challenge;
}

async function verifyEvidence(bundle: EvidenceBundle) {
   const policy = await policyRegistry.resolve(bundle.manifest.sourceMetadata);
   assert(policy, "UNSUPPORTED_ENDPOINT");
   assert(Date.parse(bundle.manifest.expiresAt) > Date.now(), "EXPIRED_CHALLENGE");
   assert(await nonceStore.consumeOnce(bundle.manifest.requestId, bundle.manifest.nonce), "REPLAY");

   const attestation = verifyNitroAttestation(
      bundle.attestation,
      policy.approvedPcrs,
      bundle.manifest.nonce,
      bundle.manifest.attestedPublicKey
   );
   assert(attestation.valid, "INVALID_ATTESTATION");
   assert(verifySourceProof(bundle.sourceProof, policy), "INVALID_SOURCE_PROOF");
   assert(verifySignature(
      attestation.publicKey,
      sha256(canonicalize(bundle.manifest)),
      bundle.enclaveSignature
   ), "INVALID_ENCLAVE_SIGNATURE");
   assert(
      sha256(bundle.sourceResponse) === bundle.manifest.rawSourceSha256,
      "RAW_SOURCE_HASH_MISMATCH"
   );

   const replayedOutput = applyApprovedTransformation(
      bundle.sourceResponse,
      policy.operation,
      policy.disclosureFields
   );
   assert(
      sha256(canonicalize(replayedOutput)) === bundle.manifest.transformedOutputSha256,
      "TRANSFORMATION_MISMATCH"
   );

   const storageReceipt = await immutableVault.put(bundle);
   assert(storageReceipt.objectLocked, "RETENTION_FAILURE");
   return issueVerificationReceipt({
      evidenceDigest: sha256(canonicalize(bundle.manifest)),
      storageReceipt
   });
}
```

Implementation requirements:

- `consumeOnce` must be an atomic conditional update, never a read followed by a write.
- `canonicalize` must be versioned and identical in the enclave and verifier; JSON stringification alone is insufficient.
- Metadata-only TLSNotary evidence must not be represented as proof of report contents without an approved S3 artifact trust-boundary decision.
- Verification must write the immutable vault receipt before releasing data to underwriting.

## 13. Governance and Risk Statement
This design intentionally separates trust anchors. It is designed to reduce the risk that a downstream processor, operational intermediary, or credential holder can silently alter, repackage, or misrepresent source data in a way that is not independently provable.

## 14. Decision Summary
Olea must be the source-proof verifier, not merely the recipient of a Dowsure-signed package. Olea’s acceptance decision must be based on independent proof of source identity, enclave authenticity, transformation integrity, and immutable evidence retention.

## 15. Approved Production Gate
Production commitment remains blocked until exact endpoint compatibility is demonstrated. The PoC must validate TLS version and cipher compatibility, LWA authentication, pagination, compression, report workflows, presigned S3 handling, source-proof generation, selective disclosure, nonce binding, PCR governance, deterministic transformation replay, privacy controls, immutable retention, and funder-readable evidence. A failed endpoint is excluded from production or recorded only as an explicit residual-risk exception; manual override must not convert invalid evidence into verified evidence.
