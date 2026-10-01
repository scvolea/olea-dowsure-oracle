# Olea-Dowsure Verifiable Data Oracle: Technical Architecture and Implementation Design

> **Plain-English summary.** Olea cannot see Amazon's data directly; Dowsure fetches
> it. The problem: a Dowsure signature only proves Dowsure signed something, not that
> Amazon actually returned those values. This design fixes that by splitting trust into
> four independent layers - (1) *source authenticity* (a proof that the data really
> came from the provider, ideally a TLSNotary proof), (2) *execution authenticity* (an
> AWS Nitro Enclave attestation proving the approved code ran), (3) *lineage integrity*
> (hashes before and after a deterministic transformation), and (4) *acceptance and
> retention* (Olea verifies, decides fail-closed, and stores evidence immutably). The
> rest of this document specifies each layer, the endpoint proof policy, the threat
> model, and the interfaces. For current status facts, see
> [docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md).
>
> Acronyms expanded on first use below: SP-API (Selling Partner API, Amazon's seller
> data API), LWA (Login with Amazon, Amazon's OAuth login), TLSNotary (a protocol that
> proves a specific HTTPS response came from a specific server), Nitro Enclave (an
> isolated, tamper-proof virtual machine with no storage and no normal network), PCR
> (Platform Configuration Register, a hash fingerprinting the enclave image), EIF
> (Enclave Image File, the artifact that boots inside the enclave), COSE (CBOR Object
> Signing and Encryption) and CBOR (Concise Binary Object Representation, the
> attestation document's signature and encoding formats), RDT (Restricted Data Token,
> Amazon's token for consumer personal data), vsock (virtual socket, the enclave's only
> communication channel), TEE (Trusted Execution Environment).

## Status

Architecture Review Board approved with minor revisions. Proceed with a controlled proof of concept using one or two representative Amazon SP-API endpoints. Production commitment remains gated by exact endpoint compatibility and PoC evidence.

## Executive Summary

Olea does not directly access Amazon SP-API or selected KYC-provider systems. Dowsure retrieves and submits source data, but a Dowsure signature proves only package attribution, not that the upstream provider returned the contents.

The approved trust model separates:

1. **Source authenticity**: provider-native proof where genuinely available; otherwise TLSNotary MPC-TLS or another approved source-proof mechanism.
2. **Execution authenticity**: AWS Nitro Enclave attestation, approved PCR measurements, and an attested ephemeral signing key.
3. **Lineage integrity**: canonical raw-source hash, deterministic transformation manifest, replayed output hash, and evidence signature.
4. **Acceptance and retention**: Olea-controlled verification policy, fail-closed acceptance, immutable evidence storage, and linkage to underwriting decisions.

Dowsure retains operational custody of credentials and infrastructure. Olea retains authority over trusted keys, source policies, enclave releases, transformations, verification, acceptance, and evidence retention.

## Amazon SP-API Production Corrections

- Amazon SP-API does not digitally sign ordinary JSON responses or report files. Generic HTTPS headers are not payload signatures.
- SP-API uses LWA OAuth access tokens in `x-amz-access-token`; AWS IAM credentials and SigV4 signing are not required for this request path.
- Underwriting should use non-PII Orders and Finances data. Restricted Data Tokens and consumer PII are prohibited unless explicitly approved by Olea privacy policy.
- Synchronous Orders and Finances calls are subject to token-bucket limits and are suitable for recent or bounded queries only.
- Historical orders, settlements, and large artifacts must use the asynchronous Reports API workflow: create report, poll status, call `getReportDocument`, fetch the presigned S3 URL, then download and process the document inside the enclave.
- Presigned report URLs may expire within minutes. The parent proxy must support the regional SP-API endpoint and dynamic S3 report hosts on port 443.
- TLSNotary is unsuitable for large multi-megabyte report streams in the normal path. It may notarize the small report metadata response, while Nitro-controlled acquisition hashes, decompresses, parses, and transforms the report. Metadata-only notarization proves the authenticated document metadata and URL, not the report bytes themselves; the PoC must explicitly validate and approve the resulting S3-to-enclave trust boundary before production.

