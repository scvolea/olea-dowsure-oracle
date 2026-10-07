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

`submitEvidence` requires every one of these (missing any → **400 `PAYLOAD_CORRUPTED`**).
Field comments below (`//`) are annotations, not JSON — strip them for a real request.

```jsonc
{
  // --- identity + challenge binding ---
  "requestId":  "7e7e04ee-...",            // string (UUID) — ties challenge→evidence; must match the challenge
  "evidenceId": "a1b2c3d4-...",            // string (UUID) — S3 key the accepted evidence is stored under
  "nonce":      "dGhpcyBpcyBhIDMyLWJ5dGU...", // string (base64url, 32 bytes) — from the challenge; single-use anti-replay
  "policyVersion": "v1.0",                 // string — must equal the challenge's policyVersion
  "sourceId":   "getOrderMetrics",         // string (enum) — one of the 7 registered sources; must be in challenge scope

  // --- data payloads ---
  "rawPayload":         { "payload": [] }, // object — parsed JSON body the source returned
  "rawResponseB64":     "SFRUUC8xLjEg...", // string (base64) — the exact wire bytes (status+headers+body); verifier re-hashes this
  "rawPayloadDigest":   "3b785acc...",     // string (hex sha256) — SHA256(decode(rawResponseB64)); the origin anchor
  "transformedPayload": { "payload": [] }, // object — output Olea consumes (pass-through today → equals rawPayload content)
  "transformedPayloadDigest": "b0105642...", // string (hex sha256) — SHA256(canonicalize(transformedPayload))
  "canonicalizationVersion": "RFC8785-PoC",  // string (enum) — must be exactly "RFC8785-PoC"

  // --- manifest + envelope ---
  "manifestDigest": "9f86d081...",         // string (hex sha256) — SHA256(canonicalize(manifest)); rebuilt + checked by verifier
  "encryptedEvidenceReference": "vsock://opaque/a1b2c3d4-...", // string — opaque reference; carried in the envelope
  "submissionEnvelope":  { },              // object — canonical envelope (see step 7); what the Dowsure signature covers
  "submissionSignature": "MEUCIQ...",      // string (base64 ECDSA) — Dowsure's signature over canonicalize(submissionEnvelope)

  // --- attestation (hardware proof) ---
  "attestationDocument":     "hEShATgi...",// string (base64url CBOR) — AWS-signed Nitro attestation (PCRs + user_data + pubkey)
  "attestedPublicKeyBase64": "MFkwEwYH...",// string (base64 DER SPKI) — ephemeral enclave pubkey; must match the attestation
  "enclaveSignature":        "MEQCIE...",  // string (base64 ECDSA) — enclave's signature over canonicalize(manifest)

  // --- release identity (approved-code gate) ---
  "eifDigest": "a837d673...0444",          // string (hex sha256) — must match a registered ACTIVE release
  "pcr0": "c5e703f0...3de",                // string (hex, 48 bytes) — enclave image measurement; must match the release
  "pcr1": "4b4d5b36...493",                // string (hex, 48 bytes) — kernel/ramdisk measurement
  "pcr2": "9ab33673...1a"                  // string (hex, 48 bytes) — application measurement
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
