# Step 9 — Verification + 202 ACCEPTED

## Who
**Olea verifier Lambda** (`index.js:submitEvidence` + `verification-contract.js` +
`attestation-verifier.js`)

## What happens
The verifier runs an ordered gauntlet of checks. Any failure returns a `422 REJECTED`
with a specific `reasonCode`. If all pass, the evidence is stored and a **202 ACCEPTED**
receipt is returned.

## The verification pipeline (exact order from `submitEvidence`)

### Phase 1 — Challenge state (DynamoDB lookups)
```javascript
const challenge = await get(CHALLENGE_TABLE, {requestId});
if (!challenge)                                return 404 CHALLENGE_NOT_FOUND;
if (challenge.used)                            return 409 CHALLENGE_REPLAY;
if (Date.parse(challenge.expiresAt) <= now)    return 410 CHALLENGE_EXPIRED;
if (body.nonce !== challenge.nonce)            return 422 NONCE_MISMATCH;
if (body.policyVersion !== challenge.policyVersion) return 422 POLICY_VERSION_REVOKED;
if (!challenge.endpointScope.includes(body.sourceId)) return 422 CHALLENGE_SCOPE_MISMATCH;
```

### Phase 2 — Release / PCR check (DynamoDB `RELEASE_TABLE`)
```javascript
const release = await get(RELEASE_TABLE, {eifDigest: body.eifDigest});
if (!release)                     return 422 PCR_MISMATCH;   // unknown EIF
if (release.status === 'REVOKED') return 422 EIF_REVOKED;
if (['pcr0','pcr1','pcr2'].some(f => body[f] !== release[f])) return 422 PCR_MISMATCH;
```
This is the core TLS-in-TEE trust anchor: the submitted PCRs must match a **registered,
ACTIVE** release. That's how Olea knows the evidence came from approved enclave code.

### Phase 3 — Hash + signature + attestation (try/catch → 422 with the thrown message)
```javascript
if (body.canonicalizationVersion !== 'RFC8785-PoC')         throw CANONICALIZATION_VERSION_UNSUPPORTED;
// raw wire bytes re-hash
if (sha256(decode(rawResponseB64)) !== rawPayloadDigest)    throw RAW_PAYLOAD_HASH_MISMATCH;
// transformed re-canonicalize + re-hash
if (sha256(canonicalize(transformedPayload)) !== transformedPayloadDigest) throw TRANSFORMED_PAYLOAD_HASH_MISMATCH;
if (!SOURCE_IDS.has(sourceId))                              throw SOURCE_SCOPE_INVALID;
// rebuild manifest from submitted fields, must hash to manifestDigest
const manifest = buildManifest(body);
if (sha256(canonicalize(manifest)) !== manifestDigest)      throw MANIFEST_HASH_MISMATCH;
// envelope shape must match expected
validateEnvelope(body);                                     // else SUBMISSION_ENVELOPE_INVALID
// Dowsure signature over the envelope (key from release table)
if (!verifySignature(release.dowsurePublicKeyPem, envelope, submissionSignature)) throw DOWSURE_SIGNATURE_INVALID;
// enclave signature over the manifest (key from attested public key)
verifyEnclaveSignature(body, manifest);                     // else ENCLAVE_SIGNATURE_INVALID
// Nitro attestation: genuine AWS doc, PCRs, user_data binds everything
verifyNitroAttestation(attestationDocument, {...release, attestedPublicKeyBase64}, {
  requestId, nonce, policyVersion, sourceId,
  rawHash: rawPayloadDigest, transformedHash: transformedPayloadDigest,
  publicKey: attestedPublicKeyBase64
});
validateNonceBinding(challenge, body);                      // single-use + expiry + nonce
```

### Phase 4 — Commit (single-use enforcement)
```javascript
// atomically mark challenge used (prevents concurrent replay)
UpdateCommand(CHALLENGE_TABLE, SET used=true WHERE used=false AND nonce=body.nonce);
// → ConditionalCheckFailedException ? return 409 SUBMISSION_REPLAY
```

### Phase 5 — Store + receipt
```javascript
s3.PutObject(EVIDENCE_VAULT, `evidence/${evidenceId}.json`, body, SSE: aws:kms);
dynamo.Put(RECEIPT_TABLE, receipt);
return response(202, receipt);
```

## 202 response (receipt)