## Target Architecture

```text
Amazon SP-API / KYC Provider
        |
        | authenticated TLS or provider-native proof
        v
Dowsure parent EC2
        | vsock; encrypted proxy forwarding
        v
Olea-approved Nitro Enclave
  acquire -> hash -> transform -> sign -> package
        |
        v
Olea verifier and policy registry
  source proof -> attestation/PCR -> nonce -> lineage -> policy
        |
        v
Olea immutable evidence vault -> underwriting -> funder assurance
```

The enclave has no virtual NIC or persistent storage. It receives an approved request context, nonce, and ephemeral LWA access token over vsock. It generates an ephemeral ECDSA P-256 key, binds the public key and challenge digest into Nitro attestation user data, performs acquisition, and signs the canonical evidence manifest. Private keys and tokens are scrubbed after use.

KMS access for protected secrets must be conditioned on the approved attestation identity and release policy. Key material must not be embedded in the EIF, parent image, logs, or persistent application storage.

### TEE hosting decision

The implementation must explicitly choose one of these deployment models before the PoC begins:

| Model | Description | Security and delivery trade-off |
| --- | --- | --- |
| Dowsure-hosted enclave | Dowsure owns the Nitro-capable EC2 instance, parent proxy, source credentials, and enclave runtime; Olea supplies or approves the EIF and independently verifies attestation | Fastest fit with Dowsure's existing source access, but Olea must govern EIF release, PCRs, KMS conditions, and verifier acceptance. |
| Olea-hosted enclave | Olea owns the EC2/enclave infrastructure and verifier; Dowsure forwards only scoped, ephemeral source credentials or approved request material | Stronger Olea operational control, but adds infrastructure, credential handoff, networking, and delivery work to the two-week timeline. |

For the two-week PoC, use the Dowsure-hosted model unless Olea already has Nitro infrastructure and the required source-access path. In either model:

- Olea owns the approved enclave application, source-policy registry, verifier, trusted keys, and PCR/release registry.
- Dowsure launches the exact approved EIF without modification when it hosts the enclave.
- Any EIF modification, rebuild, or PCR change requires a new signed release manifest and Olea approval.
- Dowsure must route all policy-covered Amazon traffic through the enclave path; an ordinary side path invalidates the coverage claim.
- The parent instance may relay vsock and encrypted network traffic, but must not inspect, rewrite, cache, or substitute source payloads.

## Endpoint Proof Policy

| Source | Operation | Required proof and controls |
| --- | --- | --- |
| Amazon SP-API Reports | Create, poll, `getReportDocument`, S3 download | Source proof is mandatory for report metadata. The PoC must separately demonstrate how the downloaded report bytes are authenticated; report workflow, S3 fetch, decompression, hashing, and parsing stay inside the enclave. |
| Amazon Orders / Finances | Selected recent or bounded queries | Source proof is mandatory. Bind exact parameters, pagination, account, marketplace, schema, freshness, and disclosure fields. |
| KYC providers | Result, callback, certificate, or report | Verify provider signature and key/certificate chain where available; otherwise use TLSNotary and the Olea trust registry. |
| Unsupported or unsigned endpoints | Any endpoint without compatible proof | Reject for production by default; allow only under an explicit residual-risk decision. |

No endpoint enters the PoC or production registry until its proof type, disclosure schema, verification rules, freshness policy, and failure behavior are documented.

## Alternative Source-Proof Architecture: TLSNotary

TLSNotary is an alternative source-proof path for bounded responses where the selected endpoint, TLS version, headers, response size, and selective-disclosure requirements are compatible. It is an interactive MPC-TLS protocol, so it must be treated as a feasibility-spike dependency rather than assumed to work against every SP-API endpoint. The PoC must test the actual Amazon endpoint before committing to this path.

### TLSNotary trust model

