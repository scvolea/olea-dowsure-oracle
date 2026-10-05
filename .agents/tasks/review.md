# Review: wire the rawResponseB64 producers

**Branch**: `wire-rawresponse` (worktree, based on main `db08610`)
**Scope reviewed**: diff against `main` across enclave, coordinator, coordinator main/tests, enclave test; confirmation that `normalize-bundle.js` is untouched.

The change wires the notarized HTTP response bytes (`rawResponseB64`) from the coordinator CLI through the enclave request into evidence, closing the producer gap left by the predecessor `bind-response-bytes` task (which made the enclave and verifier *require* the field but left nothing producing it). The diff is minimal and matches the task spec exactly.

**Verdict**: APPROVED

## High-level view

The enclave adds exactly one line in `acquire` — it echoes the `rawResponseB64` it received into the evidence map, positioned immediately after `rawPayloadDigest`. The enclave remains the authority on the bytes: it already decodes `rawResponseB64`, hashes it to `rawHash`, and fails closed (`TLS_PROOF_HASH_MISMATCH`) unless `rawHash === tlsProof.responseHash`. That guard is pre-existing and unchanged, so echoing the exact input bytes keeps the four-way hash identity intact.

The coordinator threads a new required `--raw-response-b64-file` CLI arg (UTF-8, trimmed) through `run(...)` and the static `enclaveRequest(...)` as a separate top-level enclave-request field placed between `rawPayload` and `tlsProof`. `tlsProof` is still passed through by the exact same reference, and the submission envelope's signed-field set is untouched.

The `normalize-bundle.js` allowlist is confirmed unchanged and continues to exclude `revealed_recv_b64`, so the raw bytes cannot enter `tlsProof` or corrupt `proofHash`.

<details>
<summary>Issues (0)</summary>

No blocking or non-blocking findings. All spec requirements and invariants verified.

</details>

<details>
<summary>Details</summary>

### Enclave: single-line echo, binding map untouched

In `EnclaveService.acquire`, the only change is:

```java
evidence.put("rawPayloadDigest", rawHash);
evidence.put("rawResponseB64", request.get("rawResponseB64"));   // added
evidence.put("transformedPayload", transformed);
```

This is exactly the line the spec requires, in the required position. Nothing else in `acquire` changed (confirmed) — read of the full method shows the `binding` map field order is still `{requestId, nonce, policyVersion, rawHash, transformedHash, publicKey, tlsProofHash}`, so the attestation `user_data` binding is byte-identical to main. The attestation is generated over `canonical(binding)`, which is key-sorted and unaffected by the new evidence entry.

The hash identity holds end to end: `rawResponseBytes = base64decode(rawResponseB64)`, `rawHash = sha256Bytes(rawResponseBytes)`, the guard `rawHash.equals(tlsProof.responseHash)` fails closed on mismatch, and `rawPayloadDigest`/`tlsProofResponseHash` are both set to `rawHash`. Echoing the exact `request.get("rawResponseB64")` means `sha256(base64decode(rawResponseB64)) === rawHash === rawPayloadDigest === tlsProof.responseHash` is preserved. Note the enclave already required and consumed `rawResponseB64` before this change; the diff only adds it to the output map.

### Coordinator: new field as separate top-level, not inside tlsProof

`enclaveRequest(...)` gains `String rawResponseB64` between `rawPayload` and `tlsProof`, and inserts `request.put("rawResponseB64", rawResponseB64)` between the `rawPayload` and `tlsProof` puts. Insertion order of every other field is unchanged, and `tlsProof` is still put by the same reference (the test `assertTrue(tlsProof == request.get("tlsProof"))` enforces pass-through). `run(...)` threads the new param into that call. `submissionEnvelope(...)` is unchanged — signed fields remain `{requestId, nonce, policyVersion, evidenceId, manifestDigest, encryptedEvidenceReference, submittedAt}`, so the submission signature contract is unaffected. `rawResponseB64` reaches the `/v1/evidence` body via `result.evidence`, not the envelope.

### CoordinatorMain: required arg, UTF-8 trimmed, no logging

`--raw-response-b64-file` is added to both the `USAGE` string and the `KNOWN` set, and read via `required(...)` (so a missing value triggers usage + non-zero exit). The value is read `Files.readString(..., UTF_8).strip()` into a `String` and passed to `run(...)`. Only the structured `result` JSON is printed to stdout (pre-existing behavior); the raw bytes are not emitted on a separate log line.

### normalize-bundle.js: allowlist excludes revealed_recv_b64

Not in the diff stat (unchanged). The `tlsProof` object is built from an explicit literal allowlist — `{proofType, serverName, notaryPubKeyId, notaryKeyAlg, connectionTimeUnix, requestCommitment, responseHash, revealedResponse, nonce, presentationB64}` plus the computed `proofHash`. `revealed_recv_b64` is not among them, and `revealedResponse` is the token-redacted preview. So the raw response bytes cannot leak into `tlsProof` or alter `proofHash`.

### Tests and verification evidence

`CoordinatorTest` and `EnclaveServiceTest` are updated for the new signatures and assert the new field: the enclave test asserts `evidence.get("rawResponseB64")` equals the input; the coordinator tests assert both the static `enclaveRequest` map and the recorded enclave request carry `rawResponseB64` as a top-level field, and the fail-closed test is updated to the new `run(...)` arity.

The coder recorded verification in `.agents/tasks/implementation.md`: `mvn -pl coordinator,nitro-enclave -am clean test` → BUILD SUCCESS with EnclaveServiceTest 3/3 and the coordinator module 17/17 passing; `node --test` in the verification function → 28/28 passing. Per task instructions these suites were not re-run; the evidence is specific (per-class counts, BUILD SUCCESS) and left no articulable doubt warranting a spot re-run.

</details>

## File map

<details>
<summary>Files changed</summary>

- `nitro-enclave/.../EnclaveService.java` — +1 line: echo `rawResponseB64` into evidence after `rawPayloadDigest`.
- `nitro-enclave/.../EnclaveServiceTest.java` — +2: assert evidence echoes the input `rawResponseB64`.
- `coordinator/.../Coordinator.java` — `enclaveRequest`/`run` gain `rawResponseB64` param; inserted as top-level request field.
- `coordinator/.../CoordinatorMain.java` — new required `--raw-response-b64-file` arg (USAGE + KNOWN), UTF-8 trimmed read, threaded to `run`.
- `coordinator/.../CoordinatorTest.java` — updated signatures; assert request map carries `rawResponseB64`.
- `.agents/tasks/implementation.md` — implementation + build/test evidence.
- `tls-notary/bundle/normalize-bundle.js` — NOT changed (confirmed allowlist excludes `revealed_recv_b64`).

Full diff: `git -C <worktree> diff main`.

</details>
