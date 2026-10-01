ARCHIVED - historical, superseded by the active docs; do not use as current truth.

# Dowsure Data Acquisition and Provenance Design

## 1. Purpose
This document defines Dowsure’s operational role in the acquisition of external source data for Olea, including Amazon SP-API and KYC-provider workflows. Dowsure is responsible for the secure and controlled retrieval path, but it does not own the trust decision for the final data package.

## 2. Scope
Dowsure is responsible for:
- operating the source-access layer for approved partners
- maintaining and protecting long-lived operational credentials or equivalent access paths
- running the acquisition flow within an approved execution boundary
- preserving raw data and evidence in a way that can later be verified by Olea
- providing receipts and metadata needed for independent provenance checks

Dowsure is not the final arbiter of the authenticity of the upstream data. Dowsure acts as a custodian and operator, while Olea acts as the verifier and policy authority.

## 3. Operational Model
Dowsure’s role is to retrieve data through approved and controlled mechanisms, without claiming that the source is inherently trustworthy. The operating principle is:

- Dowsure performs the acquisition workflow
- a Nitro Enclave provides the approved execution boundary
- the enclave captures the raw payload and hashes it before any transformation
- Dowsure provides source, attestation, and operational metadata to Olea for independent verification

## 4. Trust Boundary
Dowsure must not be treated as the source-of-truth for the data it retrieves. Instead, Dowsure is a secure transport and proving layer whose proof is only meaningful when validated by Olea.

The trust boundary is:

- source provider: external system of record
- Dowsure enclave boundary: approved acquisition and transformation environment
- Olea verifier: independent proof validation and acceptance engine

This means Dowsure’s operational assurances are necessary but not sufficient for underwriting-grade acceptance.

## 5. Required Dowsure Responsibilities
### 5.1 Secure Source Acquisition
Dowsure must:
- maintain credentials under strict operational controls
- restrict credentials to the approved acquisition path
- minimize credential lifetime and scope to the task
- avoid broad or long-lived access outside the enclave-controlled boundary

For Amazon SP-API, Dowsure uses LWA OAuth access tokens in `x-amz-access-token`; AWS IAM credentials and SigV4 signing are not required for the SP-API request path. An ephemeral access token may be exchanged or supplied to the enclave over vsock and must remain in protected memory for the task lifetime.

### 5.2 Enclave-Managed Retrieval
Dowsure must ensure acquisition occurs through a Nitro Enclave or equivalent approved secure boundary. This is required for:
- attestation-based identity
- approved code execution
- KMS-binding for protected secrets
- controlled report and document fetching

For SP-API bulk reports, the enclave must own:
- report creation or polling workflow
- report document retrieval
- presigned S3 document retrieval
- canonicalization and hash capture before transformation

The parent EC2 proxy must forward encrypted traffic for both the regional SP-API gateway and the dynamic presigned S3 report host, including `*.s3.amazonaws.com` and regional `*.s3-external-1.amazonaws.com` destinations on port 443. It must not terminate and reconstruct the trusted upstream TLS session.

The synchronous path is used for recent Orders or Finances queries. The asynchronous Reports API path is used for historical orders, settlements, and other large artifacts: create the report, poll status, retrieve the document metadata, fetch the presigned S3 URL, then download and process the document inside the enclave. Presigned URLs may expire within minutes, so the report payload must not leave the enclave for TLSNotary processing.

### 5.3 Evidence Capture
Dowsure must preserve:
- request metadata and timestamps
- endpoint and source identity
- raw payload or report document received from the source
- enclave attestation record
- raw hash values and transformation metadata
- any proof artifact provided by upstream or TLSNotary fallback

## 6. Source Proof Requirements
Amazon SP-API does not digitally sign ordinary responses or report files. Dowsure must therefore use TLSNotary MPC-TLS or another Olea-approved source-proof mechanism for financing-relevant Amazon data. TLSNotary should normally cover the small report metadata response; Nitro Enclave acquisition remains the primary control for large presigned S3 report downloads.

For each source endpoint, Dowsure must record:
- source identity
- requested object or report identifier
- freshness window and nonce data
- provider response metadata
- proof type used
- proof validation status

Dowsure must prefer non-PII Orders and Finances fields. It must not request or retain Restricted Data Tokens or consumer PII for underwriting unless Olea explicitly approves the exception and its privacy controls.

## 7. Transformation Handling
Dowsure must not transform source data outside the attested enclave boundary. All transformation steps must be deterministic and versioned.

Transformation requirements:
- capture canonical raw payload first
- record source hash before any field selection or normalization
- apply only approved transformation rules
- generate transformation manifest with version information
- produce a replayable output hash

Dowsure must provide the transformation manifest and the output hash to Olea for independent verification.