```text
AMAZON SP-API
          ^
          | TLS session witnessed by MPC-TLS
          |
+-----+--------------------------- DOWSURE ENVIRONMENT ------------------+
| Client prover -> TLS-forwarding proxy -> Olea-operated notary server  |
|       |                                                               |
|       +-------------------- notarized proof bundle ------------------+|
+----------------------------------------------------------------------+
                                                                          |
                                                                          v
                                                 OLEA VERIFIER / INGESTION
                          verify proof, trusted notary key, domain, policy, freshness
                                                                          |
                                                                          v
                                                          Underwriting / Vault
```

The notary public key is an independent trust anchor. Olea must publish or pin the approved notary key through an independently controlled registry, verify the notary signature on every accepted proof, validate the expected Amazon certificate/domain binding, and reject proofs signed by an unrecognized notary. A client signature alone is not a notary proof.

### TLSNotary reference skeleton

These examples are illustrative only. Exact `tlsn-js`, notary-server, proxy, TLS-version, and transcript APIs must be validated against the selected release and the real SP-API endpoint.

#### Dowsure client prover

```ts
const proof = await prove(approvedEndpoint, {
        method: "GET",
        headers: {
                "x-amz-access-token": lwaAccessToken,
                "Connection": "close",
                "Accept-Encoding": "identity"
        },
        maxTranscriptSize: policy.maxTranscriptSize,
        notaryUrl: policy.notaryUrl,
        websocketProxyUrl: policy.websocketProxyUrl
});

await olea.submitNotarizedEvidence({
        requestId,
        proofBundle: proof
});
```

The prover must use the only approved path for the financing-relevant source call. If the notary or proxy is unavailable, the call fails closed; it must not silently fall back to an ordinary unnotarized request.

#### Olea notary and verifier responsibilities

Olea must operate or explicitly approve the notary service, protect its signing key, publish the corresponding public key independently, secure and monitor the TLS-forwarding proxy, and operate the ingestion verifier. The verifier must check:

1. proof structure and notary signature
2. notary public key against the Olea trust registry
3. expected Amazon server name, certificate binding, endpoint, request parameters, and account/marketplace context
4. freshness, nonce, transcript commitment, disclosure scope, and response schema
5. transformation and immutable-retention policy before release to underwriting

```ts
async function verifyNotarizedEvidence(bundle: NotarizedBundle) {
        const result = await tlsn.verify(bundle.proofBundle);
        assert(result.valid, "INVALID_NOTARY_PROOF");
        assert(result.notaryPublicKey === policy.approvedNotaryPublicKey, "UNKNOWN_NOTARY");
        assert(result.serverName === policy.expectedServerName, "WRONG_SOURCE_DOMAIN");
        assert(result.requestHash === policy.expectedRequestHash, "WRONG_REQUEST");
        assert(result.nonce === bundle.nonce, "NONCE_MISMATCH");
        return acceptThroughStandardLineageAndRetention(bundle, result.transcript);
}
```

### TLSNotary ownership and client changes

| Area | Olea | Dowsure |
| --- | --- | --- |
| Notary service | Host, secure, monitor, patch, and operate the notary, or approve a compliant managed service | Configure the prover to use the approved service |
| Notary key | Generate, protect, rotate, publish, and revoke the notary signing key | Never generate or substitute the trusted notary key |
| TLS proxy | Operate and secure the forwarding proxy; do not terminate the trusted source session | Maintain connectivity from the prover environment |
| Verifier | Validate signature, notary key, Amazon domain/certificate, request binding, freshness, and policy | Submit the complete proof bundle without rewriting it |
| Client application | Publish schema and fail-closed acceptance contract | Replace the ordinary Amazon fetch with a prover-wrapped call and configure notary/proxy URLs |
| Operations | Alert on proof failures, key use, proxy health, and notary availability | Alert on prover failures, latency, connectivity, and source rate limits |

Dowsure must change its application path, proof-bundle schema, connectivity behavior, and failure handling. Olea must add notary infrastructure, key governance, proxy operations, verifier logic, and independently published trust material. No Amazon-side change is required, but endpoint compatibility is a hard gate.

### TLSNotary limitations

