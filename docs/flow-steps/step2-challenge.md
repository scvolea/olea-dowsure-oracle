# Step 2 — Challenge issued

## Who
**Coordinator** → **Olea verifier Lambda** (`index.js:issueChallenge`)

## What happens
Coordinator POSTs to Olea to get a fresh `nonce`. The nonce is an anti-replay token
that binds this specific evidence flow to this specific challenge. Olea also returns
the active `policyVersion` and signs the challenge with its own KMS key.

## URL

```
POST https://oracle.oleainternal.com/v1/challenges
```

Origin: `https://c8tw99zmla.execute-api.ap-southeast-1.amazonaws.com/preprod/v1/challenges`

## Request payload

```jsonc
{
  "requestId":     "7e7e04ee-xxxx-...", // string (UUID) — caller-generated; keys the challenge record
  "sourceId":      "getOrderMetrics",   // string (enum) — one of the 7 registered sources
  "policyVersion": "v1.0"               // string — policy to bind; must be ACTIVE
}
```

Built by `Coordinator.challengeBody()`:
```java
static Map<String, Object> challengeBody(String requestId, String sourceId) {
    body.put("requestId", requestId);
    body.put("sourceId", sourceId);
    body.put("policyVersion", "v1.0");
}
```

## What Olea does (verifier side)

`index.js:issueChallenge(body)`:
1. Validates `requestId` and `sourceId` are present.
2. Checks `sourceId` against the allowed set of 7 (`SOURCE_IDS`). If not in the set →
   **403 `CHALLENGE_ENDPOINT_MISMATCH`**.
3. Loads the policy from DynamoDB (`POLICY_TABLE`, key = `policyVersion`). If missing
   or `status !== 'ACTIVE'` → **403 `POLICY_VERSION_REVOKED`**.
4. Generates a `nonce`: `crypto.randomBytes(32).toString('base64url')` — 32 random
   bytes, base64url-encoded.
5. Computes `issuedAt` and `expiresAt` (TTL from env `CHALLENGE_TTL_SECONDS`).
6. Signs the challenge JSON with KMS (`OLEA_SIGNING_KEY_ID`, `ECDSA_SHA_256`).
7. Stores the challenge in DynamoDB (`CHALLENGE_TABLE`, key = `requestId`,
   condition = `attribute_not_exists(requestId)` to prevent collisions).

## Response payload (201)

```jsonc
{
  "requestId":     "7e7e04ee-...",        // string (UUID) — echoed back
  "nonce":         "dGhpcyBpcyBh...",      // string (base64url, 32 random bytes) — single-use anti-replay token
  "policyVersion": "v1.0",                 // string — the ACTIVE policy locked to this challenge
  "sourceId":      "getOrderMetrics",      // string (enum) — echoed back
  "endpointScope": ["getOrderMetrics"],    // string[] — sourceIds this challenge permits at submit
  "transformationVersion": "v1.0",         // string — transform contract version from policy
  "issuedAt":      "2026-10-06T02:15:00Z", // string (ISO-8601 UTC) — issue time
  "expiresAt":     "2026-10-06T02:20:00Z", // string (ISO-8601 UTC) — submit before this or CHALLENGE_EXPIRED
  "challengeSignature": "MEUCIQ..."        // string (base64 ECDSA, KMS) — Olea's signature over the challenge (audit)
}
```

## Key fields

| Field | Purpose |
|---|---|
| `nonce` | 32 random bytes (base64url). Binds this flow — must appear unchanged in the attestation `user_data` at Step 6 and the submission at Step 8. Prevents replay. |
| `policyVersion` | Locked at challenge time. If the policy is revoked between challenge and submit, the evidence is rejected. |
| `endpointScope` | Array of `sourceId`s this challenge permits. The submit must carry a `sourceId` in this array or get `CHALLENGE_SCOPE_MISMATCH`. |
| `expiresAt` | Challenge TTL. The coordinator must complete Steps 3–8 before this clock runs out. |
| `challengeSignature` | KMS ECDSA signature over `SHA-256(JSON.stringify(challenge))`. Not checked by the coordinator — it's for Olea's own audit trail. |

## Why
Without a nonce, an attacker could capture old evidence and replay it. The nonce +
expiry + single-use flag (`used`) mean each evidence submission is fresh and
one-time-only. Olea controls the nonce, not Dowsure — so Dowsure can't pre-generate
evidence and submit it later against a stale challenge.

## What can go wrong
| Error | HTTP | Cause |
|---|---|---|
| `CHALLENGE_ENDPOINT_MISMATCH` | 403 | `sourceId` not one of the 7 |
| `POLICY_VERSION_REVOKED` | 403 | Policy inactive or missing |
| `CONFLICT` | 409 | Duplicate `requestId` (extremely unlikely with UUIDs) |
| `INVALID_REQUEST` | 400 | Missing `requestId` or `sourceId` |

## Outputs → Step 3
The full challenge response JSON (especially `nonce`, `policyVersion`). The
Coordinator feeds this into the enclave request at Step 3.