## 8. Evidence Package Format
Dowsure must produce a structured evidence package containing:
- source metadata
- raw payload
- canonical raw hash
- source proof artifact
- enclave attestation record
- approved transformation manifest
- transformation output hash
- verification status and receipts
- immutable storage identifier

This evidence package becomes the basis for Olea’s final acceptance decision.

## 9. Operational Commitments
Dowsure commits to:
- performing data retrieval only under the approved enclave boundary
- ensuring data is not modified outside the attested execution context
- preserving the full raw evidence trail
- keeping source credential access scoped and time-bound
- making the evidence bundle available to Olea in a machine-verifiable form

## 10. Dependency on Olea Verification
Dowsure is required to support independent verification by Olea. Dowsure may provide source-access services and operational receipts, but it cannot replace the need for Olea:
- to verify the source proof
- to validate the enclave attestation
- to verify lineage and transformation integrity
- to decide whether the evidence is acceptable for underwriting

## 11. Risk Controls
The design assumes the following risks and addresses them explicitly:
- a Dowsure operator could manipulate or repackage data
- upstream source responses could be stale or replayed
- the acquisition environment could be unapproved or altered
- transform logic could diverge from the approved rule set
- raw data could be lost, relabeled, or modified before review

These risks are reduced by the combination of source proof, enclave attestation, canonicalization, replay-safe transformation, and immutable evidence capture.

## 12. PoC Execution Plan
The PoC will validate Dowsure’s operational path using one or two representative Amazon SP-API endpoints.

Required PoC validation:
- execute retrieval inside the enclave boundary
- capture raw report payload before transformation
- verify the report document is retrieved through the enclave-controlled presigned flow
- generate a canonical raw hash
- apply the approved transformation rule set
- produce the output hash and evidence package
- deliver the package to Olea for verification

The PoC must also measure LWA token handling, SP-API token-bucket throttling, TLS version and cipher compatibility, proof size and latency, pagination completeness, presigned S3 expiry behavior, GZIP decompression, report parsing, nonce replay rejection, and failure behavior. Any endpoint that cannot satisfy source proof, enclave-controlled acquisition, deterministic replay, and immutable-retention requirements must be excluded from production.

## 13. Dowsure PoC Architecture

The Dowsure implementation should keep the parent coordinator, network proxy, and enclave responsibilities separate:

```text
DOWSURE AWS ACCOUNT

  +----------------------+       vsock        +-------------------------+
  | Parent EC2           |------------------->| Nitro Enclave           |
  | coordinator          |                    | approved EIF            |
  | - receives challenge |                    | - owns TLS session      |
  | - exchanges LWA     |                    | - calls SP-API          |
  | - submits bundle     |                    | - downloads S3 report  |
  +----------+-----------+                    | - hashes/transforms    |
         |                                | - signs evidence       |
         |                                +------------+------------+
         |                                             |
         | encrypted TCP forwarding                     |
         v                                             v
      +-------------+                              +------------------+
      | vsock-proxy |----------------------------->| Amazon SP-API    |
      | no TLS MITM |                              | and report S3    |
      +-------------+                              +------------------+

  Parent EC2 -> Olea Verification Service: evidence bundle + Dowsure attribution signature
```

### Runtime boundaries

| Runtime | May access | Must not do |
| --- | --- | --- |
| Parent coordinator | Challenge metadata, ephemeral LWA token, vsock, encrypted proxy, Olea submission endpoint | Read or persist source payloads, terminate upstream TLS, transform data, or log credentials and presigned URLs. |
| Nitro Enclave | Approved request, LWA token for the task, upstream TLS session, raw response, transformation code, attestation device | Use a virtual NIC, persist credentials, call unapproved domains, or emit unapproved fields. |
| vsock proxy | Encrypted byte forwarding to approved destinations | Inspect, rewrite, cache, or substitute upstream traffic. |

The proxy allowlist must include the regional SP-API gateway, LWA token exchange from the parent host, `*.s3.amazonaws.com`, regional `*.s3-external-1.amazonaws.com`, and the Olea ingestion endpoint. The exact hostnames and ports must be recorded in the PoC deployment configuration.

## 14. Sample Dowsure PoC Code

The following TypeScript-style examples are reference pseudocode. Production implementation must use reviewed libraries for Nitro attestation, canonicalization, TLSNotary, cryptography, vsock, and AWS API calls.

### 14.1 Parent coordinator