- Interactive proving adds latency and requires notary/proxy availability for every protected request.
- The protocol is relatively young and its TLS-version compatibility must be tested against SP-API.
- Large Reports API and S3 report bodies are not suitable for the normal TLSNotary path; metadata-only notarization does not prove report bytes.
- The client must not maintain an unnotarized side path for data covered by the policy.
- The notary becomes a high-value service and key-compromise target; key protection, rotation, independent publication, monitoring, and revocation are mandatory.

## Two-Layer Assurance Model

Every financing-relevant package must satisfy two independent questions:

1. **Source proof:** did the claimed Amazon or KYC provider produce the disclosed response during the authenticated interaction?
2. **Submission and lineage proof:** did Dowsure submit the same source-bound material, and can Olea reproduce the approved transformation and output?

The source proof binds the disclosed response or artifact commitment to the upstream session. The Dowsure signature binds the submitting party and package transport, but does not replace source proof. Olea must compare the source-proof disclosure or commitment with the submitted raw-source hash and reject any mismatch.

The submission signature must cover a canonical submission envelope containing the evidence manifest digest, encrypted-evidence digest, request ID, nonce, policy version, and submission timestamp. Signing only a mutable payload reference or an unscoped package identifier is insufficient.

This distinction must be reflected in evidence status:

```text
SOURCE_PROOF_VALIDATED
                                + raw-source hash matches
                                + approved transformation replay matches
                                + Dowsure submission signature validates
                                + Nitro attestation and PCRs validate
                                + immutable retention succeeds
                                = OLEA_VERIFIED
```

## Funder Verification Package

Olea should support a standalone package for funder, audit, and legal review. It must not expose credentials or unnecessary PII.

```text
FunderEvidencePackage/
        financing-reference.json
        source-evidence.json
        source-proof.bin
        source-metadata.json
        transformation-manifest.json
        signatures/
                dowsure.sig
                enclave.sig
                olea-receipt.sig
        timestamps.json
        nitro-attestation.cbor
        evidence-manifest.json
        verification-report.json
        immutable-storage-receipt.json
```

The offline or controlled verifier should report at least:

| Check | Result |
| --- | --- |
| Source identity and endpoint | Pass or reject |
| Source proof or provider signature | Pass or reject |
| Raw-source hash | Pass or reject |
| Dowsure submission signature | Pass or reject |
| Nitro attestation and PCR release | Pass or reject |
| Transformation replay and output hash | Pass or reject |
| Olea receipt integrity | Pass or reject |
| Immutable-storage receipt | Pass or reject |
| Timestamp and nonce consistency | Pass or reject |
| Overall evidence status | Verified only when all required checks pass |

Merkle roots may be added for batch evidence indexing, but they are commitments to already-proven evidence, not proof that a payload originated at Amazon. Direct re-fetch by Olea is a useful optional comparison control, not a substitute for source proof, because Olea may not possess the source credentials or reproduce the original request context.

## Threat Model Additions

| Threat | Required control | Residual risk |
| --- | --- | --- |
| Malicious Dowsure operator modifies source data | Source proof bound to disclosed data, raw hash comparison, deterministic replay, and fail-closed verifier | Source-provider compromise remains outside the boundary |
| Dowsure signs a fabricated package | Dowsure signature is treated as attribution only; source proof is mandatory | Rejection depends on compatible source proof |
| Dowsure removes adverse records | Pagination/completeness policy, report completeness checks, source-bound raw artifact, and transformation replay | Provider-side omission cannot be detected by downstream cryptography alone |
| Dowsure bypasses the enclave | Enclave-only policy path, proxy controls, attestation, and no-side-path operational tests | Availability and unauthorized side systems remain operational risks |
| Dowsure database hash is manipulated | Never accept a database hash as source proof; hash the trusted-boundary payload | Database integrity is only a secondary operational control |
| Verifier or policy registry is compromised | Separation of duties, protected KMS keys, immutable audit, release approval, and revocation | Olea control-plane compromise remains critical residual risk |
| Notary key is compromised | HSM or equivalent protection, independent key publication, rotation, revocation, and historical verification policy | Previously accepted proofs require key-status and incident review |

