# Step 6 — Attestation + enclave signature

## Who
**Nitro Enclave** (`EnclaveService.acquire()` — key generation, attestation request,
manifest building, signing)

## What happens
This is where trust is created. The enclave:
1. Generates a fresh EC key pair (per-request, never persisted).
2. Builds a `user_data` binding that ties the request, nonce, hashes, and public key
   together.
3. Requests a **Nitro attestation document** from the hypervisor containing the exact
   code measurements (PCR0/1/2) and the user_data binding.
4. Builds a manifest of all evidence metadata.
5. Signs the manifest with the ephemeral private key (whose public key is in the
   attestation document).

## Key generation

```java
KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
generator.initialize(new ECGenParameterSpec("secp256r1"));
KeyPair keyPair = generator.generateKeyPair();
String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
```

- Algorithm: ECDSA P-256 (`secp256r1`)
- Ephemeral: generated fresh for each request, discarded after signing
- The public key (DER SPKI, base64) is embedded in both the attestation user_data and
  the manifest, so the verifier can check the signature

## user_data binding (what goes into the attestation document)

```java
Map<String, Object> binding = new LinkedHashMap<>();
binding.put("requestId",       request.get("requestId"));
binding.put("nonce",           request.get("nonce"));
binding.put("policyVersion",   request.get("policyVersion"));
binding.put("sourceId",        sourceId);
binding.put("rawHash",         rawHash);
binding.put("transformedHash", transformedHash);
binding.put("publicKey",       publicKey);
```

This map is canonicalized (sorted keys) and converted to UTF-8 bytes, then passed to
the Nitro hypervisor's attestation API as the `user_data` field. The hypervisor
includes these bytes **inside** the signed attestation document. Nobody — not the host,
not the coordinator — can modify them after the fact.

**What this binding proves:** "This specific code image (PCR0/1/2) processed request
`requestId` with nonce `nonce` and produced hashes `rawHash`/`transformedHash`, and the
key `publicKey` belongs to this enclave instance."

## Nitro attestation document

```java
byte[] attestation = attestationProvider.generateAttestation(
    canonical(binding).getBytes(StandardCharsets.UTF_8),  // user_data
    keyPair.getPublic().getEncoded()                       // public_key field
);
```

The attestation document is a CBOR-encoded, AWS-signed structure containing:
- **PCR0**: Hash of the enclave image (code + dependencies)
- **PCR1**: Hash of the Linux kernel and boot ramdisk
- **PCR2**: Hash of the application (user layer)
- **user_data**: the binding bytes above
- **public_key**: the ephemeral public key
- **certificate chain**: AWS Nitro Attestation CA → intermediate → document signer

Anyone with the AWS Nitro root cert can verify this document is genuine. The PCRs
prove **exactly** which code produced the evidence.

### Live PCR values (current EIF `a837d673...0444`)

| PCR | Value |
|---|---|
| PCR0 | `c5e703f0...3de` |
| PCR1 | `4b4d5b36...493` |
| PCR2 | `9ab33673...1a` |

These must match the ACTIVE release entry in Olea's release table (Step 9).

## Manifest

```java
Map<String, Object> manifest = new LinkedHashMap<>();
manifest.put("requestId",             request.get("requestId"));
manifest.put("evidenceId",            request.get("evidenceId"));
manifest.put("sourceId",              sourceId);
manifest.put("nonce",                 request.get("nonce"));
manifest.put("policyVersion",         request.get("policyVersion"));
manifest.put("rawSourceHash",         rawHash);
manifest.put("transformedHash",       transformedHash);
manifest.put("canonicalizationVersion", "RFC8785-PoC");
manifest.put("attestedPublicKeyBase64", publicKey);
```

`manifestDigest` = `SHA-256(canonicalize(manifest))` — hex digest of the canonical
JSON.

## Enclave signature

```java
Signature signature = Signature.getInstance("SHA256withECDSA");
signature.initSign(keyPair.getPrivate());
signature.update(canonical(manifest).getBytes(StandardCharsets.UTF_8));
String enclaveSignature = Base64.getEncoder().encodeToString(signature.sign());
```

Signs `canonicalize(manifest)` with the ephemeral private key. The verifier recovers
the public key from `attestedPublicKeyBase64` (which is attested in the Nitro doc)
and verifies the signature at Step 9.

## Full evidence object returned

```java
evidence.put("rawPayload",                  rawPayload);
evidence.put("rawPayloadDigest",            rawHash);
evidence.put("rawResponseB64",              rawResponseB64);
evidence.put("transformedPayload",          transformed);
evidence.put("transformedPayloadDigest",    transformedHash);
evidence.put("canonicalizationVersion",     "RFC8785-PoC");
evidence.put("attestedPublicKeyBase64",     publicKey);
evidence.put("manifestDigest",             manifestDigest);
evidence.put("encryptedEvidenceReference", "vsock://opaque/" + evidenceId);
evidence.put("evidenceId",                 evidenceId);
evidence.put("eifDigest",                  eifDigest);
evidence.put("enclaveSignature",           enclaveSignature);
evidence.put("attestationDocument",        base64url(attestation));
```

## Why ephemeral keys (not a long-lived enclave key)

Each request gets a fresh key pair. The public key is bound into the attestation
document, so the verifier knows it came from this specific enclave run. If the enclave
restarts, the old key is gone — this is by design. A compromised key only affects one
request, not all historical evidence.

## What can go wrong

| Error | Cause |
|---|---|
| `ENCLAVE_KEY_GENERATION_FAILED` | EC provider not available (shouldn't happen on JDK 17+) |
| `ENCLAVE_SIGNATURE_FAILED` | Signing failed (shouldn't happen with a valid key pair) |
| Attestation provider failure | Nitro `/dev/nsm` device not available (not running in enclave) |

## Outputs → Step 7
The complete `evidence` map containing all payloads, hashes, the enclave signature,
and the attestation document. This goes back to the Coordinator over vsock.