```ts
type AcquisitionRequest = {
    requestId: string;
    nonce: string;
    endpoint: string;
    parameters: Record<string, string>;
    operation: "RECENT_ORDERS" | "RECENT_FINANCES" | "SETTLEMENT_REPORT";
};

async function runAcquisition(request: AcquisitionRequest) {
    const challenge = await olea.getChallenge(request.requestId);
    assert(challenge.nonce === request.nonce, "CHALLENGE_MISMATCH");
    assert(new Date(challenge.expiresAt).getTime() > Date.now(), "EXPIRED_CHALLENGE");

    const lwaAccessToken = await exchangeRefreshTokenInHostMemory();
    let bundle;
    try {
        bundle = await enclave.vsockCall("ACQUIRE", {
            request,
            policyVersion: challenge.policyVersion,
            accessToken: lwaAccessToken
        });
    } finally {
        wipe(lwaAccessToken);
    }
    // The evidence payload is encrypted to Olea; the parent transports it opaquely.
    const submissionSignature = await signSubmission(bundle.manifest);
    return olea.submitEvidence({
        manifest: bundle.manifest,
        encryptedEvidence: bundle.encryptedEvidence,
        submissionSignature
    });
}
```

### 14.2 Enclave source acquisition

```ts
async function fetchApprovedSource(input: {
    request: AcquisitionRequest;
    accessToken: string;
}) {
    assert(policy.allows(input.request.endpoint, input.request.operation), "ENDPOINT_DENIED");

    if (input.request.operation === "SETTLEMENT_REPORT") {
        const reportId = await spApi.createReport(input.request, input.accessToken);
        const report = await spApi.waitForReport(reportId, input.accessToken);
        const document = await spApi.getReportDocument(report.documentId, input.accessToken);
        const compressedBytes = await proxy.getBytes(document.url);
        const rawBytes = decompressGzip(compressedBytes);

        return {
            rawBytes,
            metadata: {
                reportId,
                documentId: report.documentId,
                compression: document.compressionAlgorithm,
                sourceUrlHost: new URL(document.url).hostname
            },
            proof: await proveReportMetadata(report, document)
        };
    }

    return {
        rawBytes: await spApi.getJson(input.request, input.accessToken),
        metadata: { endpoint: input.request.endpoint },
        proof: await proveResponse(input.request)
    };
}
```

### 14.3 Enclave evidence packaging

```ts
async function createEvidence(request: AcquisitionRequest, accessToken: string) {
    const keyPair = await generateEphemeralP256KeyPair();
    const attestation = await nsm.attest({
        publicKey: keyPair.publicKey,
        userData: sha256(canonicalize({
            requestId: request.requestId,
            nonce: request.nonce
        }))
    });
    const source = await fetchApprovedSource({ request, accessToken });
    const rawSourceSha256 = sha256(source.rawBytes);
    const output = transformAccordingToPolicy(source.rawBytes, request.operation);
    const transformedOutputSha256 = sha256(canonicalize(output));
    const manifest = canonicalize({
        requestId: request.requestId,
        nonce: request.nonce,
        sourceMetadata: source.metadata,
        rawSourceSha256,
        transformedOutputSha256,
        transformationVersion: policy.transformationVersion,
        attestedPublicKey: keyPair.publicKey,
        attestationSha256: sha256(attestation)
    });

    const enclaveSignature = await sign(keyPair.privateKey, sha256(manifest));
    await keyPair.privateKey.destroy();
    const evidence = {
        manifest,
        sourceResponse: source.rawBytes,
        sourceProof: source.proof,
        transformedOutput: output,
        attestation,
        enclaveSignature
    };
    return {
        manifest,
        encryptedEvidence: await encryptForOlea(evidence)
    };
}
```

### Dowsure implementation constraints

- The parent must wipe the LWA token after the enclave call and must not write it, raw payloads, PII, or presigned URLs to logs or disk.
- The enclave must encrypt the evidence payload to Olea before returning it over vsock; the parent may transport the manifest and opaque ciphertext but must not receive or inspect raw source bytes.
- `proxy.getBytes(document.url)` must stream through the enclave-controlled path and reject hosts outside the approved S3 allowlist.
- The raw hash boundary must be documented: this PoC hashes the decompressed report bytes before semantic parsing and transformation.
- Metadata-only TLSNotary evidence must be labeled as metadata proof, not as proof of the report contents.
- Dowsure must submit the complete bundle even when it expects Olea to reject it; it must not silently repair, filter, or replace failed evidence.
- The Dowsure submission signature identifies the submitting operator and does not replace source proof or Nitro attestation.

## 15. Success Criteria for Dowsure
Dowsure’s implementation is successful when it can demonstrate:
- source retrieval was performed under an attested enclave
- no raw payload was modified outside the approved path
- proof metadata can be independently verified by Olea
- every evidence bundle is retained immutably and is auditable
- operational steps can be replayed or reviewed without ambiguity

## 16. Decision Summary
Dowsure is the trusted operational custodian of the acquisition path, but not the trusted source-of-truth. Its required function is to execute and document a secure, attested, and reviewable flow that Olea can verify independently before accepting the data for underwriting or downstream decision-making.