Do not treat ordinary TLS, a Dowsure database hash, a Dowsure signature alone, a Nitro attestation alone, a blockchain commitment, or a Merkle root alone as source authenticity proof. Each can support transport, attribution, execution, audit, or batch integrity, but none replaces an approved provider signature or source-proof mechanism.

## Scheduled Financial-Transaction Integration Profile

The existing Dowsure business flow should be preserved as a concrete application profile of this architecture:

```text
Amazon SP-API financial transactions
                -> scheduled Dowsure acquisition
                -> source proof and raw-response capture inside approved boundary
                -> filter qualified transactions
                -> canonicalize and hash each transaction independently
                -> persist transaction and integrity metadata
                -> generate financing Excel plus integrity_metadata.json
                -> SFTP delivery to Olea
                -> Olea transaction-level verification
                -> financing acceptance or selective rejection
```

### Per-transaction integrity contract

Each qualified transaction should carry:

| Field | Meaning | Security role |
| --- | --- | --- |
| `transactionId` | Provider transaction identifier from the response body | Correlation and reconciliation; not source proof by itself |
| `postedDate` | Provider posting timestamp from the response body | Time and reconciliation attribute; not a trusted timestamp by itself |
| `receiptHash` | SHA-256 of the canonical single-transaction object | Detects changes after the trusted raw-payload boundary |
| `canonicalData` | Canonical single-transaction JSON used to calculate `receiptHash` | Reproducible verification input |
| `sourceProofReference` | Provider signature or TLSNotary/source-proof reference | Binds the transaction to the upstream response where available |
| `transformationVersion` | Approved filtering and normalization version | Links output to deterministic processing |

The per-transaction hash is a T2 integrity and lineage control. It must not be labeled T1 source proof merely because `transactionId` has a provider-shaped format or `postedDate` looks plausible. Source authenticity still requires a provider signature, TLSNotary/source proof, or an explicitly approved residual-risk exception.

### Acquisition and persistence boundary

The hash must be generated from the canonical transaction object captured at the approved acquisition boundary, before database mapping, Excel conversion, or later financing selection. The qualified-order filter and any approved transformation must be recorded separately from the source hash. The package must also retain a request-level raw-response hash plus pagination and completeness metadata; otherwise hashing only qualified rows could conceal omitted or adverse source records.

```ts
for (const transaction of sourceResponse.data.transactions) {
        if (!matchesApprovedCriteria(transaction)) {
                continue;
        }

        const canonicalData = canonicalizeTransaction(transaction, policy.canonicalizationVersion);
        const receiptHash = sha256(canonicalData);

        saveTransactionIntegrity({
                transactionId: transaction.transactionId,
                postedDate: transaction.postedDate,
                receiptHash,
                canonicalData,
                sourceProofReference: source.proofReference,
                transformationVersion: policy.transformationVersion
        });
        saveBusinessTransaction(mapToBusinessTransaction(transaction));
}
```

The request-level manifest must bind the raw response hash, page/cursor sequence, source-proof reference, total or completion indicators, filter version, and the list of included and excluded transaction identifiers where policy permits.

### Canonicalization requirements

Canonicalization must be a versioned, schema-aware contract shared by Dowsure and Olea. It should define:

- recursive object-key ordering
- UTF-8 encoding and compact output
- number representation and decimal precision
- timestamp format and timezone
- array ordering rules, including whether source order is semantically meaningful
- explicit treatment of null, empty string, missing field, empty array, and empty object
- string escaping and Unicode normalization
- schema version and canonicalization test vectors

Do not apply a blanket `null -> ""` conversion to every field. That can change business meaning and make the hash reproducible but semantically misleading. Use field-type rules approved in the source policy, for example `null -> []` only for an array field when the provider contract says null and empty array are equivalent.

### Financing output and SFTP package

The financing Excel may include a `receipt_hash` column for row-level lookup. The full canonical transaction and verification metadata must be delivered separately and must be cryptographically bound to the same evidence ID and financing request:

```text
/olea-dowsure/IN/Financing_request/<request-date>/
  Financing_Request_Dowsure_<request-id>.xlsx
  integrity_metadata.json
```

