# Step 7 — Return to Coordinator + envelope signing

## Who
**Nitro Enclave** → **Coordinator** (`Coordinator.run()`, `submissionEnvelope()`,
`Signer.sign()`, `Canonicalizer`)

## What happens
The enclave returns the full evidence object over vsock (length-prefixed frame, same
as Step 3 but reversed). The Coordinator wraps it in a submission envelope and signs
that envelope with **Dowsure's** private key. Now there are two signatures on the
evidence: the enclave's (over the manifest) and Dowsure's (over the envelope).

## Return transport

The enclave's `VsockServer` writes the evidence JSON back on the same vsock connection,
length-prefixed:
```
[4 bytes: length, big-endian] [evidence JSON bytes]
```

The Coordinator's `EnclaveClient`/`AfVsockTransport` reads it the same way.

## Submission envelope

Built by `Coordinator.submissionEnvelope()`:

```java
Map<String, Object> envelope = new LinkedHashMap<>();
envelope.put("requestId",                  requestId);
envelope.put("nonce",                      challenge.get("nonce"));
envelope.put("policyVersion",              challenge.get("policyVersion"));
envelope.put("evidenceId",                 evidenceId);
envelope.put("manifestDigest",             evidence.get("manifestDigest"));
envelope.put("encryptedEvidenceReference", evidence.get("encryptedEvidenceReference"));
envelope.put("submittedAt",                isoUtcNow());  // ISO-8601 UTC
```

Example:
```json
{
  "requestId": "7e7e04ee-...",
  "nonce": "dGhpcyBpcyBhIDMyLWJ5dGU...",
  "policyVersion": "v1.0",
  "evidenceId": "a1b2c3d4-...",
  "manifestDigest": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "encryptedEvidenceReference": "vsock://opaque/a1b2c3d4-...",
  "submittedAt": "2026-10-06T02:16:30.000Z"
}
```

## Dowsure signature

```java
evidence.put("submissionEnvelope", envelope);
evidence.put("submissionSignature",
    signer.sign(dowsurePrivateKeyFile, Canonicalizer.canonicalize(envelope)));
```

- `Canonicalizer.canonicalize(envelope)` → sorted-key JSON (same RFC8785-PoC scheme)
- `Signer.sign()` → ECDSA signature with Dowsure's private key (loaded from
  `--dowsure-private-key-file`, used only here, never logged or persisted)
- The signature is base64-encoded

The verifier recovers Dowsure's public key from the **release table** (registered at
EIF registration time as `dowsurePublicKeyPem`) and verifies this signature at Step 9.

## Why two signatures

| Signature | Signed by | Over what | Proves |
|---|---|---|---|
| `enclaveSignature` | Ephemeral enclave key (attested) | `canonicalize(manifest)` | The data came from this exact attested enclave code |
| `submissionSignature` | Dowsure's private key | `canonicalize(envelope)` | Dowsure authorized this submission (non-repudiation; ties the evidence to Dowsure's identity) |

The enclave proves **integrity + provenance** (right code, untampered data). Dowsure's
signature proves **authorization** (the legitimate requester submitted this). Both are
required for a 202.

## Coordinator result object

```java
Map<String, Object> result = new LinkedHashMap<>();
result.put("requestId",  requestId);
result.put("evidenceId", evidenceId);
result.put("challenge",  challenge);   // full challenge from Step 2
result.put("evidence",   evidence);    // full evidence + envelope + both signatures
```

This result is what gets POSTed to `/v1/evidence` at Step 8.

## Why the host can't cheat here

The Coordinator (host) can see the evidence JSON — but it **cannot alter** the hashes or
the attestation document without invalidating the enclave signature and the attestation
binding. If the host changed `rawPayload`, the `rawHash` in the attestation (which the
host can't forge) would no longer match. The host's only legitimate addition is the
envelope + Dowsure signature, which is its authorized role.

## What can go wrong

| Error | Cause |
|---|---|
| vsock read failure | Enclave crashed mid-response, or framing mismatch |
| Signing failure | Dowsure private key file missing/unreadable/wrong format |

## Outputs → Step 8
The full result object. The Coordinator (or the operator) flattens `evidence` +
`challenge` fields into the submission body and POSTs to `/v1/evidence`.
