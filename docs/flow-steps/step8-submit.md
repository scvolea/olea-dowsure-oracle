# Step 8 — Submit evidence

## Who
**Coordinator / operator** → **Olea verifier Lambda** (`index.js:submitEvidence`)

## What happens
The assembled evidence (payloads + hashes + both signatures + attestation document +
PCRs) is POSTed to Olea. This is the single call that triggers all verification.

## URL

```
POST https://oracle.oleainternal.com/v1/evidence
```

Origin: `https://c8tw99zmla.execute-api.ap-southeast-1.amazonaws.com/preprod/v1/evidence`

## Request payload (all 22 required fields)

`submitEvidence` requires every one of these (missing any → **400 `PAYLOAD_CORRUPTED`**):

```json
{
  "requestId": "7e7e04ee-...",
  "evidenceId": "a1b2c3d4-...",
  "nonce": "dGhpcyBpcyBhIDMyLWJ5dGU...",
  "policyVersion": "v1.0",
  "sourceId": "getOrderMetrics",
  "encryptedEvidenceReference": "vsock://opaque/a1b2c3d4-...",
  "manifestDigest": "9f86d081...",
  "submissionEnvelope": { "...": "see step 7" },
  "submissionSignature": "MEUCIQ...",
  "rawPayload": { "payload": [ ... ] },
  "rawResponseB64": "SFRUUC8xLjEgMjAwIE9L...",
  "rawPayloadDigest": "3b785acc...",
  "transformedPayload": { "payload": [ ... ] },
  "transformedPayloadDigest": "b0105642...",
  "canonicalizationVersion": "RFC8785-PoC",
  "attestationDocument": "hEShATgioFkR...<base64url CBOR>",
  "attestedPublicKeyBase64": "MFkwEwYHKoZIzj0CAQ...",
  "enclaveSignature": "MEQCIE...",
  "eifDigest": "a837d6739cae6e45ba9785e0991099d85764ecdd7831a99fc7310d05aa930444",
  "pcr0": "c5e703f0...3de",
  "pcr1": "4b4d5b36...493",
  "pcr2": "9ab33673...1a"
}
```

## Where each field comes from

| Field group | Source |
|---|---|
| `requestId`, `evidenceId` | Coordinator (Step 1 UUIDs) |
| `nonce`, `policyVersion` | Challenge (Step 2) |
| `sourceId` | Coordinator CLI arg (Step 1) |
| `rawPayload`, `rawResponseB64`, `rawPayloadDigest`, `transformedPayload`, `transformedPayloadDigest`, `canonicalizationVersion`, `attestedPublicKeyBase64`, `manifestDigest`, `enclaveSignature`, `attestationDocument`, `encryptedEvidenceReference` | Enclave evidence (Steps 4–6) |
| `submissionEnvelope`, `submissionSignature` | Coordinator (Step 7) |
| `eifDigest` | Coordinator CLI arg (Step 1) |
| `pcr0`, `pcr1`, `pcr2` | The running enclave's measurements (recorded at EIF build, supplied with the submission) |

## Why so many fields

Each field is a check input. The verifier (Step 9) can't trust any single value in
isolation — it cross-checks:
- `rawResponseB64` is re-hashed and must equal `rawPayloadDigest`
- `transformedPayload` is re-canonicalized/re-hashed and must equal `transformedPayloadDigest`
- the manifest rebuilt from these fields must hash to `manifestDigest`
- the envelope must match the expected shape and the Dowsure signature must verify
- the attestation document must be genuine, its PCRs must match the registered release,
  and its user_data must bind all the above

The submission carries everything the verifier needs to reconstruct and re-check the
entire chain without trusting the submitter.

## What can go wrong (pre-verification)

| Error | HTTP | Cause |
|---|---|---|
| `PAYLOAD_CORRUPTED` | 400 | Any of the 22 fields missing or body not JSON |
| `CHALLENGE_NOT_FOUND` | 404 | `requestId` has no challenge record |
| `CHALLENGE_REPLAY` | 409 | Challenge already used |
| `CHALLENGE_EXPIRED` | 410 | Past `expiresAt` |

## Outputs → Step 9
The verifier runs the full verification pipeline. On success it returns **202 ACCEPTED**
with a receipt.