The metadata file should include the hash algorithm, schema and canonicalization versions, request/evidence ID, transaction ID, posted date, receipt hash, canonical data or an encrypted reference to it, source-proof reference, transformation version, and Dowsure signature. SFTP transport is delivery, not source authenticity; receipt, checksum, file identity, and replay controls remain required.

### Olea transaction-level verification

Olea should verify each row independently:

1. Match `receipt_hash` in Excel to the corresponding metadata entry and transaction ID.
2. Recalculate SHA-256 over the exact canonical data using the declared canonicalization version.
3. Validate the source-proof reference and source-bound response or artifact commitment.
4. Validate the approved filter/transformation version and output mapping.
5. Mark only the affected transaction abnormal when row-level verification fails, unless the request-level policy requires rejecting the entire financing request.
6. Retain the verification report and link accepted rows to the immutable evidence package.

This profile supports reconciliation using provider transaction IDs, posted dates, financial event group IDs, order IDs, and financing request IDs. Those identifiers improve auditability but do not replace cryptographic source proof.

## Evidence and Lineage

Each package must bind the following into one reproducible manifest:

- evidence ID, Olea request ID, single-use nonce, issue and expiry times
- source, region, marketplace, endpoint, request parameters, report type, and retrieval time
- source-proof type, transcript commitment or provider signature, and source-proof hash
- canonical raw payload hash computed before transformation
- approved transformation version and deterministic manifest
- transformed-output hash and disclosed payload
- enclave release, PCR measurements, Nitro attestation document, and attested public key
- enclave signature and Dowsure submission signature
- canonical submission envelope and encrypted-evidence digest covered by the Dowsure signature
- Olea verification receipt and immutable storage identifier

Allowed transformations include field selection, PII redaction, date and currency normalization, documented type conversion, whitespace normalization, and approved deterministic derivation. Amounts, IDs, statuses, timestamps, and adverse records may not be changed or silently removed.

## Verification and Failure Model

Olea verifies the Nitro attestation chain to the AWS Nitro root, PCR registry, attested public key, source proof, endpoint and request binding, nonce single-use state, freshness, canonical hashes, transformation replay, schema, completeness, and immutable storage receipt.

Verification is fail-closed. Missing or invalid source proof, unapproved PCRs, revoked releases, unbound signatures, expired or reused nonces, transformation mismatch, out-of-enclave report handling, stale or incomplete data, or failed immutable retention rejects or withholds the package from underwriting. Manual override cannot represent cryptographically invalid evidence as verified.

## Privacy and Retention

Use selective disclosure and non-PII SP-API fields by default. Accepted packages are retained in an Olea-controlled S3 Object Lock vault with versioning, encryption, CloudTrail, access controls, legal holds, and policy-defined retention. Retention must comply with privacy law, contractual obligations, data minimization, and funder disclosure requirements.

## PoC Hard Gate

The PoC must use real request shapes and validate LWA authentication, endpoint compatibility, token-bucket behavior, pagination, TLS and cipher compatibility, source-proof generation, selective disclosure, report creation and polling, presigned S3 handling, compression, enclave-controlled parsing, attestation and PCR governance, nonce replay protection, deterministic transformation replay, immutable retention, latency, failure behavior, and standalone funder verification.

If any selected endpoint cannot satisfy source proof, enclave-controlled acquisition, deterministic lineage, and immutable retention, exclude it from production or document an explicit residual-risk exception.

## Implementation Architecture

The PoC should be divided into independently deployable components with separate ownership:

```text
OLEA ACCOUNT
  +----------------+     +------------------+     +----------------------+
  | Challenge API  |---->| Policy Registry  |---->| Verification Service |
  | DynamoDB nonce |     | keys, PCRs, rules|     | receipt and rejection|
  +----------------+     +------------------+     +----------+-----------+
                                                           |
                                                           v
                                                  +----------------------+
                                                  | S3 Object Lock Vault |
                                                  +----------------------+

DOWSURE ACCOUNT
  +------------------+  vsock  +---------------------+  HTTPS  +----------+
  | Parent EC2       |-------->| Nitro Enclave       |-------->| Amazon   |
  | proxy/coordinator|         | acquire/hash/sign   |         | SP-API   |
  +------------------+         +----------+----------+         +----------+
          |                                |
          | LWA token in memory            | presigned S3 download
          +--------------------------------+-----------------------> S3
```

