# Implementation: wire the rawResponseB64 producers

Branch: `wire-rawresponse` (worktree `.worktrees/wire-rawresponse`, based on main `db08610`).

## Goal

Close the gap left by the `bind-response-bytes` change (main `db08610`): the enclave and
verifier already REQUIRE `rawResponseB64`, but nothing produced it. This change wires the
producers so the notarized HTTP response bytes (sidecar `revealed_recv_b64`, STANDARD base64)
flow coordinator -> enclave request -> echoed into evidence -> coordinator result ->
`/v1/evidence` submission body, where the verifier re-checks
`sha256(base64decode(rawResponseB64)) === rawPayloadDigest`.

## Review context

`.agents/tasks/review.json` present but is the APPROVED verdict carried over from the
predecessor `bind-response-bytes` task (its `reviewDoc` points at the `bind-response-bytes`
worktree). Its single finding is informational and describes exactly this follow-up ("producers
that populate it ... out of scope for this change ... track as a separate follow-up"). There
were no actionable findings against the `wire-rawresponse` work, so this was implemented as the
first iteration per the task spec.

## Changed files and exact lines

### 1) `nitro-enclave/src/main/java/com/olea/dowsure/enclave/EnclaveService.java` (+1)
In `acquire`, immediately after `evidence.put("rawPayloadDigest", rawHash);`:
```java
evidence.put("rawResponseB64", request.get("rawResponseB64"));
```
The enclave is the authority on which bytes it hashed (`rawHash = sha256(base64decode(rawResponseB64))`,
already enforced == `tlsProof.responseHash`), so it echoes the exact base64 it received. Nothing
else in `acquire` changed. The attestation `binding` map and its field order
`{requestId,nonce,policyVersion,rawHash,transformedHash,publicKey,tlsProofHash}` are unchanged.

### 2) `coordinator/src/main/java/com/olea/dowsure/coordinator/Coordinator.java` (+4/-3)
- `enclaveRequest(...)` signature gains `String rawResponseB64` (between `rawPayload` and `tlsProof`)
  and inserts `request.put("rawResponseB64", rawResponseB64);` between the `rawPayload` and
  `tlsProof` puts. All other fields and the key insertion order are unchanged.
- `run(...)` signature gains `String rawResponseB64` (between `rawPayload` and `tlsProof`) and
  threads it into the `enclaveRequest(...)` call.
- `submissionEnvelope(...)` is UNCHANGED — the signed-field set
  `{requestId,nonce,policyVersion,evidenceId,manifestDigest,encryptedEvidenceReference,submittedAt}`
  is left exactly as is. rawResponseB64 reaches the submission body via `result.evidence`, not the
  envelope.

### 3) `coordinator/src/main/java/com/olea/dowsure/coordinator/CoordinatorMain.java` (+6/-4)
- New REQUIRED CLI arg `--raw-response-b64-file FILE` added to `USAGE` and the `KNOWN` set.
- Reads the file as UTF-8 and trims trailing whitespace:
  `String rawResponseB64 = Files.readString(rawResponseB64File, StandardCharsets.UTF_8).strip();`
- Passes `rawResponseB64` into `coordinator.run(...)`. No logging of the value (only the structured
  `result` JSON is printed to stdout, which is the vault-bound evidence output and pre-existing
  behavior — the raw bytes never appear in a log line).

### 4) `coordinator/src/test/java/com/olea/dowsure/coordinator/CoordinatorTest.java` (+9/-3)
- `enclaveRequestPassesTlsProofThroughUntouched`: calls `enclaveRequest` with a sample
  `rawResponseB64` and asserts `request.get("rawResponseB64")` equals it (separate top-level field,
  not inside tlsProof).
- `runWiresChallengeEnclaveAndSignsEnvelope`: passes `rawResponseB64` into `run(...)` and asserts
  the recorded enclave request carries it.
- `runFailsClosedWhenEnclaveRejects`: updated to the new `run(...)` arity.

### 5) `nitro-enclave/src/test/java/com/olea/dowsure/enclave/EnclaveServiceTest.java` (+2)
- `bindsTlsProofToRawResponseAndProducesManifest`: asserts the returned evidence echoes the input
  (`assertEquals(rawResponseB64, evidence.get("rawResponseB64"))`). The existing `LinkedHashMap`
  request fixture (which already supplies `rawResponseB64`) is unchanged.

### 6) `tls-notary/bundle/normalize-bundle.js` — CONFIRMED, NOT changed
The normalizer builds `tlsProof` from an explicit allowlist:
`{proofType, serverName, notaryPubKeyId, notaryKeyAlg, connectionTimeUnix, requestCommitment,
responseHash, revealedResponse, nonce, presentationB64}` then adds `proofHash`. It does NOT include
`revealed_recv_b64`, so the raw bytes never enter `tlsProof` and cannot corrupt `proofHash`. No edit
required.

## Build / test evidence (JDK 21 only, via `juse21`; system mvn 3.9.16; Windows PowerShell)

Command: `mvn -pl coordinator,nitro-enclave -am clean test` from the worktree root.
Java: Temurin 21.0.11. Maven: Apache Maven 3.9.16.

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  -- com.olea.dowsure.enclave.EnclaveServiceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0  -- com.olea.dowsure.coordinator.CanonicalizerTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  -- com.olea.dowsure.coordinator.CoordinatorTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0  -- com.olea.dowsure.coordinator.SignerTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  -- com.olea.dowsure.coordinator.VsockFramingTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0  (coordinator module total)
[INFO] BUILD SUCCESS
```
- enclave-service module: 3 tests, 0 failures.
- coordinator module: 17 tests, 0 failures.
- Reactor: BUILD SUCCESS.

Verifier JS (no change expected, confirming existing suite still green):
Command: `node --test *.test.js` in `sam/olea/functions/verification/`. Node v24.18.0.
```
# tests 28
# suites 0
# pass 28
# fail 0
# cancelled 0
# skipped 0
# todo 0
```
All 28 existing verifier tests pass.

No live AWS/SSM/ECS/docker/notarization commands were run.

## Cross-component invariants (confirmed)

- `sha256(base64decode(rawResponseB64)) === rawHash (enclave) === rawPayloadDigest (evidence)
  === tlsProof.responseHash` — enclave enforces rawHash == tlsProof.responseHash (unchanged
  TLS_PROOF_HASH_MISMATCH guard); verifier re-checks against rawPayloadDigest (unchanged). The echoed
  rawResponseB64 is the exact bytes the enclave hashed, so all four stay equal end-to-end.
- `tlsProof` and its `proofHash` are UNCHANGED — rawResponseB64 is a separate top-level request
  field, never inside tlsProof; normalize-bundle.js allowlist excludes `revealed_recv_b64`.
- Enclave attestation `user_data` binding object is UNCHANGED (field set and order preserved).
- Envelope signed-field set is UNCHANGED.
- No secrets/tokens/PII logged; the raw response bytes are never written to a log line (grep for
  System.out/err/println/console.log touching rawResponse = no matches). They live only inside the
  structured evidence/result (vault-bound), as intended.

## Reviewer note

Build/test already executed above; the reviewer should NOT re-run these suites. Evidence:
`mvn ... BUILD SUCCESS` with enclave 3 / coordinator 17 tests passing, and `node --test` 28/28 pass.