```jsonc
{
  "evidenceId":     "a1b2c3d4-...",        // string (UUID) — key to fetch this receipt later
  "requestId":      "7e7e04ee-...",        // string (UUID) — the request this receipt is for
  "status":         "ACCEPTED",            // string (enum) — ACCEPTED here; REJECTED on any failed check
  "reasonCode":     "SUCCESS",             // string (enum) — SUCCESS, or a specific reason on reject
  "manifestDigest": "9f86d081...",         // string (hex sha256) — the accepted manifest's digest
  "acceptedAt":     "2026-10-06T02:16:31Z" // string (ISO-8601 UTC) — acceptance timestamp
}
```

The receipt is retrievable later via `GET /v1/receipts/{evidenceId}`.

## What the Nitro attestation check verifies (`attestation-verifier.js`)

`verifyNitroAttestation()` decodes the CBOR attestation document and checks:
1. The document is signed by the AWS Nitro Attestation PKI (cert chain to AWS root).
2. The `pcr0/1/2` inside the document match the release (defense-in-depth — already
   checked against submitted PCRs in Phase 2).
3. The `public_key` in the document matches `attestedPublicKeyBase64` (so the enclave
   signature's key is the attested one).
4. The `user_data` in the document matches the canonical binding of
   `{requestId, nonce, policyVersion, sourceId, rawHash, transformedHash, publicKey}`
   — this ties the hardware attestation to the exact data and request.

## Why this is airtight

Every value is cross-checked against something the submitter cannot forge:
- Raw data → `rawHash` → bound in the **AWS-signed** attestation user_data
- Enclave code → PCRs → must be a **pre-registered** release
- Submitter identity → Dowsure signature → key registered at release time
- Freshness → nonce → **Olea-issued**, single-use, expiring

The host (Coordinator) sees the plaintext but can't change it without breaking the
attestation. An external attacker can't replay (nonce) or forge code (PCRs). This is
why the external notary is no longer needed — the hardware attestation does the job.

## Live result — all 7 ACCEPTED (2026-10-06)

| sourceId | evidenceId prefix | status |
|---|---|---|
| getOrderMetrics | `7e7e04ee` | 202 ACCEPTED |
| listFinancialEventGroups | `3b785acc` | 202 ACCEPTED |
| listTransactions | `b0105642` | 202 ACCEPTED |
| alicloudTelThree | `d07f6de7` | 202 ACCEPTED |
| qichachaEnterpriseVerify | `ac87b0ba` | 202 ACCEPTED |
| qichachaShixinCheck | `e8c25235` | 202 ACCEPTED |
| gutuPanoramaChecks | `71da25ed` | 202 ACCEPTED |

Against EIF `a837d6739cae6e45ba9785e0991099d85764ecdd7831a99fc7310d05aa930444`
(release label `tls-in-tee-framing`, status ACTIVE).

## Full reject reason-code reference

| reasonCode | HTTP | Meaning |
|---|---|---|
| `PAYLOAD_CORRUPTED` | 400 | Missing field / non-JSON |
| `CHALLENGE_NOT_FOUND` | 404 | No challenge for requestId |
| `CHALLENGE_REPLAY` | 409 | Challenge already used |
| `SUBMISSION_REPLAY` | 409 | Concurrent double-submit |
| `CHALLENGE_EXPIRED` | 410 | Past expiry |
| `NONCE_MISMATCH` | 422 | Nonce doesn't match challenge |
| `POLICY_VERSION_REVOKED` | 422 | Policy changed/revoked |
| `CHALLENGE_SCOPE_MISMATCH` | 422 | sourceId not in challenge scope |
| `PCR_MISMATCH` | 422 | Unknown EIF or PCRs don't match release |
| `EIF_REVOKED` | 422 | Release revoked |
| `RAW_PAYLOAD_HASH_MISMATCH` | 422 | Raw bytes don't hash to claimed digest |
| `TRANSFORMED_PAYLOAD_HASH_MISMATCH` | 422 | Transformed doesn't hash to claimed digest |
| `MANIFEST_HASH_MISMATCH` | 422 | Manifest tampered |
| `SUBMISSION_ENVELOPE_INVALID` | 422 | Envelope shape wrong |
| `DOWSURE_SIGNATURE_INVALID` | 422 | Dowsure signature fails |
| `ENCLAVE_SIGNATURE_INVALID` | 422 | Enclave signature fails |
| attestation errors | 422 | Attestation doc invalid / user_data mismatch |