### Component responsibilities

| Component | Owner | Responsibility |
| --- | --- | --- |
| Challenge API | Olea | Issue a single-use nonce, endpoint policy, disclosure rules, and expiry. |
| Policy Registry | Olea | Store approved domains, operations, PCRs, releases, transformations, and proof requirements. |
| Parent Coordinator | Dowsure | Exchange the LWA refresh token, dispatch the approved request over vsock, proxy encrypted traffic, and submit the bundle. |
| Nitro Enclave | Dowsure runtime, Olea-approved release | Acquire source data, obtain the report document, hash before transformation, transform, attest, sign, and emit evidence. |
| Verification Service | Olea | Verify source proof, attestation, signatures, nonce state, hashes, replayed transformation, freshness, and schema. |
| Evidence Vault | Olea | Store accepted evidence with Object Lock and return the immutable storage receipt. |

### Request sequence

```text
1. Dowsure -> Challenge API: request challenge with source, operation, and financing context
2. Challenge API -> Dowsure: { requestId, nonce, expiresAt, policyVersion, endpointScope }
3. Dowsure -> Enclave: { requestId, nonce, endpoint, parameters, LWA token }
4. Enclave -> SP-API: synchronous request or Reports API workflow
5. Enclave -> S3: presigned report download through the parent proxy
6. Enclave: rawHash -> deterministic transform -> outputHash -> attestation/signature
7. Dowsure -> Verification Service: evidence bundle + submission signature
8. Verification Service: consume nonce, verify, replay, and apply policy
9. Verification Service -> Evidence Vault: write accepted bundle with Object Lock
10. Verification Service -> Underwriting: verification receipt and evidence digest
```

## Sample PoC Code

The following TypeScript-style examples are reference pseudocode. Production code must use a reviewed canonicalization library, a Nitro attestation verifier, a source-proof verifier, constant-time signature verification, and AWS SDK clients configured with least-privilege roles.

### 1. Olea challenge issuance

```ts
type Challenge = {
        requestId: string;
        nonce: string;
        source: "AMAZON_SP_API" | "KYC_PROVIDER";
        operation: string;
        disclosureFields: string[];
        policyVersion: string;
        expiresAt: string;
};

async function issueChallenge(input: {
        source: Challenge["source"];
        operation: string;
        disclosureFields: string[];
}): Promise<Challenge> {
        const requestId = crypto.randomUUID();
        const nonce = randomBytes(32).toString("hex");
        const expiresAt = new Date(Date.now() + 5 * 60_000).toISOString();

        const challenge: Challenge = {
                requestId,
                nonce,
                source: input.source,
                operation: input.operation,
                disclosureFields: input.disclosureFields,
                policyVersion: await policyRegistry.activeVersion(input.source, input.operation),
                expiresAt
        };

        // Conditional put: requestId and nonce can be consumed only once.
        await nonceStore.putIfAbsent({
                key: requestId,
                nonce,
                expiresAt,
                status: "ISSUED"
        });
        return challenge;
}
```

### 2. Enclave acquisition and evidence signing

```ts
async function acquireEvidence(challenge: Challenge, lwaAccessToken: string) {
        const signingKey = await generateEphemeralP256KeyPair();
        const attestation = await nsm.attest({
                publicKey: signingKey.publicKey,
                userData: sha256(canonicalize({
                        requestId: challenge.requestId,
                        nonce: challenge.nonce,
                        policyVersion: challenge.policyVersion
                }))
        });

        const source = await fetchApprovedSource({
                challenge,
                accessToken: lwaAccessToken,
                proxy: "vsock://parent:443"
        });

        // For Reports API, source.rawBytes is the exact decompressed report payload
        // captured inside the enclave before parsing or transformation.
        const rawSourceSha256 = sha256(source.rawBytes);
        const transformedPayload = applyApprovedTransformation(
                source.rawBytes,
                challenge.operation,
                challenge.disclosureFields
        );
        const transformedOutputSha256 = sha256(canonicalize(transformedPayload));

        const manifest = canonicalize({
                requestId: challenge.requestId,
                nonce: challenge.nonce,
                attestedPublicKey: signingKey.publicKey,
                sourceMetadata: source.metadata,
                rawSourceSha256,
                transformationVersion: source.transformationVersion,
                transformedOutputSha256,
                attestationSha256: sha256(attestation)
        });

        const enclaveSignature = await sign(signingKey.privateKey, sha256(manifest));
        await signingKey.privateKey.destroy();
        const evidence = {
                manifest,
                sourceResponse: source.rawBytes,
                sourceProof: source.proof,
                transformedPayload,
                attestation,
                enclaveSignature
        };
        return {
                manifest,
                encryptedEvidence: await encryptForOlea(evidence)
        };
}
```

### 3. Olea verification and fail-closed acceptance

```ts
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
        const sourceProofResult = verifySourceProof(bundle.sourceProof, policy);
        assert(sourceProofResult.valid, "INVALID_SOURCE_PROOF");
        assert(
                sourceProofResult.disclosedPayloadSha256 === bundle.manifest.rawSourceSha256,
                "SOURCE_PROOF_HASH_MISMATCH"
        );
        assert(
                verifyDowsureSubmissionSignature(bundle.submissionEnvelope, bundle.submissionSignature),
                "INVALID_DOWSURE_SIGNATURE"
        );
        assert(verifySignature(
                attestation.publicKey,
                sha256(canonicalize(bundle.manifest)),
                bundle.enclaveSignature
        ), "INVALID_ENCLAVE_SIGNATURE");

        const replayedOutput = applyApprovedTransformation(
                bundle.sourceResponse,
                policy.operation,
                policy.disclosureFields
        );
        assert(
                sha256(bundle.sourceResponse) === bundle.manifest.rawSourceSha256,
                "RAW_SOURCE_HASH_MISMATCH"
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

### Implementation constraints

- `fetchApprovedSource` must allow only policy-approved domains and operations.
- The parent proxy must forward TLS and must not inspect, rewrite, or cache source payloads.
- LWA access tokens, private keys, raw PII, and presigned URLs must never be written to logs, persistent host storage, or the EIF.
- `canonicalize` must be versioned and identical in the enclave and Olea verifier; JSON stringification alone is not a sufficient canonicalization specification.
- `consumeOnce` must be an atomic conditional update, not a read followed by a write.
- Verification must write the immutable vault receipt before releasing data to underwriting.
- Metadata-only TLSNotary evidence must not be described as proof of report contents; either prove the artifact bytes directly or record the S3 artifact trust boundary as an explicit residual-risk decision.
- The parent coordinator may transport the manifest and opaque Olea-encrypted evidence, but must not receive or inspect raw source bytes.

## Responsibilities

**Dowsure** operates Nitro-capable infrastructure, protects source credentials, deploys the approved EIF, provides encrypted parent-host proxying, runs acquisition and approved transformations, submits complete evidence bundles, and signs submissions for attribution.

**Olea** owns the source-policy registry, trusted keys, PCR and release registry, evidence schema, transformation specifications, verifier, acceptance rules, privacy and retention policy, verification receipts, immutable vault, revocation, and underwriting linkage.

## Final Position

Dowsure is the operational custodian, not the original source of truth. Olea accepts financing-relevant data only when upstream source proof, Nitro execution proof, deterministic lineage, freshness, policy checks, and immutable retention form one independently reproducible chain.

## References

- <https://tlsnotary.org/docs/intro/>
- <https://tlsnotary.org/docs/faq/>
- <https://tlsnotary.org/docs/protocol/proxy-mode/>
- <https://docs.aws.amazon.com/enclaves/latest/user/nitro-enclave-concepts.html>
- <https://docs.aws.amazon.com/enclaves/latest/user/nitro-enclave.html>
- <https://docs.aws.amazon.com/enclaves/latest/user/set-up-attestation.html>
